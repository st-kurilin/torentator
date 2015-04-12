package torentator.torrent

import java.net.InetSocketAddress
import akka.actor.Props

/**
 * Peer pool allows to work with several peers as with single peer.
 * Pool is responsible for peer creation and making sure that tasks would be done.
 */

object PeerPool {
  type PeerPropsFactory = InetSocketAddress => Props

  def props(numbersOfPeers: Int, peerPropsFactory: PeerPropsFactory, trackerProps: Props): Props =
    Props(classOf[impl.PeerPool], numbersOfPeers, peerPropsFactory, trackerProps)
}

/**
 * Task distributor assigns tasks to existed workers and reassigns tasks on worker failures.
 */
 
package impl.distributor {
  import akka.actor.ActorRef

  case class WorkerCreated(worker: ActorRef)
  case class WorkToBeDone(task: Any)

  object TaskDistributor {
    def props = Props(classOf[TaskDistributor])
  }

  import collection.immutable.Queue
  import java.util.concurrent.TimeoutException
  import akka.actor.{Actor, ActorRef, Terminated}
  import akka.pattern.{ask, after}
  import scala.concurrent.Future
  import scala.concurrent.duration._
  import scala.util.{Success, Failure}


  //inspired by http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2
  private class TaskDistributor private extends Actor with akka.actor.ActorLogging {
    import context.dispatcher
    type Worker = ActorRef
    type WorkSender = ActorRef

    case class WorkPiece(task: Any, workSender: WorkSender)

    case class TaskDone(worker: Worker, result: Any)
    case class WorkerFailed(worker: Worker, reason: Throwable)

    val workTimeout = 3

    val Tick = "tick"
    context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)

    var workers = Map.empty[Worker, Option[WorkPiece]]
    var raiting = Map.empty[Worker, Int].withDefaultValue(3)
    var workQueue = Queue.empty[WorkPiece]

    def availableWorkers: Iterable[Worker] = workers.filter {case (_, t) => t.isEmpty}.keys.toList.sortBy(-raiting(_))

    def askWithFailOnTimeout(worker: ActorRef, task: Any) = {
      val askFuture = worker.ask(task)(1.hour)
      val actualTimeout = workTimeout * raiting(worker)
      val delayed = after(actualTimeout.seconds, using = context.system.scheduler)(Future.failed(new TimeoutException("Task was executed to long")))
      Future firstCompletedOf Seq(askFuture, delayed)
    }


    def assignWorkersForWork(): Unit = {
      val currentlyAvailableWorkers = availableWorkers
      for ((workpiece, worker) <- workQueue zip currentlyAvailableWorkers) {
        val WorkPiece(task, workSender) = workpiece
        workers += (worker -> Some(workpiece))
        askWithFailOnTimeout(worker, task) onComplete {
          case Success(workResult) => self ! TaskDone(worker, workResult)
          case Failure(f) =>
            log.info("failed on task execution: {}", f)
            self ! WorkerFailed(worker, f)
        }
        log.debug("Assign {} to worker {}.", workpiece, worker)
      }
      workQueue = workQueue drop currentlyAvailableWorkers.size
    }

    def receive = {
      case WorkerCreated(worker) =>
        log.debug("Got new worker {}", worker)
        workers += (worker -> None)
        context.watch(worker)
        assignWorkersForWork()
      case WorkToBeDone(task) =>
        log.debug("Got new task {}", task)
        workQueue = workQueue enqueue WorkPiece(task, sender())
        assignWorkersForWork()
      case TaskDone(worker, workResult) =>
        raiting += (worker -> (raiting(worker) + 1))
        val Some(WorkPiece(_, workSender)) = workers(worker)
        log.debug("Work {} performed by worker {}.", workers(worker), worker)
        workSender ! workResult
        workers += (worker -> None)
        assignWorkersForWork()
      case WorkerFailed(worker, reason) if workers contains worker =>
        log.debug("Worker {} failed with {}", worker, reason)
        workers(worker) match {
          case Some(workpiece) => 
            log.debug("Remove {} from worker list (task reassigned)", worker)
            workers -= worker
            workQueue = workQueue enqueue workpiece
            assignWorkersForWork()
          case None =>
            log.warning("Remove {} from worker list (no task)", worker)
            workers -= worker
            assignWorkersForWork()
        }
      case Terminated(worker) =>
        self ! WorkerFailed(worker, new RuntimeException("worker was terminated"))
      case Tick =>
        lazy val tasksInProgress = workers.values.filter(_.nonEmpty)
        log.info("Workers in pool {}; tasks in queue {}; tasks in progress {}",
          workers.size, workQueue.size, tasksInProgress.size)
    }
    override def postStop() = {
      log.error("Task distributor stopped")
    }
  }
}

package impl {
  import torentator.tracker._
  import distributor._
  import akka.actor.{ Actor, ActorRef, OneForOneStrategy, Terminated}
  import akka.actor.SupervisorStrategy._
  import akka.pattern.ask
  import scala.concurrent.Future
  import scala.concurrent.duration._
  import scala.util.{Success, Failure}

  class PeerPool(numbersOfPeers: Int, val peerFactory: PeerPool.PeerPropsFactory, trackerProps: Props)
    extends Actor with PeerFactory with akka.actor.ActorLogging {

    import context.dispatcher

    lazy val taskDistributor = context.actorOf(TaskDistributor.props, "TaskDistributor")
    lazy val tracker = context.actorOf(trackerProps)

    var workers = Set.empty[ActorRef]

    override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case f if workers.contains(sender()) =>
        log.debug("peer {} failed. reason: {}. stopping", sender(), f)
        Stop
      case f =>
        log.error(f, s"""Failed ${sender()}:/ ${workers.mkString("\n")} """)
        Escalate
    }

    for (i <- 1 to numbersOfPeers) addNewPeerToPool()

    def addNewPeerToPool() = createPeer onComplete {
      case Success(worker) =>
        context.watch(worker)
        self ! WorkerCreated(worker)
      case Failure(f) =>
        log.error(f, "Pool failed on actor creation")
    }

    def receive = {
      case Terminated(worker) =>
        log.debug("peer {} Terminated. removing", sender())
        workers -= sender
        addNewPeerToPool()
      case m @ WorkerCreated(worker) =>
        workers += worker
        taskDistributor ! m
      case m => taskDistributor forward WorkToBeDone(m)
    }

    override def postStop() = {
      log.error("PeerPool stopped") 
    }
  }

  trait PeerFactory extends Actor with akka.actor.ActorLogging {
    import context.dispatcher
    implicit val timeout = akka.util.Timeout(3.seconds)

    def peerFactory: PeerPool.PeerPropsFactory
    def tracker: ActorRef

    def createPeer: Future[ActorRef] = if (available.nonEmpty) createPeerFromAvailableAddress
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
        require(!used.contains(toUse), s"$toUse are already in $used")
        available = available.tail
        used += toUse
        Future {instantiatePeer(toUse)}  
      }
    }

    var used = Set.empty[InetSocketAddress]
    var available = List.empty[InetSocketAddress]

    def newAddresses: Future[Set[InetSocketAddress]] = {
      def announce: Future[AnnounceReceived] = (tracker ? RequestAnnounce).mapTo[AnnounceReceived]
      def retrieveNewPeers = (response: AnnounceReceived) => response.announce.peers.toSet
      announce.map(retrieveNewPeers).recoverWith {case _ => newAddresses}
    }

    def instantiatePeer(address: InetSocketAddress): ActorRef = {
      val addressEscaped = address.toString.replaceAll("/", "")
      val props = peerFactory(address)
      log.debug("New peer instantiated: {}", addressEscaped)
      context.actorOf(props, s"peer:$addressEscaped")
    }
  }
}
