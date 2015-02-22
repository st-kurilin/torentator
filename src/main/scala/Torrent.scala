package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._

object Torrent {
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e => 
      println(s"peer ${sender} will be replaced due ${e.getMessage}")
      val failedPeer = sender()
      if (pieceByPeer.contains(failedPeer)) {
        val piece = pieceByPeer(failedPeer)
        handlePieceByPeer(piece, newPeers(100).head)
      }
      Stop
  }

  val manifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val announce = Tracker.announce(manifest).get

  val numberOfPieces = 3//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght" + manifest.pieceLenght)
  var oldPeersAddresses = Set.empty[java.net.InetSocketAddress]
  def newPeers(number: Int = numberOfPieces) = Tracker.announce(manifest).get.peers.
    filter(!oldPeersAddresses.contains(_)).take(numberOfPieces) map { address => 
      val addressEnscaped = address.toString.replaceAll("/", "")
      oldPeersAddresses = oldPeersAddresses + address
      context.actorOf(Peer.props(Tracker.id, manifest, Props(classOf[NetworkConnection], address)), s"peer:${addressEnscaped}")
  }

  var peers = newPeers()
  var pieceByPeer = Map.empty[ActorRef, Int]

  for ((i, p) <- (1 to numberOfPieces).zip(peers)) handlePieceByPeer(i, p)

  def handlePieceByPeer(piece: Int, peer: ActorRef) {
    pieceByPeer += (peer -> piece)
    peer ! Peer.DownloadPiece(piece, manifest.pieceLenght)
  }

  def receive: Receive = {
    case Peer.PieceDownloaded(index, data) =>
      println(s"!!!!!Piece ${index} downloaded!!!!")
      sender() ! PoisonPill
    case x => println("Torrent received" + x) 
  }
}