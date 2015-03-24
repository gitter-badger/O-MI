package agentSystem

import akka.actor.{ Actor, ActorRef, Props  }
import akka.io.{ IO, Tcp  }
import akka.util.ByteString
import akka.actor.ActorLogging
import java.net.InetSocketAddress
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Calendar
import java.lang.Exception
import scala.util.control._

import parsing.OdfParser
import database._

import parsing.Types._
import parsing.Types.Path._

/** AgentListener handles connections from agents.
  */

class AgentListener extends Actor with ActorLogging {
   
  import Tcp._
  //Orginally a hack for getting different names for actors.
  private var agentCounter : Int = 0 
  /** Get function for count of all ever connected agents.
    * Check that can't be modified via this.
    */
  def agentCount = agentCounter
  /** Partial function for handling received messages.
    */
  def receive = {
    case Bound(localAddress) =>
      // TODO: do something?
      // It seems that this branch was not executed?
   
    case CommandFailed(b: Bind) =>
      log.warning(s"Agent connection failed: $b")
      context stop self
   
    case Connected(remote, local) =>
      val connection = sender()
      log.info(s"Agent connected from $remote to $local")

      val handler = context.actorOf(
        Props(classOf[InputDataHandler], remote),
        "agent-handler-"+agentCounter
      )
      agentCounter += 1
      connection ! Register(handler)
    case obj: OdfObject => 
      InputPusher.handleObjects(Seq(obj)) 
  }

}

/** A handler for data received from a agent.
  * @param Agent's adress 
  */

class InputDataHandler(
    sourceAddress: InetSocketAddress
  ) extends Actor with ActorLogging {

  import Tcp._

  // timestamp format to use when data doesn't have its own
  val dateFormat = new SimpleDateFormat ("yyyy-MM-dd'T'HH:mm:ss")
  /** Partial function for handling received messages.
    */
  def receive = {
    case Received(data) => 
      val dataString = data.decodeString("UTF-8")

      log.debug(s"Got data from $sender") // \n" + dataString)

      val parsedEntries = OdfParser.parse(dataString)
      val errors = parsedEntries.filter( _.isLeft ).map( e => e.left.get) 
      val corrects = parsedEntries.filter( _.isRight ).map( c => c.right.get) 

      for (error <- errors) {
        log.warning(s"Malformed odf received from agent ${sender()}: ${error.msg}")
      }

     InputPusher.handleObjects(corrects)
    case PeerClosed =>
      log.info(s"Agent disconnected from $sourceAddress")
      context stop self
  }

}

trait IInputPusher {
  def handleObjects( objs: Seq[OdfObject] ) : Unit
  def handleInfoItems( infoitems: Seq[OdfInfoItem]) : Unit  
  def handlePathValuePairs( pairs: Seq[(String,String)] ): Unit
}
object InputPusher extends IInputPusher{
  
  override def handleObjects( objs: Seq[OdfObject] ) : Unit = {
    for(obj <- objs){
      if(obj.childs.nonEmpty)
        handleObjects(obj.childs)
      if(obj.sensors.nonEmpty)
        handleInfoItems(obj.sensors)
    }
  }
  override def handleInfoItems( infoitems: Seq[OdfInfoItem]) : Unit = {
    for( info <- infoitems ){
      for(timedValue <- info.timedValues){
          val sensorData = timedValue.time match {
              case None =>
                val currentTime = new java.sql.Timestamp(new Date().getTime())
                new DBSensor(info.path, timedValue.value, currentTime)
              case Some(timestamp) =>
                new DBSensor(info.path, timedValue.value,  timestamp)
            }
  //          println(s"Saving to path ${info.path}")

            SQLite.set(sensorData)
      }  
    }
  } 
  override def handlePathValuePairs( pairs: Seq[(String,String)] ) : Unit ={
    SQLite.setMany(pairs.toList.map((a => (a._1,TimedValue(None,a._2)))))
  }

}