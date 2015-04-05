package torentator.torrent

import akka.actor.ActorRef

case class WorkerCreated(worker: ActorRef)
case class WorkToBeDone(task: Any)

import akka.actor.{Actor, Terminated}
import collection.immutable.Queue
import akka.util.Timeout
import akka.pattern.{ask, after}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import java.util.concurrent.TimeoutException


//inspired by http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2
class TaskDistributor extends Actor with akka.actor.ActorLogging {
  import context.dispatcher
  type Worker = ActorRef
  type WorkSender = ActorRef

  case class WorkPiece(task: Any, workSender: WorkSender)

  case class TaskDone(worker: Worker, result: Any)
  case class WorkerFailed(worker: Worker, reason: Throwable)

  val workTimeout = 1.minute

  val Tick = "tick"
  context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)

  var workers = Map.empty[Worker, Option[WorkPiece]]
  var raiting = Map.empty[Worker, Int].withDefaultValue(0)
  var workQueue = Queue.empty[WorkPiece]

  def availableWorkers: Iterable[Worker] = workers.filter {case (_, t) => t.isEmpty}.keys.toList.sortBy(-raiting(_))

  def askWithFailOnTimeout(worker: ActorRef, task: Any) = {
    val askFuture = worker.ask(task)(1.hour)
    val delayed = after(workTimeout, using = context.system.scheduler)(Future.failed(new TimeoutException("Task was executed to long")))
    Future firstCompletedOf Seq(askFuture, delayed)
  }


  def assignWorkersForWork: Unit = {
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
      assignWorkersForWork
    case WorkToBeDone(task) =>
      log.debug("Got new task {}", task)
      workQueue = workQueue enqueue WorkPiece(task, sender)
      assignWorkersForWork
    case TaskDone(worker, workResult) =>
      raiting += (worker -> (raiting(worker) + 1))
      val Some(WorkPiece(_, workSender)) = workers(worker)
      log.info("Work {} performed by worker {}.", workers(worker), worker)
      workSender ! workResult
      workers += (worker -> None)
      assignWorkersForWork
    case WorkerFailed(worker, reason) if workers contains worker =>
      log.debug("Worker {} failed with {}", worker, reason)
      workers(worker) match {
        case Some(workpiece) => 
          log.debug("Remove {} from worker list (task reassigned)", worker)
          workers -= worker
          workQueue = workQueue enqueue workpiece
          assignWorkersForWork
        case None =>
          log.debug("Remove {} from worker list (no task)", worker)
          workers -= worker
          assignWorkersForWork
      }
    case Terminated(worker) =>
      self ! WorkerFailed(worker, new RuntimeException("worker was terminated"))
    case Tick =>
      lazy val tasksInProgress = workers.values.filter(!_.isEmpty)
      log.info("Workers in pool {}; tasks in queue {}; tasks in progress {}",
        workers.size, workQueue.size, tasksInProgress.size)
      log.debug("Workers: {}", workers.keys.mkString("\n"))
      log.debug("tasks in queue: {}", workQueue)
      log.debug("tasks in progress: {}", tasksInProgress)
      log.debug("raiting: {}", raiting.mkString("\n"))
  }
  override def postStop = {
    log.error("Task distrubutor stopped") 
  }
}
