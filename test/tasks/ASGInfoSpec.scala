package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.{AWSRestartableActor, AWSSupervisorStrategy}
import actors.workflow.tasks.ASGInfo
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, AmazonCloudFormationService, AmazonAutoScalingService, TestConfiguration}

import scala.concurrent.duration._

class ASGInfoSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike with Matchers
                          with MockitoSugar {

  val mockedClient            = mock[AmazonAutoScaling]
  val describeASGRequest      = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("test-asg-name")
  val describeASGReqFail      = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("expect-fail")
  val describeASGReqClientExc = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("client-exception")
  val instance                = new Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id")
  val asg                     = new AutoScalingGroup().withLoadBalancerNames("test-elb-name").withInstances(instance)
  val describeASGResult       = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)

  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGRequest)).thenReturn(describeASGResult)
  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReqFail)).thenThrow(new AmazonServiceException("failed"))
  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReqClientExc)).thenThrow(new AmazonClientException("connection problems")).thenReturn(describeASGResult)

  val asgInfoProps = Props(new ASGInfo(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply(clazz: AnyRef, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      //Match on actor classes you care about, pass the rest onto the "prod" factory.
      clazz match {
        case ASGInfo => context.actorOf(asgInfoProps, "asgInfo")
        case _ => ActorFactory(clazz, context, name, args)
      }
    }
  }

  "An ASGInfo fetcher" should "return a valid response if AWS is up" in {
    //Fabricate a parent so we can test messages coming back to the parent.
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = TestActorFactory(ASGInfo, context, "asgInfo", null)
      //val child = context.actorOf(asgInfoProps, "asgInfo")

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, ASGInServiceInstancesAndELBSQuery("test-asg-name"))
    proxy.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }

  it should "throw an exception if AWS is down" in {
    val proxy = TestProbe()
    val grandparent = system.actorOf(Props(new Actor {
      val parent = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = TestActorFactory(ASGInfo, context, "asgInfo", null)
        def receive = {
          case x => child forward x
        }
      }))

      def receive = {
        case x: LogMessage => proxy.ref forward x
        case x => parent forward x
      }
    }))

    proxy.send(grandparent, ASGInServiceInstancesAndELBSQuery("expect-fail"))
    val msg = proxy.expectMsgClass(classOf[LogMessage])
    msg.message should include ("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    //Fabricate a parent so we can test messages coming back to the parent.
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = TestActorFactory(ASGInfo, context, "asgInfo", null)
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ASGInServiceInstancesAndELBSQuery("client-exception"))
    proxy.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }
}