package torentator



import org.scalatest._
import scala.concurrent.duration._

 
class PeerPoorSpec extends FlatSpec with Matchers {
  import akka.util.ByteString

  "Peer" should "create proper handshake" in {
    val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
    var msg = Peer.handshakeMessage("ABCDEFGHIJKLMNOPQRST".getBytes, manifest)

    assert (msg.length === 68)
  }

  it should "serialize and deserialize PeerMessages" in {
    import PeerMessage._
    Seq(
      (Seq(0, 0, 0, 0) -> KeepAlive),                 //<len=0000>
      (Seq(0, 0, 0, 1, 0) -> Choke),                  //<len=0001><id=0>
      (Seq(0, 0, 0, 1, 1) -> Unchoke),                //<len=0001><id=1>
      (Seq(0, 0, 0, 1, 2) -> Interested),             //<len=0001><id=2>
      (Seq(0, 0, 0, 1, 3) -> NotInterested),          //<len=0001><id=3>
      (Seq(0, 0, 0, 5, 4, 0, 0, 1, 42) -> Have(298)), //<len=0005><id=4><piece index>
      (Seq(0, 0, 0, 6, 5, 0, 0, 1, 1, 1) -> 
        Bitfield(Seq(0, 0, 1, 1, 1).map(_.toByte))),  //<len=0001+X><id=5><bitfield>
      (Seq(0, 0, 0, 13, 6, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3, 4) -> 
        Request(258, 259, 16909060)),                 //<len=0013><id=6><index><begin><length>
      (Seq(0, 0, 0, 12, 7, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3) -> 
        Piece(258, 259, Seq(1, 2, 3).map(_.toByte))), //<len=0009+X><id=7><index><begin><block>
      (Seq(0, 0, 0, 10, 7, 0, 0, 0, 1, 0, 0, 0, 0, 98) -> 
        Piece(1, 0, Seq(98).map(_.toByte))), //<len=0009+X><id=7><index><begin><block>
      (Seq(0, 0, 0, 13, 8, 0, 0, 1, 2, 1, 3, 0, 1, 1, 2, 3, 4) -> 
        Cancel(258, 16973825, 16909060)),             //<len=0013><id=8><index><begin><length>
      (Seq(0, 0, 0, 3, 9, 1, 42) -> Port(298))        //<len=0003><id=9><listen-port>
      ) foreach { case (bytes: Seq[Int], message: PeerMessage) =>
        val encoded = akka.util.ByteString(bytes.map(_.toByte).toArray)

        assert(unapply(encoded)  === Some(message), message)
        assert(PeerMessage.ByteString.unapply(message) == Some(encoded), s"Failed during '${message}' test")
    }
  }

  it should "deserialize to None if message is uncorrect" in {
    import PeerMessage._
    import util.{Try, Success}
    Seq(
      ("lenght is too small" -> Seq(0, 0, 0, 2, 0)),
      ("data is required but missing" -> Seq(0, 0, 0, 1)),
      ("lenght is too big" -> Seq(0, 0, 0, 1, 0, 0)), 
      ("to big data for Have" -> Seq(0, 0, 0, 6, 4, 0, 0, 1, 42, 0)),
      ("not enought data to parse Have" -> Seq(0, 0, 0, 4, 4, 0, 0, 1)),
      ("not enought data to parse Request" -> Seq(0, 0, 0, 12, 6, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3)),
      ("to big data for Request" -> Seq(0, 0, 0, 14, 6, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3, 1, 1)),
      ("not enought data to parse Piece" -> Seq(0, 0, 0, 8, 7, 0, 0, 1, 2, 0, 0, 1)),
      ("not enought data to parse Cancel" -> Seq(0, 0, 0, 12, 8, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3)),
      ("to big data for Cancel" -> Seq(0, 0, 0, 14, 8, 0, 0, 1, 2, 0, 0, 1, 3, 1, 2, 3, 1, 1)),
      ("to big data for Port" -> Seq(0, 0, 0, 4, 9, 0, 0, 1)),
      ("not enought data to parse Port" -> Seq(0, 0, 0, 2, 9, 0))
    ) foreach { case (msg, bytes) =>
      val encoded = akka.util.ByteString(bytes.map(_.toByte).toArray)  
      val parsed = Try{unapply(encoded)} 

      assert(parsed === util.Success(None), s"Failed during '${msg}' test")
    }
  }
}

import akka.actor._
import akka.testkit._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import akka.actor.SupervisorStrategy._

class PeerActorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  import akka.testkit.TestProbe
  import scala.concurrent.duration._
  import akka.util.{ByteString => BString}

  implicit val timeout = Timeout(1 second)
  import system.dispatcher
 
  def this() = this(ActorSystem("PeerSpec"))
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val exceptionListener = TestProbe()
  val messagesListener = TestProbe()
  val superviser = system.actorOf(Props(new Superviser(exceptionListener.ref, messagesListener.ref)))

  // val giveDownloadTask: PartialFunction[util.Try[ActorRef], Unit] = {
  //   case util.Success(p) => p ! Peer.DownloadPiece(0, 100500)
  // }
  val giveDownloadTask: PartialFunction[ActorRef, Unit] = { case r =>
    r.tell(Peer.DownloadPiece(0, 0, 100500), superviser)
  }

  def messageAsBytes(msg: PeerMessage.PeerMessage) = {
    PeerMessage.ByteString.unapply(msg).get
  }

  def byteArray(size: Int) = List.fill(size)(7).map(_.toByte)

  def throwIfAnyReceived: PartialFunction[Any, Unit] = { case x => throw new RuntimeException(x.toString)  }

  def newPeer(connection: Props) = {
    val peer = superviser ? Peer.props(trackerId, manifest, connection)
    peer onFailure {
      case f => fail(f)
    }
    peer.mapTo[ActorRef]
  } 

  "Peer" must {
    "sends hanshake just after creation" in {
      var expectedHandshake = Peer.handshakeMessage(trackerId.getBytes, manifest)
      val connection = TestProbe()
      val peer = system.actorOf(Peer.props(trackerId, manifest, Props(new Forwarder(connection.ref))))

      connection.expectMsg(expectedHandshake)  
    }

    "escalates network exceptions" in {
      object NetworkException extends RuntimeException
      val connectionMock = Props(new Actor {
        def receive = { case _ => throw NetworkException }
      })

      newPeer(connectionMock)

      exceptionListener.expectMsg(NetworkException)
    }

    "should not send any messages if was not unchoked" in {
      val connectionMock = Props(new Actor {
        def receive = { case hs: BString =>
            sender() ! hs
            context become throwIfAnyReceived
        }
      })
      
      newPeer(connectionMock) onSuccess giveDownloadTask

      exceptionListener.expectNoMsg()
    }

    "should not send any messages if didn't receive task" in {
      val connectionMock = Props(new Actor {
        def receive = { case hs: BString =>
            sender() ! hs
            sender() ! messageAsBytes(PeerMessage.Unchoke)
            context become throwIfAnyReceived
        }
      })

      newPeer(connectionMock)

      exceptionListener.expectNoMsg()
    }

    "should ask for block if unchoke is given and task is received" in {
      var requestReceived = false
      val connectionMock = Props(new Actor {
        def receive = { case hs: BString =>
            sender() ! hs
            sender() ! messageAsBytes(PeerMessage.Unchoke)
            context.setReceiveTimeout(1 second)
            context become {
              case PeerMessage(m) if (m match {
                case r: PeerMessage.Request => true
                case _ => false
              }) => requestReceived = true
              case ReceiveTimeout if requestReceived => 
              case ReceiveTimeout => throw new RuntimeException("request was not received")
              case x => throw new RuntimeException(x.toString)
            }
        }
      })

      newPeer(connectionMock) onSuccess giveDownloadTask

      exceptionListener.expectNoMsg()
    }

    "should downlod piece if connection is nice" in {
      val connectionMock = Props(new Actor {
        def receive = { case hs: BString =>
            sender() ! hs
            sender() ! messageAsBytes(PeerMessage.Unchoke)
            context become {
              case PeerMessage(m) => m match {
                case PeerMessage.Request(index, begin, length) =>
                  sender() ! messageAsBytes(new PeerMessage.Piece(index, begin, byteArray(length)))
                case _ => 
              }
            }
        }
      })

      newPeer(connectionMock) onSuccess giveDownloadTask

      exceptionListener.expectNoMsg()
      messagesListener.fishForMessage(1 second) {
        case Peer.PieceDownloaded(piece, data) =>
          assert (!data.isEmpty)
          false
        case _ => true
      }
    }

    "should be able to handle splitted pieces" in {
      val connectionMock = Props(new Actor {
        def receive = { case hs: BString =>
            sender() ! hs
            sender() ! messageAsBytes(PeerMessage.Unchoke)
            context become {
              case PeerMessage(m) => m match {
                case PeerMessage.Request(index, begin, length) =>
                  val bytes = messageAsBytes(new PeerMessage.Piece(index, begin, byteArray(length)))
                  val (msg1, msg2) = bytes splitAt 10
                  require(msg1.length + msg2.length == bytes.length)
                  sender() ! msg1 
                  sender() ! msg2
                case _ => 
              }
            }
        }
      })

      newPeer(connectionMock) onSuccess giveDownloadTask

      exceptionListener.expectNoMsg()
      messagesListener.fishForMessage(1 second) {
        case Peer.PieceDownloaded(piece, data) =>
          assert (!data.isEmpty)
          false
        case _ => true
      }
    }
  }

  class Superviser(exceptionsListener: ActorRef, messagesListener: ActorRef) extends Actor {
    override val supervisorStrategy = AllForOneStrategy(loggingEnabled = false) { case e =>
      exceptionsListener ! e
      Stop
    }
    def receive = {
      case (p: Props, name: String) => sender() ! context.actorOf(p, name)
      case p: Props => sender() ! context.actorOf(p)
      case m => messagesListener ! m
    }
  }
  class Forwarder(target: ActorRef) extends Actor {
    def receive = {
      case m => target forward m
    }
  }
}



