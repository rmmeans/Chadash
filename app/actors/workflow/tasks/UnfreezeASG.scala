package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.ResumeProcessesRequest
import utils.{AmazonAutoScalingService, PropFactory}

class UnfreezeASG(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonAutoScalingService {

  import actors.workflow.tasks.UnfreezeASG._

  override def receive: Receive = {
    case msg: UnfreezeASGCommand =>

      val resumeProcessesRequest = new ResumeProcessesRequest()
        .withAutoScalingGroupName(msg.asgName)

      val awsClient = autoScalingClient(credentials)
      awsClient.resumeProcesses(resumeProcessesRequest)
      context.parent ! UnfreezeASGCompleted(msg.asgName)
  }
}

object UnfreezeASG extends PropFactory{
  case class UnfreezeASGCommand(asgName: String)
  case class UnfreezeASGCompleted(asgName: String)

  override def props(args: Any*): Props = Props(classOf[UnfreezeASG], args: _*)
}
