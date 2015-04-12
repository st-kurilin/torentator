package torentator.torrent


import torentator._
import scala.concurrent.duration._
import scala.collection.immutable.BitSet
import scala.collection.mutable.ArrayBuffer
import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import torentator.manifest.{Manifest, SingleFileManifest}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  val NumnerOfPeers = 10
  val NumberOfPieces = 5
  val PieceLength = Math.pow(2, 6).toInt
  val TotalLength = NumberOfPieces * PieceLength.toInt - PieceLength.toInt / 2
  val (manifest: Manifest, content: Seq[Seq[Byte]]) = {
    val hashSize = 20
    val content = Seq.tabulate(TotalLength)(x => (x % 100 + 1).toByte).grouped(PieceLength).toSeq
    val m = SingleFileManifest(
      name = "test manifest",
      announce = new java.net.URI("fake://fake"),
      infoHash = Seq.fill(hashSize)(1.toByte),
      pieces = Seq.tabulate(NumberOfPieces)(piece => encoding.Encoder.hash(content(piece))),
      pieceLength = PieceLength,
      length = TotalLength)
    (m, content)
  }

  def readContent(piece: Int, offset: Int, length: Int) = content(piece).slice(offset, offset + length.toInt)

  case class PeerCreatorContext(failureListener: ActorRef)
  
  "Torrent" must {
    "download file using perfect peers" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          val data = readContent(index, offset, length.toInt)
          sender() ! peer.BlockDownloaded(index, offset, data)
        }
      })}
    }

    "not download file if hash sum check for piece failing" in {
      shouldNotDownloadUsing  {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          if (index == 0) sender() ! peer.BlockDownloaded(index, offset, Seq.fill(content(index).size)(13.toByte))
          else sender() ! peer.BlockDownloaded(index, offset, readContent(index, offset, length.toInt))
        }
      })}
    }

    "download file using peers that download only one block" in {
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = firstBlock
        def firstBlock: Receive = {
          case peer.DownloadBlock(index, offset, length) =>
            val size = Math.ceil(length.toDouble / 3).toInt
            val content = readContent(index, offset, Math.min(size, length))
            sender() ! peer.BlockDownloaded(index, offset, content)
        }

        def furtherBlock(actualBlockSize: Int): Receive = {
          case peer.DownloadBlock(index, offset, length) =>
            val content = readContent(index, offset, Math.min(actualBlockSize, length))
            sender() ! peer.BlockDownloaded(index, offset, content)
        }
      })}
    }

    "should not download if peers doesn't provide anything" in {
      shouldNotDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          sender() ! peer.DownloadingFailed("test failing")
        }
      })}
    }

    "download with unreliable peers" in {
      val callsCounterPerPiece = (for (i <- 0 until NumberOfPieces)
        yield (i -> new java.util.concurrent.atomic.AtomicInteger())).toMap
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          val i = callsCounterPerPiece(index).getAndIncrement
          if (i == 0) println("Going to fail for " + index)
          require(i != 0, "Test failure")
          val data = readContent(index, offset, length.toInt)
          sender() ! peer.BlockDownloaded(index, offset, data)
        }
      })}
    }

    "recover from failing piece hash check" in {
      var piecesProvided = Set.empty[Int]
      var mistaked = false
      shouldDownloadUsing {case PeerCreatorContext(failureListener) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          if (piecesProvided.contains(index)) failureListener ! s"Piece ${index} was requested twice"
          val data = readContent(index, offset, length.toInt)
          if (index != 1 || mistaked) {
            sender() ! peer.BlockDownloaded(index, offset, data)
            piecesProvided += index
          } else {
            sender() ! peer.BlockDownloaded(index, offset, Seq.fill(length.toInt)(13))
            mistaked = true
          }
        }
      })}
    }

    "download file using peers that download blocks in arbitary order" in {
      val numberOfBlocks = 3
      val downloadOrder = List(2, 0, 1)
      require(downloadOrder.size == numberOfBlocks)
      val blockMaxSize = Math.ceil(TotalLength.toDouble / numberOfBlocks).toInt
      val callsCounterPerPiece = (for (i <- 0 until NumberOfPieces)
        yield (i -> new java.util.concurrent.atomic.AtomicInteger())).toMap
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          val blockIndex = callsCounterPerPiece(index).getAndIncrement
          val block = downloadOrder(blockIndex)
          val blockOffset = offset + block * blockMaxSize
          val blockSize = Math.min(blockMaxSize, TotalLength.toInt - blockOffset)
          println(s"blockMaxSize: ${blockMaxSize}, length: ${length}, blockOffset: ${blockOffset}")
          println(s"offset: ${offset} block : ${block} blockMaxSize:  ${blockMaxSize}")
          require(blockSize > 0)
          println(s"Downloaded ${index}: ${blockOffset} + ${blockSize}")
          sender() ! peer.BlockDownloaded(index, blockOffset, readContent(index, blockOffset, blockSize))
        }
      })}
    }

    "download file using peers that provides peers with overlapping blocks" in {
      val numberOfMiniBlocks = 5 //Mini blocks can be composed into single blocks
      val miniBlocksDownloadOrder = List(0 to 1, 3 to 4, 1 to 3)
      val miniBlockMaxSize = Math.ceil(TotalLength.toDouble / numberOfMiniBlocks).toInt
      val callsCounterPerPiece = (for (i <- 0 until NumberOfPieces)
         yield (i -> new java.util.concurrent.atomic.AtomicInteger())).toMap
      shouldDownloadUsing {case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          val blockRange = miniBlocksDownloadOrder(callsCounterPerPiece(index).getAndIncrement)
          val minBlock = blockRange.min
          val maxBlock = blockRange.max
          val numberOfMiniBlocks = blockRange.size
          
          val blockOffset = offset + minBlock * miniBlockMaxSize
          val blockSize = Math.min(numberOfMiniBlocks * miniBlockMaxSize, length.toInt - blockOffset)
          println(s"Sending: blockOffset: ${blockOffset}, blockSize: ${blockSize}")
          sender() ! peer.BlockDownloaded(index, blockOffset, readContent(index, blockOffset, blockSize))
        }
      })}
    }

    "show progress in Downloading responce" in {
      val piecesToDownload = BitSet(2, 3)
      val TestContext(client, torrent, _, _) = newTest{ case PeerCreatorContext(l) => Props(new Actor {
        def receive = { case peer.DownloadBlock(index, offset, length) =>
          if (piecesToDownload(index)) {
            val data = readContent(index, offset, length.toInt - offset)
            sender() ! peer.BlockDownloaded(index, offset, data)
          }
      }})}

      torrent.tell(StatusRequest, client.ref)
      client.fishForMessage(10.seconds) {
        case Downloading(downloadedPieces) if piecesToDownload == downloadedPieces => true
        case d: Downloading => torrent.tell(StatusRequest, client.ref); false
        case Downloaded => false
      }
    }
  }

  case class TestContext(client: TestProbe, torrent: ActorRef, failureListener: TestProbe, targetFileListener: TestProbe)
  case class FileContent(data: Seq[Byte])

  def newTest(actProvider: (PeerCreatorContext) => Props): TestContext = {
    val failureListener = TestProbe()
    val targetFileListener = TestProbe()

    val trackerMock = Props(new Actor {
      def receive = {
         case tracker.RequestAnnounce =>
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

    def peerPropsFactory (a: java.net.InetSocketAddress): Props = {
      actProvider(PeerCreatorContext(failureListener.ref))
    }
    val f = peerPropsFactory(_)

    val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    val name = "torrent" + scala.util.Random.nextInt
    val peerPool = PeerPool.props(NumnerOfPeers, f, trackerMock)
    val torrent = system.actorOf(Torrent.props(manifest, destination, fileMock, peerPool), name) 

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
      case x: Downloading => torrent.tell(StatusRequest, client.ref); false
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
    client.expectMsgAnyClassOf(classOf[Downloading])

    assert(!failureListener.msgAvailable)
  }
}



// class TorrentIntegSpec extends ActorSpec("TorrentIntegSpec") {
//   import akka.testkit.TestProbe
//   import scala.util.Random
//   import scala.collection.mutable.ArrayBuffer
//   import torentator.manifest.{Manifest, SingleFileManifest}
//   import akka.pattern.ask
//   import scala.concurrent._
//   import scala.concurrent.ExecutionContext.Implicits.global

//   val manifest = torentator.manifest.Manifest.read(java.nio.file.Paths.get("./src/test/resources/sample.single.http.torrent")).get

//   "do it for real" in {
//     implicit val timeout = akka.util.Timeout(3.second)
//     val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
//     val torrent = system.actorOf(Torrent.props(manifest, destination), "torrent")

//     awaitCond({
//       val future = (torrent ? StatusRequest) map {
//         case Downloading(downloadedPieces) =>
//           //println ("Pieces downloaded: " + downloadedPieces)
//           false
//         case Downloaded =>
//           println("!!!Downloaded!!!")
//           true
//       }
//       Await.result(future, 3.second)
//     }, 70000.seconds, 5.seconds)
//   }
// }
