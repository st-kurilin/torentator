package torentator.io

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import org.scalatest._
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
import akka.testkit.TestProbe
import Io._


class FileConnectionSpec extends torentator.ActorSpec("FileConnectionSpec") {
  import java.nio.file.{Files, Path}
 
  def tempFile = Files.createTempFile("torrentator", "temp")
  def exists(p: Path) = Files.exists(p)
  def fileIo(p: Path) = system.actorOf(fileConnectionProps(p))
  def read(p: Path) = Files.readAllBytes(p).toSeq
  def delete(p: Path) = Files.delete(p)
  def bytes(data: Int*) = data.map(_.toByte).toSeq

  val listener = TestProbe()

  "FileConnection" must {
    "not write to file if was not asked" in {
      val file = tempFile
      require(exists(file))

      assert(read(file) == bytes())
    }

    "be able to write to existing file" in {
      val file = tempFile
      require(exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Send(data, 0, 0), listener.ref)

      listener.expectMsg(Sended(0))
      assert(read(file) == data)
    }

    "be able to create file" in {
      val file = tempFile
      delete(file)
      require(!exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Send(data, 0, 0), listener.ref)

      listener.expectMsg(Sended(0))
      assert(read(file) == data)
    }

    "be able to write to file with offset" in {
      val file = tempFile
      require(exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Send(data, 5, 1), listener.ref)

      listener.expectMsg(Sended(1))
      assert(read(file) == bytes(0, 0, 0, 0, 0) ++ data)
    }

    "be able to write few times" in {
      val file = tempFile
      def actor = fileIo(file)

      actor.tell(Send(bytes(7, 8), 5, 1), listener.ref)
      actor.tell(Send(bytes(2, 3), 2, 2), listener.ref)

      require(listener.receiveN(2).toSet == Set(Sended(1), Sended(2)))

      assert(read(file) == bytes(0, 0, 2, 3, 0, 7, 8))
    }
  }
}

class NetworkConnectionSpec extends torentator.ActorSpec("NetworkConnectionSpec") {
  import java.net.InetSocketAddress
  import akka.pattern.ask
  import akka.io.{ IO, Tcp }
  import scala.concurrent.Await
  import akka.testkit.TestProbe
 
  def bytes(data: Int*) = data.map(_.toByte).toSeq

  val networkConnectionParent = TestProbe()
  val bindListener = TestProbe()

  "NetworkConnection" must {
    "should work" in {
      val serverHandler = system.actorOf(Props(new BytesAdder()))
      val server = system.actorOf(Props(classOf[Server], bindListener.ref, serverHandler))
      val serverAddress = bindListener.expectMsgType[InetSocketAddress]
      val superviser = newSuperviser(networkConnectionParent.ref, testActor)
      val networkConnection = Await.result((superviser ? tcpConnectionProps(serverAddress)).mapTo[ActorRef], 1.second)

      networkConnection ! Send(bytes(1, 2, 3))

      val Received(data) = networkConnectionParent.expectMsgType[Received]
      assert(data.toSeq == bytes(1 + 2 + 3).toSeq) 
    }
  }

class BytesAdder extends Actor {
    import Tcp._
    def receive = {
      case Received(data) =>
        val sum = data.foldLeft(0) { case (acc, x) => acc + x }
        val msg = akka.util.ByteString(bytes(sum).toArray)
        sender() ! Write(msg)
      case PeerClosed     => context stop self
    }
  }
}

class Server(bindListener: ActorRef, messageHandler: ActorRef) extends Actor {
    import akka.io.{ IO, Tcp }
    import java.net.InetSocketAddress
    import Tcp._
    import context.system
    import context._
   
    IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 0))

    def receive = {
      case b @ Bound(localAddress) =>
        bindListener ! localAddress
      case CommandFailed(_: Bind) => context stop self
   
      case c @ Connected(remote, local) =>
        val connection = sender()
        connection ! Register(messageHandler)
    }
  }