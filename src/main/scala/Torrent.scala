package torentator.torrent

import java.nio.file.Path
import torentator.manifest.Manifest
import torentator.io.FileConnectionCreator
import torentator.peer.PeerPropsCreator
import akka.actor.Props
import scala.collection.immutable.BitSet


//API messages
//Get torrent download status
case object StatusRequest

sealed trait Status
//Downloading in progeress
case class Downloading(downloadedPieces: BitSet)
//Downloading completed
case object Downloaded


object Torrent {
  def props(manifest: Manifest, destination: Path,
    fileCC: FileConnectionCreator, peerPool: Props): Props =
    Props(classOf[Torrent], manifest, destination, fileCC, peerPool: Props)

  import torentator.io.Io
  import torentator.peer.Peer
  import torentator.tracker.Tracker
  import java.net.{ InetSocketAddress => Address}
  def props(manifest: Manifest, destination: Path): Props = {
    val tracker = Tracker.props(manifest)
    val peerPropsFactory = (a: Address) => Peer.props(Tracker.id, manifest.infoHash, Io.tcpConnectionProps(a))
    val pool = Props(classOf[PeerPool], 10, peerPropsFactory, tracker)
    props(manifest, destination, Io, pool)
  }
}


//Impl
import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import torentator.peer._
import torentator.io._
import torentator.manifest.SingleFileManifest
import torentator.encoding.Encoder
import java.net.{ InetSocketAddress => Address}

//Internal messages
case class PeerRequest(requester: ActorRef)
case class PieceCollected(pieceIndex: Int, data: Seq[Byte])
case class PieceSaved(pieceIndex: Int)

case class PieceHashCheckFailed(piece: Int, expected: Seq[Byte], actual: Seq[Byte]) extends RuntimeException(s"""
  PieceHashCheckFailed for piece ${piece}.
  Expected: ${expected.mkString(", ")}.
  Actual  : ${actual.mkString(", ")}""")

//Util class. Used to organise torrent actor in composable way
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

//The main actor per torrent. 
class Torrent (
  _manifest: Manifest,
  destination: Path,
  fileCC: FileConnectionCreator,
  peerPool: Props)
  extends ComposableActor with PieceHandlerCreator with StatusTracker with FileFlusher
  with akka.actor.ActorLogging {

  import Torrent._

  decider {
    case e: PieceHashCheckFailed =>
      log.warning("Failed on piece hash check {}", sender)
      Restart
    case e =>
      log.error(e, "Torrent error")
      Escalate
  }

  val manifest: SingleFileManifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }

  lazy val peers = context.actorOf(peerPool)

  val numberOfPieces = 5//java.lang.Math.ceil(manifest.length / manifest.pieceLength.toDouble).toInt
  val destinationFile = context.actorOf(fileCC.fileConnectionProps(destination))

  log.info("""Downloading torrent to {}
   pieceLength: {}. number of pieces {}""", "destination", manifest.pieceLength,
   java.lang.Math.floor(manifest.length / manifest.pieceLength).toInt)

  for (piece <- 0 until numberOfPieces)
    context.actorOf(pieceHandlerProps(piece, manifest), s"Piece_handler:${piece}")
}

//Responds on status requests.
trait StatusTracker extends ComposableActor with akka.actor.ActorLogging {
  import scala.concurrent.duration._
  import context.dispatcher

  def numberOfPieces: Int
  private var downloadedPieces = BitSet()

  private val Tick = "StatusTrackerTick"
  context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)

  receiver {
    case PieceSaved(index) =>
      downloadedPieces += index
    case StatusRequest if downloadedPieces.size == numberOfPieces =>
      sender() ! Downloaded
    case StatusRequest =>
      sender() ! Downloading(downloadedPieces)
    case Tick =>
      log.debug(s"Downloaded {}/{} : {}",
        downloadedPieces.size, numberOfPieces, downloadedPieces.mkString(", "))
  }
}

trait PieceHandlerCreator extends ComposableActor with akka.actor.ActorLogging {
  import context.dispatcher
  val torrent = self
  private val timeout = akka.util.Timeout(3.seconds)

  def peers: ActorRef
  
  def pieceHandlerProps(pieceIndex: Int, manifest: SingleFileManifest) = {
    val numberOfPieces = java.lang.Math.ceil(manifest.length / manifest.pieceLength.toDouble).toInt
    val pieceActualLength = if (pieceIndex == numberOfPieces - 1)
        manifest.length % manifest.pieceLength
      else manifest.pieceLength

    Props(classOf[PieceHandler], peers, pieceIndex, pieceActualLength, manifest.pieces(pieceIndex))
  }
}

//Writes downloaded data to file system piece by piece.
trait FileFlusher extends ComposableActor with akka.actor.ActorLogging {
  def manifest: Manifest
  def destinationFile: ActorRef

  receiver {
    case PieceCollected(index, data) =>
      destinationFile ! Send(data, index * manifest.pieceLength.toInt, index)
    case Sended(index) => 
      self ! PieceSaved(index)
  }
}

//Downloads the assigned piece
class PieceHandler(peers: ActorRef, piece: Int, totalSize: Long, hash: Seq[Byte]) extends Actor with akka.actor.ActorLogging {
  import Torrent._
  import Peer._
  import scala.collection.mutable.PriorityQueue

  implicit val timeout = akka.util.Timeout(3.seconds)
  import context.dispatcher

  override val supervisorStrategy = AllForOneStrategy(loggingEnabled = true) {
    case f => Escalate
  }

  val Tick = "PieceHandlerTick"
  context.system.scheduler.schedule(0.second, 10.seconds, self, Tick)

  val torrent = context.parent


  def download(peer: ActorRef, offset: Int) = peer ! DownloadPiece(piece, offset, Math.min(offset + 16384, totalSize))

  def downloaded = pieceData.size
  var peer: ActorRef = peers
  var done = false
  var notHandledDownloadedBlocks = new PriorityQueue[BlockDownloaded]()(Ordering.by(-_.offset))

  var pieceData = Seq.empty[Byte]

  download(peer, downloaded)

  def checkPieceHashes(pieceIndex: Int, data: Seq[Byte], expectedHash: Seq[Byte]) {
    val actual = Encoder.hash(data).toSeq
    val expected = expectedHash.toSeq
    if (actual.toSeq != expected.toSeq) throw new PieceHashCheckFailed(pieceIndex, actual, expected)
  }

  def receive = {
    case m: BlockDownloaded =>
      notHandledDownloadedBlocks enqueue m
      notHandledDownloadedBlocks = notHandledDownloadedBlocks dropWhile {
        case BlockDownloaded(pieceIndex, offset, content) if offset <= downloaded =>
          pieceData = pieceData ++ content.drop((downloaded - offset).toInt)
          true
        case _ => false
      }
      if (downloaded == totalSize) {
        checkPieceHashes(piece, pieceData, hash)
        torrent ! PieceCollected(piece, pieceData)

        context become {
          case Tick => log.debug("Piece {} downloaded.", piece)
          case r => log.debug("Piece {} downloaded. received: {}", piece, r)
        }
      } else {
        download(peer, downloaded)
      }
    // case DownloadingFailed(reason: String) =>
    //   if (!done) {
    //     log.debug("piece: {}. asked for replacement: reason: {} dwn: {};  peer {}",
    //       piece, reason, downloaded, sender())
    //     download(peer, downloaded)
    //   }
    case Tick =>
      log.info("Piece {}. Downloaded {}% [{}/{} B]. {}",
        Array(piece, 100 * downloaded / totalSize, downloaded, totalSize, peer))
  }
}
