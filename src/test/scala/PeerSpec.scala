package torentator


import akka.util.ByteString

import org.scalatest._
import scala.concurrent.duration._

 
class PeerPoorSpec extends FlatSpec with Matchers {
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

import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
class PeerActorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  import akka.testkit.TestProbe
  import scala.concurrent.duration._
 
  def this() = this(ActorSystem("PeerSpec"))
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Peer" must {
    "sends hanshake just after creation" in {
      var expectedHandshake = Peer.handshakeMessage(trackerId.getBytes, manifest)
      val connection = TestProbe()
      val peer = system.actorOf(Peer.props(trackerId, manifest, Props(new Forwarder(connection.ref))))

      connection.expectMsg(expectedHandshake)  
    }
    // for development only
    // "do it for real" in {
    //   val connectionProps = Props(classOf[NetworkConnection], new java.net.InetSocketAddress("84.123.53.8", 51413))
    //   val peer = system.actorOf(Peer.props(trackerId, manifest, connectionProps), "peer") 
    //   TestProbe().expectNoMsg(15.seconds)
    // }
  }
  class Forwarder(target: ActorRef) extends Actor {
    def receive = {
      case m => target forward m
    }
  }
}



