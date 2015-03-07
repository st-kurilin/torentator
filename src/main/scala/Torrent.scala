package torentator 

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import io._

object Torrent {
  case class AskForPeer(asker: ActorRef)
  case class PieceCollected(pieceIndex: Int, data: Seq[Byte])

  case class PieceHashCheckFailed(piece: Int, expected: Seq[Byte], actual: Seq[Byte]) extends RuntimeException(s"""
        PieceHashCheckFailed for piece ${piece}.
        Expected: ${expected.mkString(", ")}.
        Actual  : ${actual.mkString(", ")}""") 

  def checkPieceHashes(pieceIndex: Int, data: Seq[Byte], expectedHash: Seq[Byte]) {
    val actual = Bencoding.hash(data).toSeq
    val expected = expectedHash.toSeq
    if (actual != expected) throw new PieceHashCheckFailed(pieceIndex, actual, expected)
  }

  def pieceHandlerProps(pieceIndex: Int, manifest: SingleFileManifest) = {
    val numberOfPieces = java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt
    val pieceActualLength = if (pieceIndex == numberOfPieces - 1)
        manifest.length % manifest.pieceLength
      else manifest.pieceLength
    Props(classOf[PieceHandler], pieceIndex, pieceActualLength, manifest.pieces(pieceIndex))
  }
}

class Torrent(_manifest: Manifest, destination: java.nio.file.Path) extends Actor {
  import Torrent._
  import Peer._
  import scala.concurrent.duration._
  import context.dispatcher  

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) {
    case e: PieceHashCheckFailed => Restart
    case e: Throwable => Escalate
  }

  val manifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val numberOfPieces = 5//java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt
  println("pieceLength: " + manifest.pieceLength)
  println("piece actual number: " + java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt)

  val Tick = "tick"
  context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)

  for (piece <- 0 until numberOfPieces)
    context.actorOf(pieceHandlerProps(piece, manifest), s"Piece_handler:${piece}")

  val peerManager = context.actorOf(Props(classOf[PeerManager], manifest))
  val destinationFile = context.actorOf(Io.fileConnectionProps(destination))

  var downloadedPieces = Set.empty[Int]

  def receive: Receive = {
    case m: Torrent.AskForPeer =>
      peerManager forward m
    case PieceCollected(index, data) =>
      downloadedPieces += index
      destinationFile ! io.Send(data, index * manifest.pieceLength.toInt)
    case Tick =>
      println(s"Downloaded ${downloadedPieces.size}/${numberOfPieces} : ${downloadedPieces.mkString(", ")}")
    case x => println("Torrent received" + x) 
  }
}

class PeerManager(manifest: Manifest) extends Actor {
  import Torrent._
  import Peer._
  import context.dispatcher

  type Address = java.net.InetSocketAddress
  case class NewAddresses(addresses: List[Address])

  val Tick = "tick"
  context.system.scheduler.schedule(0.second, 1.second, self, Tick)

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) {
    case e: Peer.DownloadingFailed if peerOwners contains sender() =>
      peerOwners(sender()) forward e
      Stop
    case e: Peer.DownloadingFailed =>
      Stop
    case _ => Escalate
  }

  var used = Set.empty[Address]
  var available = List.empty[Address]

  def newAddresses: Future[NewAddresses] = Tracker.announce(manifest).
    map ( _.peers.filter(!used.contains(_)).toList).
    map(NewAddresses(_)).
    recoverWith{case _ => newAddresses}
    
  var waiting = List.empty[(ActorRef, AskForPeer)]
  var peerOwners = Map.empty[ActorRef, ActorRef]

  def createPeer(address: Address) = {
    val addressEnscaped = address.toString.replaceAll("/", "")  
    used = used + address
    val props = Peer.props(Tracker.id, manifest, Io.tcpConnectionProps(address))
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

class PieceHandler(piece: Int, totalSize: Long, hash: Seq[Byte]) extends Actor {
  import Torrent._
  import Peer._

  implicit val timeout = akka.util.Timeout(3.seconds)
  import context.dispatcher

  override val supervisorStrategy = AllForOneStrategy(loggingEnabled = true) {
    case f => Escalate
  }

  val Tick = "tick"
  context.system.scheduler.schedule(0.second, 10.seconds, self, Tick)

  val torrent = context.parent

  def newPeer: Future[ActorRef] = (torrent ? new AskForPeer(self)).mapTo[ActorRef].recoverWith {
   case f => println(s"failed on peer creation ${f}"); newPeer
  }

  def download(peer: Future[ActorRef], offset: Int) = newPeer onSuccess { case peer =>
    peer ! DownloadPiece(piece, offset, totalSize)
    this.peer = peer
  }

  def downloaded = pieceData.size
  var peer: ActorRef = null
  var done = false

  var pieceData = Seq.empty[Byte]

  download(newPeer, downloaded)

  def receive = {
    case PieceDownloaded(index) =>
      checkPieceHashes(piece, pieceData, hash)
      torrent ! PieceCollected(index, pieceData)
      
      context become {
        case Tick => println(s"Piece ${piece} downloaded.")
        case r => println(s"Piece ${piece} downloaded. received: ${r}")
      }
    case BlockDownloaded(pieceIndex, offset, content) => 
      require(downloaded == offset,
          s"on piece ${piece} already downloaded ${this.downloaded} can not replace with ${offset}+")
      pieceData = pieceData ++ content
    case DownloadingFailed(reason: String) =>
      if (!done) {
        println(s"piece: ${piece}. asked for replacement: reason: ${reason} dwn: ${downloaded};  peer ${sender()}")
        sender() ! PoisonPill
        download(newPeer, downloaded)
      }
    case Tick =>
      println(s"Piece ${piece}. Downloaded ${100*downloaded/totalSize}% [${downloaded}/${totalSize} B]. ${peer}")
  }
}
