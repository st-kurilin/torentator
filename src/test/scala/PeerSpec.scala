package torentator

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.duration._
 
class PeerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
 
  def this() = this(ActorSystem("PeerSpec"))
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Peer should create proper handshake" must {
    val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
    var msg = Peer.handshakeMessage("ABCDEFGHIJKLMNOPQRST".getBytes, manifest)

    assert (msg.length === 68)
  }
}

