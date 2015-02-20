package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3Object
import org.apache.commons.io.IOUtils
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import utils.PropFactory

class StackLoader(credentials: AWSCredentials, bucketName: String) extends AWSRestartableActor {

  import actors.workflow.tasks.StackLoader._

  override def receive: Receive = {
    case msg: LoadStack =>
      val s3Client = new AmazonS3Client(credentials)
      val stackObject: S3Object = s3Client.getObject(bucketName, s"chadash-stacks/${msg.stackPath}.json")
      val stackFileJson = Json.parse(IOUtils.toByteArray(stackObject.getObjectContent))

      context.parent ! StackLoaded(stackFileJson)
  }
}

object StackLoader extends PropFactory {

  case class LoadStack(stackPath: String)

  case class StackLoaded(stackJson: JsValue)

  override def props(args: Any*): Props = Props(classOf[StackLoader], args: _*)
}
