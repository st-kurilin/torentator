package torentator


import akka.util.ByteString

import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
class TorrentSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  import akka.testkit.TestProbe
  import scala.concurrent.duration._
 
  def this() = this(ActorSystem("PeerSpec"))
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Torrent" must {
    // for development only
    // "do it for real" in {
    //   val torrent = system.actorOf(Props(classOf[Torrent], manifest, new java.io.File(""))) 
    //   TestProbe().expectNoMsg(60.seconds)
    // }
  }
  class Forwarder(target: ActorRef) extends Actor {
    def receive = {
      case m => target forward m
    }
  }
}