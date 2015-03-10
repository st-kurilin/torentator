package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  import Torrent._
  import akka.testkit.TestProbe
  import scala.util.Random

  
  
  val (manifest: Manifest, content: Seq[Seq[Byte]]) = {
    val pieceLength = Math.pow(2, 10).toInt
    val numberOfPieces = 5
    val totalLength = numberOfPieces * pieceLength.toInt - pieceLength.toInt / 2
    val hashSize = 20
    val content = Seq.tabulate(totalLength)(x => 2.toByte).grouped(pieceLength).toSeq
    val m = SingleFileManifest(
      name = "test manifest",
      announce = new java.net.URI("fake://fake"),
      hash = Seq.fill(hashSize)(1.toByte),
      pieces = Seq.tabulate(numberOfPieces)(piece => Bencoding.hash(content(piece))),
      pieceLength = pieceLength,
      length = totalLength)
    (m, content)
  }

  def readContent(piece: Int, offset: Int, length: Int) = content(piece).slice(offset, offset + length.toInt)

  def perfectPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new PerfectPeer())

  def byBlockDownloaderPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new ByBlockDownloaderPeer())

  def singleBlockPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new BlockDownloaderPeer())

  def failingPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new FailingPeer())

  def unreliablePeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new UnreliablePeer())

  def wrongDataPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new WrongDataPeer())

  def newTorrent(peerCreator: Torrent.PeerPropsCreator) = {
    val trackerMock = Props(new Actor {
      def receive = {
         case tracker.RequestAnnounce(manifest) =>
          def randInt = scala.util.Random.nextInt(250) + 1
          val randPeers = List.fill(10)(new java.net.InetSocketAddress(s"${randInt}.${randInt}.${randInt}.${randInt}", 10)).toSet
          sender() ! tracker.AnnounceReceived(tracker.Tracker.Announce(0, randPeers))
      }
    })
    val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    val name = "torrent" + scala.util.Random.nextInt
    system.actorOf(Props(classOf[Torrent], manifest, destination, peerCreator, trackerMock), name) 
  }

  def newTest(peerCreator: Torrent.PeerPropsCreator): (TestProbe, ActorRef) 
    = (TestProbe(), newTorrent(peerCreator))

  "Torrent" must {
    "download file using perfect peers" in {
      val (client, torrent) = newTest(perfectPeer)
      torrent.tell(StatusRequest, client.ref)
      client.ignoreMsg {
        case Downloading => torrent.tell(StatusRequest, client.ref); true
      }
      client.expectMsg(Downloaded)

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloaded)
    }

    "not download file if hash sum check for piece failing" in {
      val (client, torrent) = newTest(wrongDataPeer)
      torrent.tell(StatusRequest, client.ref)
      client.receiveWhile(3.seconds) {
        case x @ Downloading => torrent.tell(StatusRequest, client.ref); x
      }

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloading)
    }

    "download file using by block downloader peer" in {
      val (client, torrent) = newTest(byBlockDownloaderPeer)
      torrent.tell(StatusRequest, client.ref)
      client.ignoreMsg {
        case Downloading => torrent.tell(StatusRequest, client.ref); true
      }
      client.expectMsg(Downloaded)

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloaded)
    }

    "download file using peers that download only one block" in {
      val (client, torrent) = newTest(singleBlockPeer)
      torrent.tell(StatusRequest, client.ref)
      client.ignoreMsg {
        case Downloading => torrent.tell(StatusRequest, client.ref); true
      }
      client.expectMsg(30.second, Downloaded)

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloaded)
    }

    "should not download if peers doesn't provide anything" in {
      val (client, torrent) = newTest(failingPeer)
      torrent.tell(StatusRequest, client.ref)
      client.receiveWhile(3.seconds) {
        case x @ Downloading => torrent.tell(StatusRequest, client.ref); x
      }

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloading)
    }

    "download with unreliable peers" in {
      val (client, torrent) = newTest(unreliablePeer)
      torrent.tell(StatusRequest, client.ref)
      client.ignoreMsg {
        case Downloading => torrent.tell(StatusRequest, client.ref); true
      }
      client.expectMsg(30.second, Downloaded)

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloaded)
    }

    //for development only
    // "do it for real" in {
    //   val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    //   val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination), "torrent") 
    //   expectNoMsg(70000.seconds)
    // }
  }

  class PerfectPeer extends Actor {
    def receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        val data = readContent(index, offset, length.toInt)
        sender() ! peer.Peer.BlockDownloaded(index, offset, data)
        sender() ! peer.Peer.PieceDownloaded(index)
    }
  }

  class WrongDataPeer extends Actor {
    def receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        //if (index == 0) sender() ! peer.Peer.BlockDownloaded(index, offset, Seq.fill(length.toInt)(13))
        //else sender() ! peer.Peer.BlockDownloaded(index, offset, readContent(index, offset, length.toInt))
        sender() ! peer.Peer.BlockDownloaded(index, offset, Seq.fill(content(index).size)(13.toByte))
        sender() ! peer.Peer.PieceDownloaded(index)
    }
  }

  class BlockDownloaderPeer extends Actor {
    def receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        val blockSize = Math.ceil(length / 10).toInt
        val nextBlockOffset = offset + blockSize
        sender() ! peer.Peer.BlockDownloaded(index, offset, readContent(index, offset, blockSize))
        if (nextBlockOffset >= length) {
          sender() ! peer.Peer.PieceDownloaded(index)
        } else {
          sender() ! peer.Peer.DownloadingFailed("test failing")
        }
    }
  }

  class ByBlockDownloaderPeer extends Actor {
    def receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        val blockSize = Math.ceil(length.toDouble / 5).toInt
        val nextBlockOffset = offset + blockSize
        for (block <- 0 to Math.ceil(length / blockSize).toInt) {
          val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
          val currentOffset = offset + block * blockSize
          sender() ! peer.Peer.BlockDownloaded(index, currentOffset, readContent(index, offset, currentBlockSize))  
        }
        sender() ! peer.Peer.PieceDownloaded(index)
    }
  }

  class UnreliablePeer extends Actor {
    def shouldFail = scala.util.Random.nextDouble < 0.1
    
    require(!shouldFail)

    def receive: Receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        require(!shouldFail)
        val blockSize = Math.ceil(length.toDouble / 5).toInt
        val nextBlockOffset = offset + blockSize
        for (block <- 0 to Math.ceil(length / blockSize).toInt) {
          require(!shouldFail)
          val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
          val currentOffset = offset + block * blockSize
          sender() ! peer.Peer.BlockDownloaded(index, currentOffset, readContent(index, offset, currentBlockSize))  
        }
        sender() ! peer.Peer.PieceDownloaded(index)
    }
  }

  class FailingPeer extends Actor {
    def receive = {
      case peer.Peer.DownloadPiece(index, offset, length) =>
        sender() ! peer.Peer.DownloadingFailed("test failing")
    }
  }
}

