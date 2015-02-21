package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy }
import akka.actor.SupervisorStrategy._

object Torrent {
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e => 
      println(s">>>>>>>>>>>>>>peer ${sender} will be replaced due ${e.getMessage}")
      val failedPeer = sender()
      val piece = pieceByPeer(failedPeer)
      handlePieceByPeer(piece, newPeers(1).head)
      Stop
  }
  val manifest = _manifest match {
    case m : SingleFileManifest => m
    case _ => throw new RuntimeException("Only single file torrents supported")
  }
    
  val announce = Tracker.announce(manifest).get

  val numberOfPieces = 1//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght" + manifest.pieceLenght)

  def newPeers(number: Int = numberOfPieces) = Tracker.announce(manifest).get.peers.take(numberOfPieces) map { address => 
    val addressEnscaped = address.toString.replaceAll("/", "")
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
    case x => println("Torrent received" + x) 
  }
}