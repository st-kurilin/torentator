package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.Future

object Torrent {
  case object AskForPeer
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  import scala.concurrent.duration._
  import context.dispatcher

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e: Peer.CanNotDownload if peerOwners contains sender() =>
      peerOwners(sender()) forward e
      Stop
    case e: Throwable =>
      println("------------------------")
      e.printStackTrace
      println("------------------------")
      Escalate
  }

  val manifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val announce = Tracker.announce(manifest).get
  val numberOfPieces = 3//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght: " + manifest.pieceLenght)
  println("piece actual number: " + java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt)

  var oldPeersAddresses = Set.empty[java.net.InetSocketAddress]
  def newPeers(number: Int = numberOfPieces) = {
      if (number != 0) println(s"Creating ${number} peers")
      Tracker.announce(manifest).get.peers.
        filter(!oldPeersAddresses.contains(_)).take(number) map { address => 
        val addressEnscaped = address.toString.replaceAll("/", "")
        oldPeersAddresses = oldPeersAddresses + address
        context.actorOf(Peer.props(Tracker.id, manifest, Props(classOf[NetworkConnection], address)), s"peer:${addressEnscaped}")
    }
  }

  var peerOwners = Map.empty[ActorRef, ActorRef]

  for (piece <- 0 until numberOfPieces)
    context.actorOf(Props(classOf[PieceHandler], piece, manifest.pieceLenght))

  def receive: Receive = {
    case Torrent.AskForPeer =>
      val peer = newPeers(1).head
      val owner = sender()
      peerOwners += (peer -> owner)
      owner ! peer
    case x => println("Torrent received" + x) 
  }
}

class PieceHandler(piece: Int, totalSize: Long) extends Actor {
  implicit val timeout = akka.util.Timeout(3 second)
  import context.dispatcher

  val torrent = context.parent

  def newPeer: Future[ActorRef] = (torrent ? Torrent.AskForPeer).mapTo[ActorRef].recoverWith {
   case f => println(s"failed on peer creation ${f}"); newPeer
  }

  def download(peer: Future[ActorRef], start: Int) = newPeer onSuccess { case peer =>
    peer ! Peer.DownloadPiece(piece, start, totalSize)
  }

  var downloaded = 0

  download(newPeer, downloaded)

  def receive = {
    case Peer.PieceDownloaded(index, data) =>
      for (i <- 0 to 15) println(s"!!!!!Piece ${piece} downloaded!!!!")
    case Peer.PiecePartiallyDownloaded(piece, downloaded, data) =>
      require(this.downloaded <= downloaded)
      this.downloaded = downloaded.toInt
      download(newPeer, this.downloaded)
    case Peer.CanNotDownload (reason) => 
      println(s"peer ${sender} will be replaced due ${reason}")
      download(newPeer, downloaded)
      Stop
  }
}
