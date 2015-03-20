package torentator

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import java.nio.file.Path

import peer._

//API messages
//Get torrent download status
case object StatusRequest

sealed trait Status
//Downloading in progeress
case object Downloading
//Downloading completed
case object Downloaded

//Internal messages
case class PeerRequest(requester: ActorRef)
case class PieceCollected(pieceIndex: Int, data: Seq[Byte])
case class PieceSaved(pieceIndex: Int)

object Torrent {
  case class PieceHashCheckFailed(piece: Int, expected: Seq[Byte], actual: Seq[Byte]) extends RuntimeException(s"""
        PieceHashCheckFailed for piece ${piece}.
        Expected: ${expected.mkString(", ")}.
        Actual  : ${actual.mkString(", ")}""")

  def checkPieceHashes(pieceIndex: Int, data: Seq[Byte], expectedHash: Seq[Byte]) {
    val actual = encoding.Encoder.hash(data).toSeq
    val expected = expectedHash.toSeq
    if (actual.toSeq != expected.toSeq) throw new PieceHashCheckFailed(pieceIndex, actual, expected)
  }

  def pieceHandlerProps(pieceIndex: Int, manifest: SingleFileManifest) = {
    val numberOfPieces = java.lang.Math.ceil(manifest.length / manifest.pieceLength.toDouble).toInt
    val pieceActualLength = if (pieceIndex == numberOfPieces - 1)
        manifest.length % manifest.pieceLength
      else manifest.pieceLength
    Props(classOf[PieceHandler], pieceIndex, pieceActualLength, manifest.pieces(pieceIndex))
  }
}

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
  fileCC: io.FileConnectionCreator,
  val peerFactory: peer.PeerPropsCreator,
  val trackerProps: Props)
  extends ComposableActor with PeerManager  with StatusTracker with FileFlusher
  with akka.actor.ActorLogging {

  import Torrent._

  def this(_manifest: Manifest, destination: Path) =
    this(_manifest, destination, io.Io, peer.Peer, tracker.Tracker.props)

  decider {
    case e: PieceHashCheckFailed =>
      log.warning("Failed on piece hash check: {}", e)
      Restart
  }

  val manifest: SingleFileManifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }

  val numberOfPieces = java.lang.Math.ceil(manifest.length / manifest.pieceLength.toDouble).toInt
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
  private var downloadedPieces = Set.empty[Int]

  private val Tick = "StatusTrackerTick"
  context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)

  receiver {
    case PieceSaved(index) =>
      downloadedPieces += index
    case StatusRequest if downloadedPieces.size == numberOfPieces =>
      sender() ! Downloaded
    case StatusRequest =>
      sender() ! Downloading
    case Tick =>
      log.debug(s"Downloaded {}/{} : {}",
        downloadedPieces.size, numberOfPieces, downloadedPieces.mkString(", "))
  }
}

//Writes downloaded data to file system piece by piece.
trait FileFlusher extends ComposableActor with akka.actor.ActorLogging {
  def manifest: Manifest
  def destinationFile: ActorRef

  receiver {
    case PieceCollected(index, data) =>
      destinationFile ! io.Send(data, index * manifest.pieceLength.toInt, index)
    case io.Sended(index) => 
      self ! PieceSaved(index)
  }
}

//Creates and replaces peers
trait PeerManager extends ComposableActor with akka.actor.ActorLogging {
  import Torrent._
  import Peer._
  import torentator.tracker._
  import context.dispatcher

  implicit val timeout = akka.util.Timeout(3.seconds)

  type Address = java.net.InetSocketAddress

  def manifest: Manifest
  def peerFactory: PeerPropsCreator
  def trackerProps: Props

  case class NewAddresses(addresses: List[Address])

  private val Tick = "PeerManagerTick"
  context.system.scheduler.schedule(0.second, 1.second, self, Tick)

  decider {
    case e: peer.DownloadingFailed if peerOwners contains sender() =>
      peerOwners(sender()) forward e
      Stop
    case e: peer.DownloadingFailed =>
      Stop
    case e if peerOwners contains sender() =>
      log.warning("Unexpected exception occur for peer: {}", e)
      peerOwners(sender()).tell(peer.DownloadingFailed(e.getMessage), sender)
      Stop
  }

  var used = Set.empty[Address]
  var available = List.empty[Address]

  lazy val tracker = context.actorOf(trackerProps)
  def newAddresses: Future[NewAddresses] = {
    def announce = (tracker ? RequestAnnounce(manifest)).mapTo[AnnounceReceived]
    def retrieveNewPeers = (response: AnnounceReceived) => response.announce.peers.filter(!used.contains(_)).toList
    announce.map(retrieveNewPeers).map(NewAddresses(_)).recoverWith{case _ => newAddresses}
  }

  var waiting = List.empty[(ActorRef, PeerRequest)]
  var peerOwners = Map.empty[ActorRef, ActorRef]

  def createPeer(address: Address) = {
    val addressEnscaped = address.toString.replaceAll("/", "")
    used = used + address
    val props = peerFactory.props(Tracker.id, manifest.hash, io.Io.tcpConnectionProps(address))
    context.actorOf(props, s"peer:${addressEnscaped}")
  }

  def respodWithAvailablePeer(requester: ActorRef, sender: ActorRef) {
    require(!available.isEmpty)
    val peer = createPeer(available.head)
    sender ! peer
    peerOwners += (peer -> requester)
    available = available.tail
  }

  receiver {
    case PeerRequest(requester) if !available.isEmpty =>
      respodWithAvailablePeer(requester, sender())
    case m: PeerRequest =>
      waiting = (sender(), m)  :: waiting
    case NewAddresses(addresses) =>
      available = addresses
    case Tick =>
      waiting = waiting dropWhile {
        case (sender, PeerRequest(requester)) if !available.isEmpty =>
          respodWithAvailablePeer(requester, sender)
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

//Downloads the assigned piece
class PieceHandler(piece: Int, totalSize: Long, hash: Seq[Byte]) extends Actor with akka.actor.ActorLogging {
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

  def newPeer: Future[ActorRef] = (torrent ? new PeerRequest(self)).mapTo[ActorRef].recoverWith {
   case f => log.debug("failed on peer creation {}", f); newPeer
  }

  def download(peer: Future[ActorRef], offset: Int) = peer onSuccess { case peer =>
    peer ! DownloadPiece(piece, offset, totalSize)
    this.peer = peer
  }

  def downloaded = pieceData.size
  var peer: ActorRef = null
  var done = false
  var notHandledDownloadedBlocks = new PriorityQueue[BlockDownloaded]()(Ordering.by(-_.offset))

  var pieceData = Seq.empty[Byte]

  download(newPeer, downloaded)

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
      }
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
