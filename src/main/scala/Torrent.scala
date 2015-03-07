package torentator 

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import io._
import peer._

case object StatusRequest

sealed trait Status
case object Downloading
case object Downloaded


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

  type PeerPropsCreator = (String, Seq[Byte], Props) => Props
}

trait ComposableActor extends Actor {
  var receivers = Array.empty[Receive]
  final var supervisorDesiders = Array.empty[Decider]  

  final val supervisorDesider = new Decider {
    def res: Decider = supervisorDesiders reduce ((l, r) => l orElse r)
    def apply(x: Throwable): Directive = res(x)
    def isDefinedAt(x: Throwable): Boolean = res isDefinedAt x
  }

  final val receive = new Receive {
    def apply(x: Any) = receivers foreach { f =>
      if (f isDefinedAt x) f(x)
    }
    def isDefinedAt(x: Any) = receivers exists (_.isDefinedAt(x))
  }

  final override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false)(supervisorDesider)

  def receiver(v: Receive) { receivers = receivers :+ v } 
  def decider(v: Decider) { supervisorDesiders = supervisorDesiders :+ v }
}

class Torrent (_manifest: Manifest, destination: java.nio.file.Path, val peerFactory: Torrent.PeerPropsCreator)
  extends ComposableActor with akka.actor.ActorLogging with PeerManager {
  import Torrent._
  import Peer._
  import scala.concurrent.duration._
  import context.dispatcher  

  def this(_manifest: Manifest, destination: java.nio.file.Path) =
    this(_manifest, destination, Peer.props)

  decider {
    case e: PieceHashCheckFailed => Restart
  }

  def manifest: SingleFileManifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val numberOfPieces = 5//java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt
  log.info("""Downloading torrent to {}
   pieceLength: {}. number of pieces {}""" + destination, manifest.pieceLength,
   java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt)

  val TorrentTick = "TorrentTick"
  context.system.scheduler.schedule(1.second, 5.seconds, self, TorrentTick)

  for (piece <- 0 until numberOfPieces)
    context.actorOf(pieceHandlerProps(piece, manifest), s"Piece_handler:${piece}")

  val destinationFile = context.actorOf(Io.fileConnectionProps(destination))

  var downloadedPieces = Set.empty[Int]

  receiver {
    case PieceCollected(index, data) =>
      downloadedPieces += index
      destinationFile ! io.Send(data, index * manifest.pieceLength.toInt)
    case StatusRequest =>
      if (downloadedPieces.size == numberOfPieces) sender() ! Downloaded
      else sender() ! Downloading
    case TorrentTick =>
      log.debug(s"Downloaded {}/{} : {}",
        downloadedPieces.size, numberOfPieces, downloadedPieces.mkString(", "))
  }
}

trait PeerManager extends ComposableActor with akka.actor.ActorLogging {
  import Torrent._
  import Peer._
  import context.dispatcher

  type Address = java.net.InetSocketAddress

  def manifest: Manifest
  def peerFactory: PeerPropsCreator

  case class NewAddresses(addresses: List[Address])

  val PeerManagerTick = "PeerManagerTick"
  context.system.scheduler.schedule(0.second, 1.second, self, PeerManagerTick)

  decider {
    case e: Peer.DownloadingFailed if peerOwners contains sender() =>
      peerOwners(sender()) forward e
      Stop
    case e: Peer.DownloadingFailed =>
      Stop
    case e if peerOwners contains sender() =>
      log.warning("Unexpected exception occur for peer: {}", e)
      peerOwners(sender()).tell(Peer.DownloadingFailed(e.getMessage), sender)
      Stop
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
    val props = peerFactory(Tracker.id, manifest.hash, Io.tcpConnectionProps(address))
    context.actorOf(props, s"peer:${addressEnscaped}")
  }

  def respodWithAvailablePeer(asker: ActorRef, sender: ActorRef) {
    require(!available.isEmpty)
    val peer = createPeer(available.head)
    sender ! peer
    peerOwners += (peer -> asker)
    available = available.tail
  }

  receiver {
    case Torrent.AskForPeer(asker) if !available.isEmpty =>
      respodWithAvailablePeer(asker, sender())
    case m: Torrent.AskForPeer =>
      waiting = (sender(), m)  :: waiting    
    case NewAddresses(addresses) => 
      available = addresses
    case PeerManagerTick => 
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

class PieceHandler(piece: Int, totalSize: Long, hash: Seq[Byte]) extends Actor with akka.actor.ActorLogging {
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
   case f => log.debug("failed on peer creation {}", f); newPeer
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
        case Tick => log.debug("Piece {} downloaded.", piece)
        case r => log.debug("Piece {} downloaded. received: {}", piece, r)
      }
    case BlockDownloaded(pieceIndex, offset, content) => 
      require(downloaded == offset,
          s"on piece ${piece} already downloaded ${this.downloaded} can not replace with ${offset}+")
      pieceData = pieceData ++ content
    case DownloadingFailed(reason: String) =>
      if (!done) {
        log.debug("piece: {}. asked for replacement: reason: {} dwn: {};  peer {}",
          piece, reason, downloaded, sender())
        sender() ! PoisonPill
        download(newPeer, downloaded)
      }
    case Tick =>
      log.debug("Piece {}. Downloaded {}% [{}/{} B]. {}",
        Array(piece, 100 * downloaded / totalSize, downloaded, totalSize, peer))
  }
}
