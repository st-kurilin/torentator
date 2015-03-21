package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  import Torrent._
  import akka.testkit.TestProbe
  import scala.util.Random
  import scala.collection.mutable.ArrayBuffer
  import torentator.manifest.{Manifest, SingleFileManifest}

  val (manifest: Manifest, content: Seq[Seq[Byte]]) = {
    val pieceLength = Math.pow(2, 6).toInt
    val numberOfPieces = 5
    val totalLength = numberOfPieces * pieceLength.toInt - pieceLength.toInt / 2
    val hashSize = 20
    val content = Seq.tabulate(totalLength)(x => (x % 100 + 1).toByte).grouped(pieceLength).toSeq
    val m = SingleFileManifest(
      name = "test manifest",
      announce = new java.net.URI("fake://fake"),
      infoHash = Seq.fill(hashSize)(1.toByte),
      pieces = Seq.tabulate(numberOfPieces)(piece => encoding.Encoder.hash(content(piece))),
      pieceLength = pieceLength,
      length = totalLength)
    (m, content)
  }

  def readContent(piece: Int, offset: Int, length: Int) = content(piece).slice(offset, offset + length.toInt)

  case class PeerCreatorContext(failureListener: ActorRef)
  
  "Torrent" must {
    "download file using perfect peers" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          val data = readContent(index, offset, length.toInt)
          sender() ! peer.BlockDownloaded(index, offset, data)
        }
      })}
    }

    "not download file if hash sum check for piece failing" in {
      shouldNotDownloadUsing  {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          if (index == 0) sender() ! peer.BlockDownloaded(index, offset, Seq.fill(content(index).size)(13.toByte))
          else sender() ! peer.BlockDownloaded(index, offset, readContent(index, offset, length.toInt))
        }
      })}
    }

    "download file using by block downloader peer" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          val blockSize = Math.ceil(length.toDouble / 3).toInt
          val numberOfBlocks = Math.ceil(length.toDouble / blockSize).toInt
          
          for {
            block <- 0 to numberOfBlocks
            currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
            currentOffset = offset + block * blockSize
            if currentBlockSize > 0
          } {
            sender() ! peer.BlockDownloaded(index, currentOffset, readContent(index, currentOffset, currentBlockSize))
          }
        }
      })}
    }

    "download file using peers that download only one block" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          val blockSize = Math.ceil(length.toDouble / 3).toInt
          if (offset + blockSize < length) {
            sender() ! peer.BlockDownloaded(index, offset, readContent(index, offset, blockSize))
            sender() ! peer.DownloadingFailed(s"test failing on [${index}] ${offset} ~ ${readContent(index, offset, blockSize)}")
          } else {
            sender() ! peer.BlockDownloaded(index, offset, readContent(index, offset, length.toInt - offset))
          }
        }
      })}
    }

    "should not download if peers doesn't provide anything" in {
      shouldNotDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          sender() ! peer.DownloadingFailed("test failing")
        }
      })}
    }

    "download with unreliable peers" in {
      def shouldFail = scala.util.Random.nextDouble < 0.1
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        require(!shouldFail)
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          require(!shouldFail)
          val blockSize = Math.ceil(length.toDouble / 5).toInt
          val nextBlockOffset = offset + blockSize
          for (block <- 0 to Math.ceil(length / blockSize).toInt) {
            require(!shouldFail)
            val currentBlockSize = Math.min(blockSize, length - block * blockSize).toInt
            val currentOffset = offset + block * blockSize
            sender() ! peer.BlockDownloaded(index, currentOffset, readContent(index, currentOffset, currentBlockSize))  
          }
        }
      })}
    }

    "recover from failing piece hash check" in {
      var piecesProvided = Set.empty[Int]
      var mistaked = false
      shouldDownloadUsing {case PeerCreatorContext(failureListener) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          if (piecesProvided.contains(index)) failureListener ! s"Piece ${index} was requested twice"
          val data = readContent(index, offset, length.toInt)
          if (index != 1 || mistaked) {
            sender() ! peer.BlockDownloaded(index, offset, data)
            piecesProvided += index
          } else {
            val m1 = length.toInt / 2
            val m2 = length.toInt - m1
            sender() ! peer.BlockDownloaded(index, offset, Seq.fill(m1)(13))
            sender() ! peer.BlockDownloaded(index, m1, Seq.fill(m2)(13))
            mistaked = true
          }
        }
      })}
    }

    "download file using peers that download blocks in arbitary order" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          val numberOfBlocks = 3
          val downloadOrder = List(2, 0, 1)
          require(downloadOrder.size == numberOfBlocks)
          val blockMaxSize = Math.ceil(length.toDouble / numberOfBlocks).toInt
          for (block <- downloadOrder) {
            val blockOffset = offset + block * blockMaxSize
            val blockSize = Math.min(blockMaxSize, length.toInt - blockOffset)
            require(blockSize > 0)
            sender() ! peer.BlockDownloaded(index, blockOffset, readContent(index, blockOffset, blockSize))
          }
        }
      })}
    }

    "download file using peers that provides peers with overlapping blocks" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadPiece(index, offset, length) =>
          val numberOfMiniBlocks = 5 //Mini blocks can be composed into single blocks
          val miniBlocksDownloadOrder = List(0 to 1, 3 to 4, 1 to 3)
          val miniBlockMaxSize = Math.ceil(length.toDouble / numberOfMiniBlocks).toInt
          for {
            blockRange <- miniBlocksDownloadOrder
            minBlock = blockRange.min
            maxBlock = blockRange.max
            numberOfMiniBlocks = blockRange.size
          } {
            val blockOffset = offset + minBlock * miniBlockMaxSize
            val blockSize = Math.min(numberOfMiniBlocks * miniBlockMaxSize, length.toInt - blockOffset)
            println(s"Sending: blockOffset: ${blockOffset}, blockSize: ${blockSize}")
            sender() ! peer.BlockDownloaded(index, blockOffset, readContent(index, blockOffset, blockSize))
          }
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

  case class TestContext(client: TestProbe, torrent: ActorRef, failureListener: TestProbe, targetFileListener: TestProbe)
  case class FileContent(data: Seq[Byte])

  def newTest(actProvider: (PeerCreatorContext) => Props): TestContext = {
    val failureListener = TestProbe()
    val targetFileListener = TestProbe()

    val trackerMock = Props(new Actor {
      def receive = {
         case tracker.RequestAnnounce(manifest) =>
          def randInt = scala.util.Random.nextInt(250) + 1
          val randPeers = List.fill(10)(new java.net.InetSocketAddress(s"${randInt}.${randInt}.${randInt}.${randInt}", 10)).toSet
          sender() ! tracker.AnnounceReceived(tracker.Announce(0, randPeers))
      }
    })
    val fileMock = new io.FileConnectionCreator {
      import java.nio.file.Path
      def fileConnectionProps(path: Path, expectedSize: Int): Props = {
        Props(new Actor {
          val targetFile = new ArrayBuffer[Byte]()
          def receive = {
            case io.Send(block, offset, id) =>
              val preinsert = Math.max(offset + block.size - targetFile.size, 0)
              targetFile.insertAll(targetFile.size, Seq.fill(preinsert)(0.toByte))
              for (i <- 0 until block.size) targetFile.update(offset + i, block(i))
              targetFileListener.ref ! FileContent(targetFile.toArray)
              sender() ! io.Sended(id)
          }
        })
      }
    }

    def peerCreator = new peer.PeerPropsCreator {
      def props(trackerId: String, infoHash: Seq[Byte], connection: Props): Props = {
        actProvider(PeerCreatorContext(failureListener.ref))
      }
    }

    val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    val name = "torrent" + scala.util.Random.nextInt
    val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination, fileMock, peerCreator, trackerMock), name) 

    val client = TestProbe()
    TestContext(client, torrent, failureListener, targetFileListener)
  }

  def shouldDownloadUsing(actProvider: (PeerCreatorContext) => Props): Unit = {
    def lastAvailableMessage(tb: TestProbe, default: AnyRef = null): Any = if (tb.msgAvailable) {
      lastAvailableMessage(tb, tb.receiveOne(0.seconds))
    } else default

    val TestContext(client, torrent, failureListener, targetFileListener) = newTest(actProvider)
    
    torrent.tell(StatusRequest, client.ref)
    client.fishForMessage(10.seconds) {
      case Downloading => torrent.tell(StatusRequest, client.ref); false
      case Downloaded => true
    }

    torrent.tell(StatusRequest, client.ref)
    client.expectMsg(Downloaded)

    if (failureListener.msgAvailable) fail(s"Failure message received: ${failureListener.receiveOne(0.seconds)}")

    val expectedContent = content.flatten

    val actualContent = lastAvailableMessage(targetFileListener) match {
      case FileContent(c) => c
      case o => Seq.empty[Byte]
    }

    assert(expectedContent.sameElements(actualContent))
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
