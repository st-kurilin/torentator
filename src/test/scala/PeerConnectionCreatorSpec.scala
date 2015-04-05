package torentator.connection

import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}
import java.net.InetSocketAddress
import torentator._


class PeerConnectionCreatorSpec extends ActorSpec("PeerConnectionCreatorSpec") {
  def connectionMock(address: InetSocketAddress) = new Actor {
    def receive = {
      case _ => sender ! address
    }
  }
  "PeerConnectionCreator" must {
    "provide addresses from tracker" in {
      val someAddress = new InetSocketAddress("11.12.13.14", 1516)
      val trackerMock = system.actorOf(Props(new Actor {
        import torentator.tracker._
        def receive = { case RequestAnnounce =>
          sender ! AnnounceReceived(Announce(10, Set(someAddress)))
        }
      }))
      val connectionOpennerMock = system.actorOf(Props(new Actor {
        import torentator.io._
        def receive = {case TcpConnectionRequest(`someAddress`) =>
          sender ! TcpConnection(Props(connectionMock(someAddress)))
        }
      }))

      val actor = system.actorOf(PeerConnectionCreator.props(trackerMock, connectionOpennerMock))
      actor ! NewConnectionRequest
      
      val NewConnection(connectionProps) = expectMsgAnyClassOf(classOf[NewConnection])
      val connection = system.actorOf(connectionProps)
      connection ! "getAddress"
      expectMsg(someAddress)
    }
  }
}
