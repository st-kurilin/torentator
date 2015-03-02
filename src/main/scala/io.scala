package torentator 

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, PoisonPill }
import scala.concurrent.duration._

object FileActor {
  case class Write(data: Seq[Byte], offset: Int)
  case object Written

  def props(path: java.nio.file.Path, expectedSize: Int = 16 * 1024) = 
    Props(classOf[FileActor], path, expectedSize)
}

class FileActor(path: java.nio.file.Path, expectedSize: Int) extends Actor {
  import FileActor._
  import context.dispatcher
  import java.nio.channels.AsynchronousFileChannel
  import java.nio.file.StandardOpenOption
  import java.nio.ByteBuffer

  val channel = AsynchronousFileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)

  val Tick = "tick"
  context.system.scheduler.schedule(0.second, 1.second, self, Tick)

  var waiting = List.empty[(ActorRef, java.util.concurrent.Future[Integer])]
  
  def receive = {
    case Write(data, offset) =>
      val initiator = sender()
      waiting = (initiator, channel.write(ByteBuffer.wrap(data.toArray), offset)) :: waiting

    case Tick =>
      waiting = waiting filter { case (actor, future) =>
        if (future.isDone) {
          actor ! Written
          false
        } else true
      }
  }
}