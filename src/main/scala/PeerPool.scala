package torentator.torrent

import java.net.InetSocketAddress
import java.net.{ InetSocketAddress => Address}
import torentator.manifest.Manifest

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, OneForOneStrategy, PoisonPill, Terminated}
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import torentator.manifest.Manifest
import scala.concurrent.Future
import torentator.tracker._
import torentator.peer.PeerPropsCreator
import torentator.io.Io
import scala.concurrent.duration._
import scala.util.{Success, Failure}


object PeerPool {
  type PeerInstasiator = Address => Props
}

class PeerPool(numbersOfPeers: Int, val peerFactory: PeerPool.PeerInstasiator, trackerProps: Props) extends Actor
  with PeerFactory with akka.actor.ActorLogging {

  import context.dispatcher
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case f if workers.contains(sender) =>
      log.debug("peer {} failed. ignoring: {}", sender, f)
      //workers -= sender
      //addNewPeerToPool
      //sender ! PoisonPill
      Stop
    case f =>
      log.error(f, s"""Failed ${sender}:/ ${workers.mkString("\n")} """)
      Escalate
  }

  lazy val taskDistributor = context.actorOf(Props(classOf[TaskDistributor]), "TaskDistributor")
  lazy val tracker = context.actorOf(trackerProps)
  
  var workers = Set.empty[ActorRef]
  for (i <- 0 until 5) addNewPeerToPool 

  def addNewPeerToPool = createPeer onComplete {
    case Success(worker) =>
      context.watch(worker)
      self ! WorkerCreated(worker)
    case Failure(f) =>
      log.error(f, "Pool failed on actor creation")
  }

  def receive = {
    case Terminated(worker) =>
      log.debug("peer {} Terminated. removing", sender)
      workers -= sender
      addNewPeerToPool
    case m @ WorkerCreated(worker) =>
      workers += worker
      taskDistributor ! m
    case m => taskDistributor forward WorkToBeDone(m)
  }

  override def postStop = {
    log.error("PeerPool stopped") 
  }
}

trait PeerFactory extends Actor with akka.actor.ActorLogging {
  import context.dispatcher
  implicit val timeout = akka.util.Timeout(3.seconds)

  def peerFactory: PeerPool.PeerInstasiator
  def tracker: ActorRef

  def createPeer: Future[ActorRef] = if (!available.isEmpty) createPeerFromAvailableAddress
    else newAddresses flatMap { case newAddresses: Set[InetSocketAddress] =>
      synchronized {
        available ++= newAddresses.filter( x => !used.contains(x) && !available.contains(x))
      }
      if (available.size == 0)
        createPeer
      else
        createPeerFromAvailableAddress
    }

  def createPeerFromAvailableAddress = {
    synchronized {
      val toUse = available.head
      require(!used.contains(toUse), s"${toUse} are already in ${used}")
      available = available.tail
      used += toUse
      Future {instantiatePeer(toUse)}  
    }
  }

  var used = Set.empty[Address]
  var available = List.empty[Address]

  def newAddresses: Future[Set[InetSocketAddress]] = {
    def announce: Future[AnnounceReceived] = (tracker ? RequestAnnounce).mapTo[AnnounceReceived]
    def retrieveNewPeers = (response: AnnounceReceived) => response.announce.peers.toSet
    announce.map(retrieveNewPeers).recoverWith {case _ => newAddresses}
  }

  def instantiatePeer(address: Address): ActorRef = {
    val addressEnscaped = address.toString.replaceAll("/", "")
    val props = peerFactory(address)
    log.debug("New peer instanciated: {}", addressEnscaped)
    context.actorOf(props, s"peer:${addressEnscaped}")
  }
}
