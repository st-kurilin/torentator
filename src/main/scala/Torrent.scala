package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

object Torrent {
  case object AskForPeer
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  import scala.concurrent.duration._
  import context.dispatcher

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e: Peer.CanNotDownload if peerOwners contains sender() =>
      peerOwners(sender()) forward e
      Stop
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
    
  val numberOfPieces = 3//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght: " + manifest.pieceLenght)
  println("piece actual number: " + java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt)

  val Tick = "tick"
  context.system.scheduler.schedule(1 seconds, 5 seconds, self, Tick)

  val peers = new Iterator[Future[ActorRef]] {
    type Address = java.net.InetSocketAddress
    var used = Set.empty[Address]
    def newAddresses: Future[List[Address]] = Tracker.announce(manifest).
      map(_.peers.filter(!used.contains(_)).toList).recoverWith{case _ => newAddresses}
    var addresses = List.empty[Address]

    def updateAddresses = newAddresses onSuccess {
      case res => addresses = res
    }

    def createPeer(address: Address) = {
      val addressEnscaped = address.toString.replaceAll("/", "")  
      used = used + address
      context.actorOf(Peer.props(Tracker.id, manifest, Props(classOf[NetworkConnection], address)), s"peer:${addressEnscaped}")
    }

    def hasNext = true
    def next = addresses match {
      case address::rest =>
        addresses = rest
        Future { createPeer(address) }
      case _ => 
        val p = Promise[ActorRef]
        newAddresses onSuccess { case address::rest =>
          addresses = rest
          p success createPeer(address)
        }
        p.future
    }
  }

  var peerOwners = Map.empty[ActorRef, ActorRef]

  for (piece <- 0 until numberOfPieces)
    context.actorOf(Props(classOf[PieceHandler], piece, manifest.pieceLenght))

  var downloadedPieces = Set.empty[Int]

  def receive: Receive = {
    case Torrent.AskForPeer =>
      val peer = peers.next
      val owner = sender()
      peer onSuccess { case p =>
        peerOwners += (p -> owner)
        owner ! p
      }
    case Peer.PieceDownloaded(index, data) =>
      downloadedPieces += index
    case Tick =>
      println(s"Downloaded ${downloadedPieces.size}/${numberOfPieces} : ${downloadedPieces.mkString(", ")}")

    case x => println("Torrent received" + x) 
  }
}

class PieceHandler(piece: Int, totalSize: Long) extends Actor {
  implicit val timeout = akka.util.Timeout(3 second)
  import context.dispatcher

  val torrent = context.parent

  def newPeer: Future[ActorRef] = (torrent ? Torrent.AskForPeer).mapTo[ActorRef].recoverWith {
   case f => println(s"failed on peer creation ${f}"); newPeer
  }

  def download(peer: Future[ActorRef], start: Int) = newPeer onSuccess { case peer =>
    peer ! Peer.DownloadPiece(piece, start, totalSize)
  }

  var downloaded = 0

  download(newPeer, downloaded)

  def receive = {
    case Peer.PieceDownloaded(index, data) =>
      torrent ! Peer.PieceDownloaded(index, data)
    case Peer.PiecePartiallyDownloaded(piece, downloaded, data) =>
      require(this.downloaded <= downloaded)
      this.downloaded = downloaded.toInt
      download(newPeer, this.downloaded)
    case Peer.CanNotDownload (reason) => 
      println(s"peer ${sender} will be replaced due ${reason}")
      download(newPeer, downloaded)
      Stop
  }
}
