package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"

  "Torrent" must {
    
    //for development only
    // "do it for real" in {
    //   val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    //   println("Destination: " + destination)
    //   val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination), "torrent") 
    //   expectNoMsg(70000.seconds)
    // }
  }
}