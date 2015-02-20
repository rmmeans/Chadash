package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest
import utils.PropFactory

import scala.collection.JavaConverters._

class FreezeASG(credentials: AWSCredentials) extends AWSRestartableActor {

  import actors.workflow.tasks.FreezeASG._

  override def receive: Receive = {
    case query: FreezeASGCommand =>

      val suspendProcessesRequest = new SuspendProcessesRequest()
        .withAutoScalingGroupName(query.asgName)
        .withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.suspendProcesses(suspendProcessesRequest)
      context.parent ! FreezeASGCompleted(query.asgName)
  }
}

object FreezeASG extends PropFactory {

  case class FreezeASGCommand(asgName: String)

  case class FreezeASGCompleted(asgName: String)

  override def props(args: Any*): Props = Props(classOf[FreezeASG], args: _*)
}

