package torentator.io

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, PoisonPill }
import akka.util.ByteString
import scala.concurrent.duration._
import akka.actor.SupervisorStrategy._

object Io {
  case object Connected
  case class ConnectionFailed(msg: String) extends RuntimeException

  case class Write(data: Seq[Byte], offset: Int = 0, confirmId: Int = 0)
  case class WriteConfirmation(confirmId: Int)

  case object Close

  def tcpConnectionProps(remote: java.net.InetSocketAddress) = 
    Props(classOf[NetworkConnection], remote)

  def fileConnectionProps(path: java.nio.file.Path, expectedSize: Int = 16 * 1024) = 
    Props(classOf[FileActor], path, expectedSize)
}

class FileActor(path: java.nio.file.Path, expectedSize: Int) extends Actor {
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
  
  def receive = {
    case Write(data, offset, confirmId) =>
      waiting = (channel.write(ByteBuffer.wrap(data.toArray), offset), sender(), confirmId) :: waiting

    case Tick =>
      waiting = waiting filter { case (future, waiter, confirmId) =>
        if (future.isDone) {
          waiter ! WriteConfirmation(confirmId)
          false
        } else true
      }
  }
}


class NetworkConnection(remote: java.net.InetSocketAddress) extends Actor {
  import akka.io.{ IO, Tcp }
  import Tcp._
  import context.system

  override val supervisorStrategy = AllForOneStrategy(loggingEnabled = false) {case e => Escalate}

  val listener = context.parent
  IO(Tcp) ! Connect(remote)
 
  def receive = connecting(List.empty)

  def connecting(msgQueue: List[Any]) : Receive = {
    case CommandFailed(_: Connect) =>
      context become {case _ => }
      throw new Io.ConnectionFailed("connect failed to " + remote)
 
    case c @ Connected(remote, local) =>
      val connection = sender()
      connection ! Register(self)
      context become connected(connection)
      for (m <- msgQueue) self ! m
    case x =>
      context become connecting(x :: msgQueue)
  }

  def connected(connection: ActorRef): Receive = {
    case Io.Write(data, _, _) => 
      connection ! Write(ByteString(data.toArray))
    case CommandFailed(w: Write) =>
      throw new Io.ConnectionFailed("write failed (O/S buffer was full?)")
    case Received(data) =>
      listener ! data
    case Io.Close =>
      connection ! Close
    case _: ConnectionClosed =>
      context stop self
  }
}