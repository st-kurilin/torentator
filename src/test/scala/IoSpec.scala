package torentator.io

import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
import java.nio.file.{Files, Path}

class FileActorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  import akka.testkit.TestProbe
  import scala.concurrent.duration._
  import Io._
 
  def this() = this(ActorSystem("FileActorSpec"))
 
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def tempFile = Files.createTempFile("torrentator", "temp")
  def exists(p: Path) = Files.exists(p)
  def fileIo(p: Path) = system.actorOf(fileConnectionProps(p))
  def read(p: Path) = Files.readAllBytes(p).toSeq
  def delete(p: Path) = Files.delete(p)
  def bytes(data: Int*) = data.map(_.toByte).toSeq

  val listener = TestProbe()

  "FileActor" must {
    "should not write to file if was not asked" in {
      val file = tempFile
      require(exists(file))

      assert(read(file) == bytes())
    }

    "be able to write to existing file" in {
      val file = tempFile
      require(exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Write(data, 0, 0), listener.ref)

      listener.expectMsg(WriteConfirmation(0))
      assert(read(file) == data)
    }

    "be able to create file" in {
      val file = tempFile
      delete(file)
      require(!exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Write(data, 0, 0), listener.ref)

      listener.expectMsg(WriteConfirmation(0))
      assert(read(file) == data)
    }

    "be able to write to file with offset" in {
      val file = tempFile
      require(exists(file))
      val data = bytes(1, 2, 3)
      def actor = fileIo(file)

      actor.tell(Write(data, 5, 1), listener.ref)

      listener.expectMsg(WriteConfirmation(1))
      assert(read(file) == bytes(0, 0, 0, 0, 0) ++ data)
    }

    "be able to write few times" in {
      val file = tempFile
      def actor = fileIo(file)

      actor.tell(Write(bytes(7, 8), 5, 1), listener.ref)
      actor.tell(Write(bytes(2, 3), 2, 2), listener.ref)

      require(listener.receiveN(2).toSet == Set(WriteConfirmation(1), WriteConfirmation(2)))

      assert(read(file) == bytes(0, 0, 2, 3, 0, 7, 8))
    }
  }
}