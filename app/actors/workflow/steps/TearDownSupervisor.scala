package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.TearDownSupervisor.{TearDownData, TearDownState}
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeleteRequested}
import actors.workflow.tasks.StackDeleteCompleteMonitor.StackDeleteCompleted
import actors.workflow.tasks.StackInfo.{StackIdQuery, StackIdResponse}
import actors.workflow.tasks.UnfreezeASG.{UnfreezeASGCommand, UnfreezeASGCompleted}
import actors.workflow.tasks.{DeleteStack, StackDeleteCompleteMonitor, StackInfo, UnfreezeASG}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentialsProvider
import utils.{ActorFactory, PropFactory}

class TearDownSupervisor(credentials: AWSCredentialsProvider,
                         actorFactory: ActorFactory) extends FSM[TearDownState, TearDownData] with ActorLogging
                                                             with AWSSupervisorStrategy {

  import actors.workflow.steps.TearDownSupervisor._

  startWith(AwaitingTearDownCommand, Uninitialized)

  when(AwaitingTearDownCommand) {
    case Event(TearDownCommand(oldStack, newASG), Uninitialized) =>
      val stackInfo = actorFactory(StackInfo, context, "stackInfo", credentials)
      context.watch(stackInfo)
      stackInfo ! StackIdQuery(oldStack)
      goto(AwaitingStackIdResponse) using InitialData(oldStack, newASG)
  }

  when(AwaitingStackIdResponse) {
    case Event(StackIdResponse(oldStackId), InitialData(oldStack, newASG)) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Deleting old stack: $oldStack")
      val deleteStack = actorFactory(DeleteStack, context, "stackDeleter", credentials)
      context.watch(deleteStack)
      deleteStack ! DeleteStackCommand(oldStack)
      goto(AwaitingStackDeletedResponse) using DeleteStackData(oldStack, oldStackId, newASG)
  }

  when(AwaitingStackDeletedResponse) {
    case Event(StackDeleteRequested, stackData: DeleteStackData) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old stack has been requested to be deleted. Monitoring delete progress")
      val stackDeleteMonitor = actorFactory(StackDeleteCompleteMonitor, context, "stackDeleteMonitor", credentials, stackData.oldStackId, stackData.oldStackName)
      context.watch(stackDeleteMonitor)
      goto(AwaitingStackDeleteCompleted)
  }

  when(AwaitingStackDeleteCompleted) {
    case Event(msg: StackDeleteCompleted, stackData: DeleteStackData) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old stack has reached DELETE_COMPLETE status. Resuming all scaling activities on new stack")
      val asgResume = actorFactory(UnfreezeASG, context, "asgResume", credentials)
      context.watch(asgResume)
      asgResume ! UnfreezeASGCommand(stackData.newStackASG)
      goto(AwaitingUnfreezeASGResponse)
  }

  when(AwaitingUnfreezeASGResponse) {
    case Event(msg: UnfreezeASGCompleted, stackData: DeleteStackData) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New ASG scaling activities have been resumed: ${msg.asgName}")
      context.parent ! TearDownFinished
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to tear down old stack and unfreeze new ASG")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object TearDownSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait TearDownMessage
  case class TearDownCommand(oldStackName: String, newStackASG: String) extends TearDownMessage
  case object TearDownFinished extends TearDownMessage

  //FSM: States
  sealed trait TearDownState
  case object AwaitingTearDownCommand extends TearDownState
  case object AwaitingStackIdResponse extends TearDownState
  case object AwaitingStackDeletedResponse extends TearDownState
  case object AwaitingStackDeleteCompleted extends TearDownState
  case object AwaitingUnfreezeASGResponse extends TearDownState

  //FSM: Data
  sealed trait TearDownData
  case object Uninitialized extends TearDownData
  case class InitialData(oldStackName: String, newStackASG: String) extends TearDownData
  case class DeleteStackData(oldStackName: String, oldStackId: String, newStackASG: String) extends TearDownData

  override def props(args: Any*): Props = Props(classOf[TearDownSupervisor], args: _*)
}
