package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

object Torrent {
  case class AskForPeer(asker: ActorRef)
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  import scala.concurrent.duration._
  import context.dispatcher

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e: Throwable =>
      println("------------------------")
      e.printStackTrace
      println("------------------------")
      Escalate
  }

  val manifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val numberOfPieces = 5//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght: " + manifest.pieceLenght)
  println("piece actual number: " + java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt)

  val Tick = "tick"
  context.system.scheduler.schedule(1 seconds, 5 seconds, self, Tick)

  for (piece <- 0 until numberOfPieces){
    val p = context.actorOf(Props(classOf[PieceHandler], piece, manifest.pieceLenght), s"Piece_handler:${piece}")
    println("create piece manager "  + p)
  }


  var downloadedPieces = Set.empty[Int]

  val peerManager = context.actorOf(Props(classOf[PeerManager], manifest))

  def receive: Receive = {
    case m: Torrent.AskForPeer =>
      peerManager forward m
    case Peer.PieceDownloaded(index, data) =>
      downloadedPieces += index
    case Tick =>
      println(s"Downloaded ${downloadedPieces.size}/${numberOfPieces} : ${downloadedPieces.mkString(", ")}")

    case x => println("Torrent received" + x) 
  }
}

class PeerManager(manifest: Manifest) extends Actor {
  import context.dispatcher
  type Address = java.net.InetSocketAddress
  case class NewAddresses(addresses: List[Address])

  val Tick = "tick"
  context.system.scheduler.schedule(0 seconds, 1 seconds, self, Tick)

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e: Peer.DownloadingFailed if peerOwners contains sender() =>
      println(s"catched DownloadingFailed from ${sender()}; owner: ${peerOwners(sender())}")
      peerOwners(sender()) forward e
      Stop
    case e: Peer.DownloadingFailed =>
      println ("???Downloading failed for peer without owner")
      Stop
    case _ => Escalate
  }

  var used = Set.empty[Address]
  var available = List.empty[Address]

  def newAddresses: Future[NewAddresses] = Tracker.announce(manifest).
    map ( _.peers.filter(!used.contains(_)).toList).
    map(NewAddresses(_)).
    recoverWith{case _ => newAddresses}
    

  var waiting = List.empty[(ActorRef, Torrent.AskForPeer)]
  var peerOwners = Map.empty[ActorRef, ActorRef]

  def createPeer(address: Address) = {
    val addressEnscaped = address.toString.replaceAll("/", "")  
    used = used + address
    val props = Peer.props(Tracker.id, manifest, Props(classOf[NetworkConnection], address))
    context.actorOf(props, s"peer:${addressEnscaped}")
  }

  def respodWithAvailablePeer(asker: ActorRef, sender: ActorRef) {
    require(!available.isEmpty)
    val peer = createPeer(available.head)
    sender ! peer
    peerOwners += (peer -> asker)
    available = available.tail
  }

  def receive = {
    case Torrent.AskForPeer(asker) if !available.isEmpty =>
      respodWithAvailablePeer(asker, sender())
    case m: Torrent.AskForPeer =>
      waiting = (sender(), m)  :: waiting    
    case NewAddresses(addresses) => 
      available = addresses
    case Tick => 
      waiting = waiting dropWhile { 
        case (sender, Torrent.AskForPeer(asker)) if !available.isEmpty => 
          respodWithAvailablePeer(asker, sender)
          true
        case _ => false
      }
      if (available.size < 2) {
        newAddresses onSuccess { case a => 
          self ! a
        }
      }
  }
}

class PieceHandler(piece: Int, totalSize: Long) extends Actor {
  implicit val timeout = akka.util.Timeout(3 second)
  import context.dispatcher

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case f => println("PieceHandler failed: " + f); Escalate
  }

  val Tick = "tick"
  context.system.scheduler.schedule(0 seconds, 10 seconds, self, Tick)

  val torrent = context.parent

  def newPeer: Future[ActorRef] = (torrent ? new Torrent.AskForPeer(self)).mapTo[ActorRef].recoverWith {
   case f => println(s"failed on peer creation ${f}"); newPeer
  }

  def download(peer: Future[ActorRef], start: Int) = newPeer onSuccess { case peer =>
    peer ! Peer.DownloadPiece(piece, start, totalSize)
    this.peer = peer
  }

  var downloaded = 0
  var downloadedReported = 0
  var peer: ActorRef = null
  var done = false

  download(newPeer, downloaded)

  def receive = {
    case Peer.PieceDownloaded(index, data) =>
      torrent ! Peer.PieceDownloaded(index, data)
      downloaded = totalSize.toInt
      downloadedReported = totalSize.toInt
      context become {
        case Tick => println(s"Piece ${piece} downloaded.")
        case r => println(s"Piece ${piece} downloaded. received: ${r}")
      }
    case Peer.PiecePartiallyDownloaded(piece, downloaded, data) =>
      println(s"piece: ${piece}. asked for replacement: dwn: ${downloaded};  peer ${sender()}")
      sender() ! PoisonPill
      require(this.downloaded <= downloaded,
          s"on piece ${piece} already downloaded ${this.downloaded} can not replace with ${downloaded}")
      this.downloaded = downloaded.toInt
      download(newPeer, this.downloaded)
      downloadedReported = Math.max(downloadedReported, this.downloaded)
    case Peer.Downloading(downloading) =>
      downloadedReported = Math.max(downloading, this.downloaded)
    case Peer.DownloadingFailed (reason) => 
      if (!done) {
        println(s"peer ${sender()} will be replaced due ${reason}")
        download(newPeer, downloaded)  
      }
    case Tick =>
      println(s"Piece ${piece}. Downloaded ${100*downloadedReported/totalSize}% [${downloadedReported}/${totalSize} B]. ${peer}")

  }
}
