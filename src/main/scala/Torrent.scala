package torentator 

import akka.actor.{ Actor, ActorRef, Props, OneForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._

object Torrent {
}

class Torrent(_manifest: Manifest, destination: java.io.File) extends Actor {
  import scala.concurrent.duration._
  import context.dispatcher
  val Tick = "tick"
  context.system.scheduler.schedule(1 seconds, 1 seconds, self, Tick)
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case Peer.CanNotDownload (reason) => 
      println(s"peer ${sender} will be replaced due ${reason}")
      val failedPeer = sender()
      if (tasksInProgress.contains(failedPeer)) {
        val task = tasksInProgress(failedPeer)
        tasks = task :: tasks
      }
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

  val numberOfPieces = 5//java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt

  println("pieceLenght: " + manifest.pieceLenght)
  println("piece actual number: " + java.lang.Math.floor(manifest.length / manifest.pieceLenght).toInt)

  var oldPeersAddresses = Set.empty[java.net.InetSocketAddress]
  def newPeers(number: Int = numberOfPieces) = {
      if (number != 0) println(s"Creating ${number} peers")
      val r = Tracker.announce(manifest).get.peers.
        filter(!oldPeersAddresses.contains(_)).take(number) map { address => 
        val addressEnscaped = address.toString.replaceAll("/", "")
        oldPeersAddresses = oldPeersAddresses + address
        context.actorOf(Peer.props(Tracker.id, manifest, Props(classOf[NetworkConnection], address)), s"peer:${addressEnscaped}")
    }
    require(r.size == number)  
    r
  }

  var tasks = List.empty[Peer.DownloadPiece]
  var tasksInProgress = Map.empty[ActorRef, Peer.DownloadPiece]

  for (piece <- 0 until numberOfPieces) tasks = Peer.DownloadPiece(piece, 0, manifest.pieceLenght) :: tasks

  def receive: Receive = {
    case Peer.PieceDownloaded(index, data) =>
      for (i <- 0 to 15) println(s"!!!!!Piece ${index} downloaded!!!!")
      if (tasks.size > 0) {
        println ("current tasks: " + tasks.mkString("\n"))
        println("----")  
      }
      
      //sender() ! PoisonPill
    case Peer.PiecePartiallyDownloaded(piece, downloaded, data) =>
      println(s"replacing actor for piece ${piece} after ${downloaded} downloaded")
      tasks = Peer.DownloadPiece(piece, downloaded.toInt, manifest.pieceLenght) :: tasks
      //sender() ! PoisonPill
    case Tick =>
      if (tasks.size != 0) println ("current tasks: " + tasks.mkString("\n") + "---\n")
      for ((task, peer) <- tasks zip newPeers(tasks.size)) {
        println(s"assign ${task} to ${peer}")
        tasksInProgress += (peer -> task)
        peer ! task
      }
      tasks = List.empty
    case x => println("Torrent received" + x) 
  }
}