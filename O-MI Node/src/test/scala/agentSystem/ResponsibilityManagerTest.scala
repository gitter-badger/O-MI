
package agentSystem

import java.util.{Date}
import java.sql.Timestamp

import scala.collection.mutable.Map
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.Future
import akka.actor.{Actor, ActorSystem, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.testkit._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.create.InterpolatedFragment
import org.specs2.mutable._
import com.typesafe.config.{ConfigFactory, Config}
import types.OdfTypes._
import types.Path
import types.OmiTypes.WriteRequest
import database.DB
import agentSystem._
import http.CLICmds._
import testHelpers.Actorstest


class ResponsibilityManagerTest(implicit ec: ExecutionEnv )extends Specification{

  def AS =ActorSystem(
    "startstop",
    ConfigFactory.load(
      ConfigFactory.parseString(
        """
        akka.loggers = ["akka.testkit.TestEventListener"]
        """).withFallback(ConfigFactory.load()))
    )
  //def AS = ActorSystem()
  def emptyConfig = ConfigFactory.empty()
  trait TestManager extends BaseAgentSystem with ResponsibleAgentManager{
    def receive : Actor.Receive = {
      case ListAgentsCmd() => sender() ? agents.values.toSeq
      case ResponsibilityRequest( senderName: String, write: WriteRequest) => handleWrite(senderName,write)
    }
  }
  class TestSuccessManager(paths: Vector[Path ],  testAgents: scala.collection.mutable.Map[AgentName, AgentInfo], val settings:  AgentSystemConfigExtension )  extends TestManager{
    import context.dispatcher
    protected[this] val agents: scala.collection.mutable.Map[AgentName, AgentInfo] = testAgents
    override protected def writeValues(
      infoItems: Iterable[OdfInfoItem],
      objectMetadatas: Vector[OdfObject] = Vector()
    )(implicit system: ActorSystem): Future[SuccessfulWrite] = {
      val ps = infoItems.map(_.path).toVector ++ objectMetadatas.map(_.path).toVector
      if( ps == paths )
        Future.successful( SuccessfulWrite( paths) )
      else 
        Future.successful( SuccessfulWrite( Vector()) )
    }
  }
  class TestFailureManager( testAgents: scala.collection.mutable.Map[AgentName, AgentInfo], val settings:  AgentSystemConfigExtension )  extends TestManager{
    import context.dispatcher
    protected[this] val agents: scala.collection.mutable.Map[AgentName, AgentInfo] = testAgents
    override protected def writeValues(
      infoItems: Iterable[OdfInfoItem],
      objectMetadatas: Vector[OdfObject] = Vector()
    )(implicit system: ActorSystem): Future[SuccessfulWrite] = Future.failed( new Exception( "Test failure" ))
  }

  "ResponsibilityManager should " >> {
    "write not owned values " >> notOwnedWriteTest
    "send owned path to agents to handle " >> ownedWriteTest
    //"write owned values when received from owner " >> test
    "return error when a write fails " >> notOwnedWriteFailTest.pendingUntilFixed
    //"return error when agent denies write " >> test
    "return error when agent's write fails" >> ownedWriteFailTest.pendingUntilFixed
  }

  val timeoutDuration = 10.seconds
  implicit val timeout = Timeout( timeoutDuration )
  def timestamp = new Timestamp( new Date().getTime())

  def ownedWriteTest = new Actorstest(AS){
    import system.dispatcher
    val name = "WriteSuccess"
    val path = Path( "Objects/object1/sensor1" )
    val _paths = Vector(path)
    val clazz = "agentSystem.WSAgent"
    val testAgents : Map[AgentName, AgentInfo ]= Map.empty
   val configStr =
   s"""
   agent-system{
     starting-timeout = 2 seconds
     internal-agents {
      "$name" ={
        language = "scala"
        class = "$clazz"
        config = {}
      }
    }
   }
   """
   val config = ConfigFactory.parseString(configStr)
   val asce =new AgentSystemSettings(config)

    val managerRef = TestActorRef( new TestSuccessManager(_paths,testAgents,asce){
      
      val ref = context.actorOf( Props( new WSAgent), name)
      val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, running = true, _paths)
      agents +=  name -> agentInfo
    }) 
    val managerActor = managerRef.underlyingActor
    val ttl = timeoutDuration
    val write = WriteRequest(
      createAncestors( OdfInfoItem(
          path,
          OdfTreeCollection(
            OdfValue(
              "731757",
              "xs:int",
              timestamp
            )
        )
      )),
      None,
      ttl
    )
    
    val successF : Future[ResponsibleAgentResponse]= (managerRef ? ResponsibilityRequest(name, write)
      ).mapTo[ResponsibleAgentResponse]
    successF must beEqualTo(SuccessfulWrite(_paths)).await(0, timeoutDuration) 
  }

  def ownedWriteFailTest = new Actorstest(AS){
    import system.dispatcher
    val name = "WriteSuccess"
    val paths = Vector(Path( "Objects/object1/sensor1" ))
    val ref = system.actorOf( Props( new WSAgent), name)
    val clazz = "agentSystem.WSAgent"
    val agentInfo = AgentInfo( name, clazz, emptyConfig, ref, running = true, paths)
    val testAgents = Map( name -> agentInfo)
   val configStr =
   s"""
   agent-system{
     starting-timeout = 2 seconds
     internal-agents {
      "$name" ={
        language = "scala"
        class = "$clazz"
        config = {}
      }
    }
   }
   """
   val config = ConfigFactory.parseString(configStr)
   val asce =new AgentSystemSettings(config)
    val managerRef = TestActorRef( new TestFailureManager(testAgents, asce)) 
    val managerActor = managerRef.underlyingActor
    val ttl = timeoutDuration
    val write = WriteRequest(
      createAncestors( OdfInfoItem(
          Path("Objects/object1/sensor1"),
          OdfTreeCollection(
            OdfValue(
              "731757",
              "xs:int",
              timestamp
            )
        )
      )),
      ttl = ttl
    )
    
    val successF : Future[ResponsibleAgentResponse] =( managerRef ? ResponsibilityRequest(name, write)
    ).mapTo[ResponsibleAgentResponse]
    val equal =MixedWrite(Vector(),FailedWrite(Vector(Path("Objects/object1/sensor1"), Path("Objects/object1/sensor1")),Vector( new Exception("Test failure"),  new Exception("Test failure"))))
    successF must beEqualTo(equal).await(0, timeoutDuration) 

  }

  def notOwnedWriteTest = new Actorstest(AS){
    import system.dispatcher
    val name = "WriteSuccess"
    val path = Path( "Objects/object1/sensor1" )
    val paths = Vector(path)
    val testAgents : Map[AgentName, AgentInfo ]= Map.empty
   val configStr =
   s"""
   agent-system{
     starting-timeout = 2 seconds
     internal-agents {
     }
   }
   """
   val config = ConfigFactory.parseString(configStr)
   val asce =new AgentSystemSettings(config)
    val managerRef = TestActorRef( new TestSuccessManager(paths,testAgents, asce)) 
    val managerActor = managerRef.underlyingActor
    val ttl = timeoutDuration
    val write = WriteRequest(
      createAncestors( OdfInfoItem(
        path,
          OdfTreeCollection(
            OdfValue(
              "731757",
              "xs:int",
              timestamp
            )
        )
      )),
      None,
      ttl
    )
    
    val successF : Future[ResponsibleAgentResponse]= (managerRef ? ResponsibilityRequest(name, write)
      ).mapTo[ResponsibleAgentResponse]
    successF must beEqualTo(SuccessfulWrite(paths)).await(0, timeoutDuration)
  }

  def notOwnedWriteFailTest = new Actorstest(AS){
    import system.dispatcher
    val name = "WriteSuccess"
    val paths = Vector(Path( "Objects/object1/sensor1" ))
    val testAgents : Map[AgentName, AgentInfo ]= Map.empty
   val configStr =
   s"""
   agent-system{
     starting-timeout = 2 seconds
     internal-agents {
    }
   }
   """
   val config = ConfigFactory.parseString(configStr)
   val asce =new AgentSystemSettings(config)
    val managerRef = TestActorRef( new TestFailureManager(testAgents,asce)) 
    val managerActor = managerRef.underlyingActor
    val ttl = timeoutDuration
    val write = WriteRequest(
      createAncestors( OdfInfoItem(
          Path("Objects/object1/sensor1"),
          OdfTreeCollection(
            OdfValue(
              "731757",
              "xs:int",
              timestamp
            )
        )
      )),
      None,
      ttl
    )
    
    val equal =MixedWrite(Vector(),FailedWrite(Vector(Path("Objects/object1/sensor1")),Vector( new Exception("Test failure"))))
    val successF : Future[ResponsibleAgentResponse] = (managerRef ? ResponsibilityRequest(name, write)
      ).mapTo[ResponsibleAgentResponse]
    successF must beEqualTo(equal).await(0, timeoutDuration) 
  }
}

