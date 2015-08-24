/**
  Copyright (c) 2015 Aalto University.

  Licensed under the 4-clause BSD (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at top most directory of project.

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
**/
package agentSystem

import http._
import akka.actor.{ Props, Actor, ActorLogging, SupervisorStrategy, OneForOneStrategy, ActorInitializationException, ActorKilledException }
import akka.actor.SupervisorStrategy._
import akka.io.{ IO, Tcp }
import scala.collection.mutable.Map
import scala.collection.immutable
import scala.collection.JavaConverters._
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.io.File
import scala.concurrent._
import scala.util.{ Try, Success, Failure }

import java.util.Date
import java.sql.Timestamp

import ExecutionContext.Implicits.global

import InternalAgentCLICmds._
/**
 * Helper Object for creating AgentLoader.
 *
 */
object InternalAgentLoader {
  def props(): Props = Props(new InternalAgentLoader())
}

case class ThreadException(agent: InternalAgent, exception: Exception)
case class ThreadInitialisationException(agent: InternalAgent, exception: Exception)
/**
 * AgentLoader loads agents from jars in deploy directory.
 * Supervise agents and startups bootables.
 *
 */
class InternalAgentLoader extends Actor with ActorLogging {

  import Tcp._

  case class AgentInfo(name: String, configPath: String, agent: Option[InternalAgent], timestamp: Timestamp)
  //Container for bootables
  protected[this] val agents: scala.collection.mutable.Map[String, AgentInfo] = Map.empty
  //getter method to allow testing
  private[agentSystem] def getAgents = agents
  //Classloader for loading classes in jars.
  private[this] val classLoader = createClassLoader()
  Thread.currentThread.setContextClassLoader(classLoader)

  //Settings for getting list of Bootables and configs from application.conf
  private[this] val settings = Settings(context.system)
  InternalAgent.setLoader(self)
  InternalAgent.setLog(log)
  start()

  def handleAgentCmd(agent: String)(handle: AgentInfo => Unit): Unit = {
    val agentInfoO = agents.get(agent)
    agentInfoO match {
      case None =>
        log.warning("Command for not stored agent!: " + agent)
      case Some(agentInfo) =>
        handle(agentInfo)
    }
  }
  /**
   * Method for handling received messages.
   * Should handle:
   *   -- ConfigUpdate with updating running AgentActors.
   *   -- Terminated with trying to restart AgentActor.
   */
  def receive = {
    case StartCmd(agentname: String) => {
      handleAgentCmd(agentname) { agentInfo: AgentInfo =>
        
        agentInfo.agent.fold {
          log.warning(s"Starting: " + agentInfo.name)
          agents -= agentInfo.name
          //          Thread.sleep(3000)
          loadAndStart(agentInfo.name, agentInfo.configPath)
        } { n=>
          log.warning(s"Agent $agentname was already Running. 're-start' should be used to restart running Agents")
        }
        //        agentInfo.agent.collect{ 
        //          case agent : InternalAgent =>
        //            log.warning(s"Starting: " + agentInfo.name)
        //            agents -= agentInfo.name
        //            Thread.sleep(3000)
        //            loadAndStart(agentInfo.name, agentInfo.configPath)
        //        }
      }
    }

    case ReStartCmd(agentname: String) => {
      handleAgentCmd(agentname) { agentInfo: AgentInfo =>
        agentInfo.agent.collect {
          case agent: InternalAgent if agent.isAlive =>
            log.warning(s"Re-Starting: " + agentInfo.name)
            agents -= agentInfo.name
            agent.interrupt()
            agent.join()
            loadAndStart(agentInfo.name, agentInfo.configPath)
        }
      }
    }

    case StopCmd(agent: String) => {
      handleAgentCmd(agent) { agentInfo: AgentInfo =>
        agentInfo.agent.collect {
          case agent: InternalAgent if agent.isAlive =>
            log.warning(s"Stopping: " + agentInfo.name)
            agents -= agentInfo.name
            agent.interrupt();
            agent.join()
            agents += agentInfo.name -> AgentInfo(agentInfo.name, agentInfo.configPath, None, agentInfo.timestamp)
        }
      }
    }

    case ThreadException(sender: InternalAgent, exception: InterruptedException) =>
      log.info(s"$sender.name was succesfully terminated.")
    case ThreadException(sender: InternalAgent, exception: Exception) =>
      log.warning(s"InternalAgent caugth exception: $exception")
      var date = new Date()
      val agentInfoO = agents.find {
        case (_, AgentInfo(_, _, Some(agent), _))  => agent == sender
        case _ => false
      }
      agentInfoO match {
        case None =>
          log.warning("Exception from not stored agent!: " + sender)
        case Some(Tuple2(name, agentInfo)) =>
          agentInfo.agent.collect {
            case agent: InternalAgent if date.getTime - agentInfo.timestamp.getTime > settings.timeoutOnThreadException =>
              log.warning(s"Trying to relaunch: " + agentInfo.name)
              agents -= agentInfo.name
              loadAndStart(agentInfo.name, agentInfo.configPath)
          }
      }
    case ThreadInitialisationException(agent: InternalAgent, exception: Exception) =>
      log.warning(s"InternalAgent $agent initialisation failed. $exception")

    case Bound(localAddress) =>
    // TODO: do something?
    // It seems that this branch was not executed?

    case CommandFailed(b: Bind) =>
      log.warning(s"CLI connection failed: $b")
      context stop self

    case Connected(remote, local) =>
      val connection = sender()
      log.info(s"CLI connected from $remote to $local")

      val cli = context.actorOf(
        Props(classOf[InternalAgentCLI], remote),
        "cli-" + remote.toString.tail)
      connection ! Register(cli)
    case _ => //noop?
  }

  /**
   * Load Bootables from jars in deploy directory and start AgentActors up.
   *
   */
  def start() = {
    val classnames = getClassnamesWithConfigPath
    classnames.foreach {
      case (classname: String, configPath: String) =>
        if (!agents.exists {
          case (agentname: String, _) => classname == agentname
        }) {
          loadAndStart(classname, configPath)
        } else {
          log.warning("Agent allready running: " + classname)
        }
    }
  }

  def loadAndStart(classname: String, configPath: String) = {
    Try {
      log.info("Instantitating agent: " + classname)
      val clazz = classLoader.loadClass(classname)
      val const = clazz.getConstructor()
      val agent: InternalAgent = const.newInstance().asInstanceOf[InternalAgent]
      val date = new Date()
      agents += classname -> AgentInfo(classname, configPath, Some(agent), new Timestamp(date.getTime))
      agent.init(configPath)
      agent.start()
    } match {
      case Success(_) => ()
      case Failure(e) => e match {
        case e: NoClassDefFoundError =>
          log.warning("Classloading failed. Could not load: " + classname + "\n" + e + " caught")
        case e: ClassNotFoundException =>
          log.warning("Classloading failed. Could not load: " + classname + "\n" + e + " caught")
        case e: Exception =>
          log.warning(s"Classloading failed. $e")
        case t => throw t
      }
    }
  }

  /**
   * Creates classloader for loading classes from jars in deploy directory.
   *
   */
  private[this] def createClassLoader(): ClassLoader = {
    val deploy = new File("deploy")
    if (deploy.exists) {
      loadDeployJars(deploy)
    } else {
      log.warning("No deploy dir found at " + deploy)
      Thread.currentThread.getContextClassLoader
    }
  }

  /**
   * Method for loading jars in deploy directory.
   * Jars should contain class files of agents and their bootables.
   *
   */
  private[this] def loadDeployJars(deploy: File): ClassLoader = {
    val jars = deploy.listFiles.filter(_.getName.endsWith(".jar"))

    val nestedJars = jars flatMap { jar =>
      val jarFile = new JarFile(jar)
      val jarEntries = jarFile.entries.asScala.toArray.filter(_.getName.endsWith(".jar"))
      jarEntries map { entry => new File("jar:file:%s!/%s" format (jarFile.getName, entry.getName)) }
    }

    val urls = (jars ++ nestedJars) map { _.toURI.toURL }

    urls.foreach { url => log.info("Deploying " + url) }

    new URLClassLoader(urls, Thread.currentThread.getContextClassLoader)
  }

  /**
   * Simple function for getting Bootable's name and Agent config file path pairs.
   *
   */
  private[agentSystem] def getClassnamesWithConfigPath: Array[(String, String)] = {
    settings.internalAgents.unwrapped().asScala.map { case (s: String, o: Object) => (s, o.toString) }.toArray
  }
  /**
   * Supervison strategy for supervising AgentActors.
   *
   */
  final val defaultStrategy: SupervisorStrategy = {
    def defaultDecider: Decider = {
      case _: ActorInitializationException => Stop
      case _: ActorKilledException         => Stop
      case _: Exception                    => Restart
    }
    OneForOneStrategy()(defaultDecider)
  }

}
