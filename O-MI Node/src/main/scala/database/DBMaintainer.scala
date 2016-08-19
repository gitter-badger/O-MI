/*+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 +    Copyright (c) 2015 Aalto University.                                        +
 +                                                                                +
 +    Licensed under the 4-clause BSD (the "License");                            +
 +    you may not use this file except in compliance with the License.            +
 +    You may obtain a copy of the License at top most directory of project.      +
 +                                                                                +
 +    Unless required by applicable law or agreed to in writing, software         +
 +    distributed under the License is distributed on an "AS IS" BASIS,           +
 +    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    +
 +    See the License for the specific language governing permissions and         +
 +    limitations under the License.                                              +
 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++*/

package database

import java.io.{File, FilenameFilter}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import akka.actor._
import akka.dispatch.{BoundedMessageQueueSemantics, RequiresMessageQueue}
import org.prevayler.Prevayler
import http.OmiConfigExtension

object DBMaintainer{
  def props(
    dbConnection : DBReadWrite,
    singleStores : SingleStores,
    settings : OmiConfigExtension
  ) : Props = Props( new DBMaintainer(dbConnection, singleStores, settings) )
}
class DBMaintainer(
  protected val dbConnection : DBReadWrite,
  protected val singleStores : SingleStores,
  protected val settings : OmiConfigExtension
)
  extends Actor
  with ActorLogging
  with RequiresMessageQueue[BoundedMessageQueueSemantics]
  {
  
  case object TrimDB
  case object TakeSnapshot
  private val scheduler = context.system.scheduler
  private val trimInterval: FiniteDuration = settings.trimInterval
  private val snapshotInterval = settings.snapshotInterval
  log.info(s"scheduling databse trimming every $trimInterval")
  scheduler.schedule(trimInterval, trimInterval, self, TrimDB)
  if( settings.writeToDisk){
    log.info(s"scheduling prevayler snapshot every $snapshotInterval")
    scheduler.schedule(snapshotInterval, snapshotInterval, self, TakeSnapshot)
  } else {
    log.info("using transient prevayler, taking snapshots is not in use.")
  }
  type Hole
  private def takeSnapshot: FiniteDuration = {
    def trySnapshot[T](p: Prevayler[T], errorName: String): Unit = {
      Try[Unit]{
        p.takeSnapshot() // returns snapshot File
      }.recover{case a : Throwable => log.error(a,s"Failed to take Snapshot of $errorName")}
    }

    log.info("Taking prevyaler snapshot")
    val start: FiniteDuration  = Duration(System.currentTimeMillis(),MILLISECONDS)

    trySnapshot(singleStores.latestStore, "latestStore")
    trySnapshot(singleStores.hierarchyStore, "hierarchyStore")
    trySnapshot(singleStores.subStore, "subStore")
    trySnapshot(singleStores.idPrevayler, "idPrevayler")

    val end : FiniteDuration = Duration(System.currentTimeMillis(),MILLISECONDS)
    val duration : FiniteDuration = end - start
    duration
  }
  /**
   * Function for handling InputPusherCmds.
   *
   */
  override def receive: Actor.Receive = {
    case TrimDB                         => {
      val numDel = dbConnection.trimDB()
      numDel.map(n=>log.info(s"DELETE returned ${n.sum}"))}
    case TakeSnapshot                   => {
      val snapshotDur: FiniteDuration = takeSnapshot
      log.info(s"Taking Snapshot took $snapshotDur")
      // remove unnecessary files (might otherwise grow until disk is full)
      val dirs = singleStores.prevaylerDirectories
      for (dir <- dirs) {
        val prevaylerDir = new org.prevayler.implementation.PrevaylerDirectory(dir)
        Try{prevaylerDir.necessaryFiles()} match {
          case Failure(e) =>
            log.warning(s"Exception reading directory $dir for prevayler cleaning: $e")
          case Success(necessaryFiles) =>
            val allFiles = dir.listFiles(new FilenameFilter {
              def accept(dir: File, name: String) = (name endsWith ".journal") || (name endsWith ".snapshot")
            })
            
            val extraFiles = allFiles filterNot (necessaryFiles contains _)

            extraFiles foreach {file =>
              Try{file.delete} match {
                case Success(true) => // noop
                case Success(false) =>
                  log.warning(s"File $file was listed unnecessary but couldn't be deleted")
                case Failure(e) => 
                  log.warning(s"Exception when trying to delete unnecessary file $file: $e")
              }
            }
        }
        
      }
    }
    case _ => log.warning("Unknown message received.")
  }
}
