package torentator.connection

import torentator.manifest.Manifest
import akka.actor.Props
import akka.actor.ActorRef
import java.net.InetSocketAddress

case object NewConnectionRequest
case class NewConnection(connection: Props)

object PeerConnectionCreator {
  def props(tracker: ActorRef, connectionOpener: ActorRef): Props =
    Props(classOf[PeerConnectionCreator], tracker, connectionOpener)
}


import akka.actor.Actor
import torentator.tracker._
import torentator.io._
import akka.pattern.ask
import scala.concurrent.duration._

class PeerConnectionCreator(tracker: ActorRef, connectionOpener: ActorRef) extends Actor {
  import context.dispatcher

  implicit val timeout = akka.util.Timeout(3.seconds)

  def receive = { case NewConnectionRequest =>
    val origSender = sender
    for {
      AnnounceReceived(Announce(_, peers)) <- tracker ? RequestAnnounce
      TcpConnection(props) <- connectionOpener ? TcpConnectionRequest(peers.head)
    } origSender ! NewConnection(props)
  }
}


