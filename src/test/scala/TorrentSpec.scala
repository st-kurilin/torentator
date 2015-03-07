package torentator


import org.scalatest._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}

class TorrentSpec extends ActorSpec("TorrentSpec") {
  lazy val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent")).get
  lazy val trackerId = "ABCDEFGHIJKLMNOPQRST"
  import Torrent._

  def perfectPeer: Torrent.PeerPropsCreator = (trackerId: String, infoHash: Seq[Byte], connection: Props)
    => Props(new PerfectPeer())


  "Torrent" must {
    "download files" in {
      val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
      val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination, perfectPeer), "torrent") 
      torrent ! StatusRequest
      fishForMessage(1.second) {
        case Downloading => torrent ! StatusRequest; true
        case Downloaded => false
      }
    }

    //for development only
    // "do it for real" in {
    //   val destination = java.nio.file.Files.createTempFile("torrentator", "temp")
    //   val torrent = system.actorOf(Props(classOf[Torrent], manifest, destination), "torrent") 
    //   expectNoMsg(70000.seconds)
    // }
  }  
}

class PerfectPeer extends Actor {
  def receive = {
    case peer.Peer.DownloadPiece(index, offset, length) =>
      sender() ! peer.Peer.BlockDownloaded(index, offset, List.fill(length.toInt)(7).map(_.toByte))
      sender() ! peer.Peer.PieceDownloaded(index)
  }
}
