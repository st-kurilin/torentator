package torentator

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem, AllForOneStrategy}
import akka.util.Timeout
import org.scalatest._
import akka.testkit.{ TestActors, TestKit, ImplicitSender }

abstract class ActorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this(name: String) = this(ActorSystem(name, com.typesafe.config.ConfigFactory.parseString("""akka.loglevel = DEBUG""")))

  def newSuperviser(messageListener: ActorRef, exceptionListener: ActorRef) =
    system.actorOf(Props(new Superviser(messageListener, exceptionListener)))

  def forwarderProps(target: ActorRef) = Props(new Forwarder(target))

  implicit val timeout = Timeout(1.second)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}

class Superviser(messagesListener: ActorRef, exceptionsListener: ActorRef) extends Actor {
  import akka.actor.SupervisorStrategy._
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