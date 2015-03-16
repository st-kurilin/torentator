package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  import Torrent._
  import akka.testkit.TestProbe
  import scala.util.Random
  import scala.collection.mutable.ArrayBuffer

  val (manifest: Manifest, content: Seq[Seq[Byte]]) = {
    val pieceLength = Math.pow(2, 6).toInt
    val numberOfPieces = 5
    val totalLength = numberOfPieces * pieceLength.toInt - pieceLength.toInt / 2
    val hashSize = 20
    val content = Seq.tabulate(totalLength)(x => (x % 100).toByte).grouped(pieceLength).toSeq
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

  case class PeerCreatorContext(failureListener: ActorRef)
  
  "Torrent" must {
    "download file using perfect peers" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          val data = readContent(index, offset, length.toInt)
          sender() ! peer.Peer.BlockDownloaded(index, offset, data)
          sender() ! peer.Peer.PieceDownloaded(index)
        }
      })}
    }

    "not download file if hash sum check for piece failing" in {
      shouldNotDownloadUsing  {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          if (index == 0) sender() ! peer.Peer.BlockDownloaded(index, offset, Seq.fill(content(index).size)(13.toByte))
          else sender() ! peer.Peer.BlockDownloaded(index, offset, readContent(index, offset, length.toInt))
          sender() ! peer.Peer.PieceDownloaded(index)
        }
      })}
    }

    "download file using by block downloader peer" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          val blockSize = Math.ceil(length.toDouble / 4).toInt
          val nextBlockOffset = offset + blockSize
          val numberOfBlocks = Math.ceil(length.toDouble / blockSize).toInt
          for (block <- 0 to numberOfBlocks) {
            val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
            val currentOffset = offset + block * blockSize
            sender() ! peer.Peer.BlockDownloaded(index, currentOffset, readContent(index, currentOffset, currentBlockSize))  
          }
          println(s"sending index: ${index};")
          sender() ! peer.Peer.PieceDownloaded(index)
        }
      })}
    }

    "download file using peers that download only one block" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          val blockSize = Math.ceil(length / 3).toInt
          val nextBlockOffset = offset + blockSize
          sender() ! peer.Peer.BlockDownloaded(index, offset, readContent(index, offset, blockSize))
          if (nextBlockOffset >= length) {
            sender() ! peer.Peer.PieceDownloaded(index)
          } else {
            sender() ! peer.Peer.DownloadingFailed("test failing")
          }
        }
      })}
    }

    "should not download if peers doesn't provide anything" in {
      shouldNotDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          sender() ! peer.Peer.DownloadingFailed("test failing")
        }
      })}
    }

    "download with unreliable peers" in {
      def shouldFail = scala.util.Random.nextDouble < 0.1
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        require(!shouldFail)
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          require(!shouldFail)
          val blockSize = Math.ceil(length.toDouble / 5).toInt
          val nextBlockOffset = offset + blockSize
          for (block <- 0 to Math.ceil(length / blockSize).toInt) {
            require(!shouldFail)
            val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
            val currentOffset = offset + block * blockSize
            sender() ! peer.Peer.BlockDownloaded(index, currentOffset, readContent(index, currentOffset, currentBlockSize))  
          }
          sender() ! peer.Peer.PieceDownloaded(index)
        }
      })}
    }

    "recover from failing piece hash check" in {
      var piecesProvided = Set.empty[Int]
      var mistaked = false
      shouldDownloadUsing {case PeerCreatorContext(failureListener) => Props(new Actor {
        def receive = { case peer.Peer.DownloadPiece(index, offset, length) =>
          if (piecesProvided.contains(index)) failureListener ! s"Piece ${index} was requested twice"
          val data = readContent(index, offset, length.toInt)
          if (index != 1 || mistaked) {
            sender() ! peer.Peer.BlockDownloaded(index, offset, data)
            piecesProvided += index
          } else {
            sender() ! peer.Peer.BlockDownloaded(index, offset, Seq.fill(length.toInt)(13))
            mistaked = true
          }
          sender() ! peer.Peer.PieceDownloaded(index)
        }
      })}
    }

    //for development only
    // "do it for real" in {
    //   val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    //   val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination), "torrent") 
    //   expectNoMsg(70000.seconds)
    // }
  }

  case class TestContext(client: TestProbe, torrent: ActorRef, failureListener: TestProbe, targetFile: ArrayBuffer[Byte])

  def newTest(actProvider: (PeerCreatorContext) => Props): TestContext = {
    val targetFile = new ArrayBuffer[Byte]()
    val failureListener = TestProbe()

    val trackerMock = Props(new Actor {
      def receive = {
         case tracker.RequestAnnounce(manifest) =>
          def randInt = scala.util.Random.nextInt(250) + 1
          val randPeers = List.fill(10)(new java.net.InetSocketAddress(s"${randInt}.${randInt}.${randInt}.${randInt}", 10)).toSet
          sender() ! tracker.AnnounceReceived(tracker.Tracker.Announce(0, randPeers))
      }
    })
    val fileMock = new io.FileConnectionCreator {
      import java.nio.file.Path
      def fileConnectionProps(path: Path, expectedSize: Int): Props = {
        Props(new Actor {
          def receive = { case io.Send(block, offset, _) =>
            val preinsert = Math.max(offset + block.size - targetFile.size, 0)
            targetFile.insertAll(targetFile.size, Seq.fill(preinsert)(0.toByte))
            for (i <- 0 until block.size) targetFile.update(offset + i, block(i))
          }
        })
      }
    }

    def peerCreator: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props) => {
      println("creating peerCreator")
      actProvider(PeerCreatorContext(failureListener.ref))
    }

    val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    val name = "torrent" + scala.util.Random.nextInt
    println("creating torrent " + name)
    val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination, fileMock, peerCreator, trackerMock), name) 

    val client = TestProbe()
    println("Creating client: " + client.ref)
    TestContext(client, torrent, failureListener, targetFile)
  }

  def shouldDownloadUsing(actProvider: (PeerCreatorContext) => Props): Unit = {
    val TestContext(client, torrent, failureListener, targetFile) = newTest(actProvider)
    
    torrent.tell(StatusRequest, client.ref)
    client.fishForMessage(10.seconds) {
      case Downloading => torrent.tell(StatusRequest, client.ref); false
      case Downloaded => true
    }

    torrent.tell(StatusRequest, client.ref)
    client.expectMsg(Downloaded)

    assert(!failureListener.msgAvailable)
    println(s"${targetFile.size} - ${content.flatten.size}")
    assert(content.flatten.size == targetFile.size)
    assert(content.flatten == targetFile.toSeq)
  }

  def shouldNotDownloadUsing(actProvider: (PeerCreatorContext) => Props): Unit = {
    val TestContext(client, torrent, failureListener, targetFile) = newTest(actProvider)
    torrent.tell(StatusRequest, client.ref)
    client.receiveWhile(10.seconds) {
      case x @ Downloading => torrent.tell(StatusRequest, client.ref); x
    }

    torrent.tell(StatusRequest, client.ref)
    client.expectMsg(Downloading)

    assert(!failureListener.msgAvailable)
  }
}
