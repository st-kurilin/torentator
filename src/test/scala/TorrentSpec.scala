package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"
  import Torrent._
  import akka.testkit.TestProbe

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

  def newTorrent(peerCreator: Torrent.PeerPropsCreator) = {
    val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    system.actorOf(Props(classOf[Torrent], manifest, destination, peerCreator), "torrent" + scala.util.Random.nextInt) 
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
      client.expectMsg(10.second, Downloaded)

      torrent.tell(StatusRequest, client.ref)
      client.expectMsg(Downloaded)
    }

    "should not download if peers doesn't provide anything" in {
      val (client, torrent) = newTest(failingPeer)
      torrent.tell(StatusRequest, client.ref)
      client.ignoreMsg {
        case Downloading => torrent.tell(StatusRequest, client.ref); true
      }
      client.ignoreNoMsg

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
}

class PerfectPeer extends Actor {
  def receive = {
    case peer.Peer.DownloadPiece(index, offset, length) =>
      sender() ! peer.Peer.BlockDownloaded(index, offset, List.fill(length.toInt)(7).map(_.toByte))
      sender() ! peer.Peer.PieceDownloaded(index)
  }
}

class BlockDownloaderPeer extends Actor {
  def receive = {
    case peer.Peer.DownloadPiece(index, offset, length) =>
      val blockSize = Math.ceil(length.toInt / 10).toInt
      val nextBlockOffset = offset + blockSize
      sender() ! peer.Peer.BlockDownloaded(index, offset, List.fill(blockSize)(7).map(_.toByte))
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
      val blockSize = Math.ceil(length.toInt / 10).toInt
      val nextBlockOffset = offset + blockSize
      for (block <- 0 to Math.ceil(length / blockSize).toInt) {
        val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
        val currentOffset = offset + block * blockSize
        sender() ! peer.Peer.BlockDownloaded(index, currentOffset, List.fill(currentBlockSize)(7).map(_.toByte))  
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
      val blockSize = Math.ceil(length.toInt / 10).toInt
      val nextBlockOffset = offset + blockSize
      for (block <- 0 to Math.ceil(length / blockSize).toInt) {
        require(!shouldFail)
        val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
        val currentOffset = offset + block * blockSize
        sender() ! peer.Peer.BlockDownloaded(index, currentOffset, List.fill(currentBlockSize)(7).map(_.toByte))  
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
