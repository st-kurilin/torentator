package torentator.io

import akka.actor.Props
import java.nio.file.Path
import java.net.InetSocketAddress

/** Module with mackable actors abstraction around IO concepts.*/

case class TcpConnectionRequest(remote: InetSocketAddress)
case class TcpConnection(props: Props)

case object Connected
case class ConnectionFailed(msg: String) extends RuntimeException

case class Send(data: Seq[Byte], offset: Int = 0, confirmId: Int = 0)
case class Sended(confirmId: Int)

case class Received(data: Seq[Byte])

case object Close

trait FileConnectionCreator {
  def fileConnectionProps(path: Path, expectedSize: Int = 16 * 1024): Props
}

object Io extends FileConnectionCreator {
  def tcpConnectionProps(remote: InetSocketAddress): Props =
    Props(classOf[NetworkConnection], remote)

  def fileConnectionProps(path: Path, expectedSize: Int): Props =
    Props(classOf[FileConnection], path, expectedSize)
}



/*Impl*/

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, PoisonPill }
import akka.util.ByteString
import scala.concurrent.duration._
import akka.actor.SupervisorStrategy._

class FileConnection(path: java.nio.file.Path, expectedSize: Int) extends Actor {
  import Io._
  import context.dispatcher
  import java.nio.channels.AsynchronousFileChannel
  import java.nio.file.StandardOpenOption
  import java.nio.ByteBuffer
  import java.util.concurrent.{Future => JFuture}

  val channel = AsynchronousFileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)

  val Tick = "tick"
  context.system.scheduler.schedule(0.second, 1.second, self, Tick)


  var waiting = List.empty[(JFuture[Integer], ActorRef, Int)]

  def receive: Receive = {
    case Send(data, offset, confirmId) =>
      waiting = (channel.write(ByteBuffer.wrap(data.toArray), offset), sender(), confirmId) :: waiting

    case Tick =>
      waiting = waiting filter { case (future, waiter, confirmId) =>
        if (future.isDone) {
          waiter ! Sended(confirmId)
          false
        } else {
          true
        }
      }
  }
}


class NetworkConnection(remote: java.net.InetSocketAddress) extends Actor {
  import akka.io.{ IO, Tcp }
  import context.system

  override val supervisorStrategy = AllForOneStrategy(loggingEnabled = false) {case e => Escalate}

  val listener = context.parent
  IO(Tcp) ! Tcp.Connect(remote)

  def receive: Receive = connecting(List.empty)

  def connecting(msgQueue: List[Any]) : Receive = {
    case Tcp.CommandFailed(_: Tcp.Connect) =>
      context become {case _ => }
      throw new ConnectionFailed("connect failed to " + remote)

    case c @ Tcp.Connected(remote, local) =>
      val connection = sender()
      connection ! Tcp.Register(self)
      context become connected(connection)
      for (m <- msgQueue) self ! m
    case x =>
      context become connecting(x :: msgQueue)
  }

  def connected(connection: ActorRef): Receive = {
    case Send(data, _, _) =>
      connection ! Tcp.Write(ByteString(data.toArray))
    case Tcp.CommandFailed(w: Send) =>
      throw new ConnectionFailed("write failed (O/S buffer was full?)")
    case Tcp.Received(data) =>
      listener ! Received(data)
    case Close =>
      connection ! Tcp.Close
    case _: Tcp.ConnectionClosed =>
      context stop self
  }
}
