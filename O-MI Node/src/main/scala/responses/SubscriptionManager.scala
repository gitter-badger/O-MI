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

package responses

import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, duration}
import scala.util.Try
import scala.concurrent.duration._

import akka.actor.{Cancellable, Actor, ActorLogging, Props}
import database._
import http.CLICmds.{ ListSubsCmd, SubInfoCmd}
import http.{OmiConfigExtension}
import responses.CallbackHandler.{ MissingConnection, CallbackFailure}
import types.OdfTypes.OdfTreeCollection.seqToOdfTreeCollection
import types.OdfTypes._
import types.OmiTypes._
import types._

/**
 * Message for triggering handling of intervalsubscriptions
 */
case class HandleIntervals(id: Long)

/**
 * New subscription event
 * @param subscription Subscription to be added
 */
case class NewSubscription(subscription: SubscriptionRequest)

/**
 * Remove subscription event
 * @param id Id of the subscription to remove
 */
case class RemoveSubscription(id: Long)

/**
 * Remove subscription event, used for removing subscrpitions when ttl is over
 *
 * @param id
 */
case class SubscriptionTimeout(id: Long)

/**
 * Event for polling pollable subscriptions
 * @param id Id of the subscription to poll
 */
case class PollSubscription(id: Long)

object SubscriptionManager{
  def props()(
    implicit settings: OmiConfigExtension,
   singleStores: SingleStores,
   callbackHandler: CallbackHandler
  ): Props = Props(
    new SubscriptionManager(
      settings,
      singleStores,
      callbackHandler
    )
  )
}


/**
 * Class that handles event and interval based subscriptions.
 * Uses Akka scheduler to schedule ttl handling and intervalhandling
 */
class SubscriptionManager(
  protected val settings: OmiConfigExtension,
  protected val singleStores: SingleStores,
  protected val callbackHandler: CallbackHandler
) extends Actor with ActorLogging {
  val minIntervalDuration = Duration(1, duration.SECONDS)
  val ttlScheduler = new SubscriptionScheduler
  val intervalScheduler = context.system.scheduler
  val intervalMap: ConcurrentHashMap[Long, Cancellable] = new ConcurrentHashMap

  /**
   * Schedule remove operation for subscriptions that are in prevayler stores,
   * only run at startup
   */
  private[this] def scheduleTtls() = {
    log.debug("Scheduling removesubscriptions for the first time...")
    //interval subs
    val intervalSubs = (singleStores.subStore execute GetAllIntervalSubs())

    val allSubs = (singleStores.subStore execute GetAllEventSubs()) ++
      (singleStores.subStore execute GetAllPollSubs()) ++ intervalSubs

    val currentTime = System.currentTimeMillis()
    intervalSubs.foreach{iSub =>
      val subTime = currentTime - iSub.startTime.getTime
      val initialDelay = (iSub.interval.toMillis - (subTime % iSub.interval.toMillis)).millis
      intervalMap.putIfAbsent(iSub.id, intervalScheduler.schedule(initialDelay, iSub.interval, self, HandleIntervals(iSub.id)))
    }

    allSubs.foreach { sub =>
      if (sub.endTime.getTime() != Long.MaxValue) {
        val nextRun = (sub.endTime.getTime() - currentTime).millis

        if (nextRun.toMillis > 0L) {
          ttlScheduler.scheduleOnce(nextRun, self, SubscriptionTimeout(sub.id))
        } else {
          self ! SubscriptionTimeout(sub.id)
        }
      }
    }
    log.debug("Scheduling done")
  }

  scheduleTtls()

  //TODO FIX handleIntervals() //when server restarts


  def receive: PartialFunction[Any, Unit] = {
    case NewSubscription(subscription) => sender() ! subscribe(subscription)
    case HandleIntervals(id) => handleIntervals(id)
    case RemoveSubscription(id) => sender () ! removeSubscription(id)
    case SubscriptionTimeout(id) => removeSubscription(id)
    case PollSubscription(id) => sender() ! pollSubscription(id)
    case ListSubsCmd() => sender() ! getAllSubs()
    case SubInfoCmd(id) => sender() ! getSub(id)
  }

  private def handlePollEvent(pollEvent: PollEventSub) = {
    log.debug(s"Creating response message for Polled Event Subscription")
    val eventData = (singleStores.pollDataPrevayler execute PollEventSubscription(pollEvent.id))
      .map { case (_path, _values) =>
        OdfInfoItem(_path, _values.sortBy(_.timestamp.getTime()).toVector)
      }.map(createAncestors)

    eventData //eventData.map(eData => Some(eData))
  }

  private def calculateIntervals(pollInterval: PollIntervalSub, values: Seq[OdfValue[Any]], pollTime: Long) = {
    //Refactor
    val buffer: collection.mutable.Buffer[OdfValue[Any]] = collection.mutable.Buffer()
    val lastPolled = pollInterval.lastPolled.getTime()
    val pollTimeOffset = (lastPolled - pollInterval.startTime.getTime()) % pollInterval.interval.toMillis
    val interval = pollInterval.interval.toMillis
    var nextTick = lastPolled + (interval - pollTimeOffset)

    if (values.length >= 2) {
      var i = 1 //Intentionally 1 and not 0
      var previousValue = values.head

      while (i < values.length) {
        if (values(i).timestamp.getTime >= (nextTick)) {
          buffer += previousValue
          nextTick += interval
        } else {
          //if timestmap.getTime < startime + interval
          previousValue = values(i)
          i += 1
        }
      }
      //overcomplicated??
      if (previousValue.timestamp.getTime != pollTime &&
        previousValue.timestamp.getTime() > lastPolled &&
        previousValue.timestamp.getTime() > (nextTick - interval))
        buffer += previousValue
      Some(buffer.toVector)
    } else None
  }

  private def handlePollInterval(pollInterval: PollIntervalSub, pollTime: Long, odfTree: OdfObjects) = {

    log.info(s"Creating response message for Polled Interval Subscription")

    val intervalData = (singleStores.pollDataPrevayler execute PollIntervalSubscription(pollInterval.id))
      .mapValues(_.sortBy(_.timestamp.getTime()))

    val combinedWithPaths =
      OdfTypes  //TODO easier way to get child paths... maybe something like prefix map
              .getOdfNodes(pollInterval.paths.flatMap(path => odfTree.get(path)):_*)
        .map(n => n.path)
        .map(p => p -> Vector[OdfValue[Any]]()).toMap ++ intervalData

    val pollData = combinedWithPaths.map(pathValuesTuple => {

      val (path, values) = pathValuesTuple match {
        case (p, v) if (v.nonEmpty) => {
          v.lastOption match {
            case Some(last) =>
              log.info(s"Found previous values for intervalsubscription: $last")
              (p, v :+ OdfValue(last.value.toString, last.typeValue, new Timestamp(pollTime)))
            case None =>
              val msg = s"Found previous values for intervalsubscription, but lastOption is None, should not be possible."
              log.error(msg)
              throw new Exception(msg)
          }
        } //add polltime
        case (p, v) => {
          log.info(s"No values found for path: $p in Interval subscription poll for sub id ${pollInterval.id}")
          val latestValue = singleStores.latestStore execute LookupSensorData(p) match {
            //lookup latest value from latestStore, if exists use that
            case Some(value) => {
              log.info(s"Found old value from latestStore for sub ${pollInterval.id}")
              Vector(value, OdfValue(value.value, new Timestamp(pollTime), value.attributes))
            }
            //no previous values v is empty
            case _ => {
              log.info("No previous value found return empty values.")
              v
            }
          }
          (p, latestValue)
        }

      }
      val calculatedData = calculateIntervals(pollInterval, values, pollTime)

      calculatedData.map(cData => path -> cData)
    }).flatMap { n => //flatMap removes None values
      //create OdfObjects from InfoItems
      n.map { case (path, values) => createAncestors(OdfInfoItem(path, values)) }
    }

    pollData
  }

  /**
   * Get pollsubscriptions data drom database
   *
   * This method is used to both 'event' and 'interval' based subscriptions.
   *
   * Event subscriptions remove all data from database related to the poll.
   *
   * Interval subscriptions leave one value in the database to serve as starting value for next poll.
   * Database only stores changed values for subscriptions so values need to be interpolated for interval based subscriptions.
   * If sensor updates happen faster than the interval of the subscription then only the newest sensor value is added and older values dropped,
   * on the other hand if interval is shorter than the sensor updates then interpolated values will be generated between sensor values.
   *
   * @param id id of subscription to poll
   * @return
   */
  private def pollSubscription(id: Long): Option[OdfObjects] = {
    val pollTime: Long = System.currentTimeMillis()
    val sub: Option[PolledSub] = singleStores.subStore execute PollSub(id)
    sub match {
      case Some(pollSub) => {
        log.debug(s"Polling subcription with id: ${pollSub.id}")
        val odfTree = singleStores.hierarchyStore execute GetTree()
        val emptyTree = odfTree.intersect(pollSub
          .paths  //get subscriptions paths
          .flatMap(path => odfTree.get(path)) //get odfNode for each path and flatten the Option values
          .foldLeft(OdfObjects()){
            case (objs: OdfObjects, node: OdfNode) => objs.union(createAncestors(node))
          }.valuesRemoved.allMetaDatasRemoved)

        //pollSubscription method removes the data from database and returns the requested data
        val subValues: Iterable[OdfObjects] = pollSub match {

          case pollEvent: PollEventSub => handlePollEvent(pollEvent)

          case pollInterval: PollIntervalSub => handlePollInterval(pollInterval, pollTime, odfTree)
        }
        Some(subValues.fold(emptyTree)(_.union(_)))
      }
      case _ => None
    }
  }

  /**
   * Method called when the interval of an interval subscription has passed
   */
  private def handleIntervals(id: Long): Unit = {
    //TODO add error messages from requesthandler

    log.debug(s"handling interval sub with id: $id")
    val currentTime = System.currentTimeMillis()
    val hTree = singleStores.hierarchyStore execute GetTree()
    val intervalSubscriptionOption = singleStores.subStore execute GetIntervalSub(id)
    intervalSubscriptionOption.foreach { iSub => //same as if exists

      //send new data to callback addresses
      log.debug(s"Trying to send subscription data to ${iSub.callback}")
      val subPaths = iSub.paths.map(path => (path, hTree.get(path)))
      val (failures, nodes) = subPaths.foldLeft[(Seq[Path], Seq[OdfNode])]((Seq(), Seq())){
            case ((paths, _nodes), (p,Some(node))) => (paths, _nodes.:+(node))
            case ((paths, nodes), (p, None))    => (paths.:+(p), nodes)
          }
      val subscribedInfoItems = OdfTypes
        .getInfoItems(nodes: _*)

      val datas = singleStores.latestStore execute LookupSensorDatas(subscribedInfoItems.map(_.path))
      val objects: Vector[OdfObjects] = datas.map {
        case (iPath, oValue) =>
          createAncestors(OdfInfoItem(iPath, Iterable(oValue)))
      }

      val optionObjectsWithoutTypes: Option[OdfObjects] = objects.foldLeft[Option[OdfObjects]](None){
          case (s, n) => Some(s.fold(n)(prev=> prev.union(n)))
        }
      val optionObjects: Option[OdfObjects] = optionObjectsWithoutTypes.map(ob => hTree.intersect(ob))
      val succResult = Vector(Results.Success(OdfTreeCollection(iSub.id), optionObjects))
      val failedResults = if (failures.nonEmpty) Vector(Results.SubscribedPathsNotFound(failures)) else Vector.empty
      val responseTTL = iSub.interval
      val response = ResponseRequest((succResult ++ failedResults).toVector, responseTTL)

      val callbackF = callbackHandler.sendCallback(iSub.callback, response) // FIXME: change resultXml to ResponseRequest(..., responseTTL)
      callbackF.onSuccess {
        case () =>
          log.debug(s"Callback sent; subscription id:${iSub.id} addr:${iSub.callback} interval:${iSub.interval}")
      }
      callbackF.onFailure {
        case fail@MissingConnection(callback) =>
          log.warning(
            s"Callback failed; subscription id:${iSub.id} interval:${iSub.interval}  reason: ${fail.toString}, subscription is remowed.")
          removeSubscription(iSub.id)
        case fail: CallbackFailure =>
          log.warning(
            s"Callback failed; subscription id:${iSub.id} interval:${iSub.interval}  reason: ${fail.toString}")
        case e: Throwable =>
          log.warning(
            s"Callback failed; subscription id:${iSub.id} interval:${iSub.interval}  reason: ${e.getMessage}")
      }
    }
  }


  /**
   * Method used for removing subscriptions using their Id
   * @param id Id of the subscription to remove
   * @return Boolean indicating if the removing was successful
   */
  private def removeSubscription(id: Long): Boolean = {
    lazy val removeIS = singleStores.subStore execute RemoveIntervalSub(id)
    lazy val removePS = singleStores.subStore execute RemovePollSub(id)
    lazy val removeES = singleStores.subStore execute RemoveEventSub(id)
    Option(intervalMap.get(id)).foreach(_.cancel())
    if (removePS) {
      singleStores.pollDataPrevayler execute RemovePollSubData(id)
      removePS
    } else {
      removeIS || removeES
    }
  }

  private def getAllSubs() = {
    log.info("getting list of all subscriptions")
    val intervalSubs = singleStores.subStore execute GetAllIntervalSubs()
    val eventSubs = singleStores.subStore execute GetAllEventSubs()
    val pollSubs = singleStores.subStore execute GetAllPollSubs()
    (intervalSubs, eventSubs, pollSubs)
  }

  private def getSub(id: Long) = {
    val intervalSubs = singleStores.subStore execute GetAllIntervalSubs()
    val eventSubs = singleStores.subStore execute GetAllEventSubs()
    val pollSubs = singleStores.subStore execute GetAllPollSubs()
    val allSubs = intervalSubs ++ eventSubs ++ pollSubs
    allSubs.find { sub => sub.id == id }
  }

  /**
   * Method used to add subcriptions to Prevayler database
   * @param subscription SubscriptionRequest of the subscription to add
   * @return Subscription Id within Try monad if adding fails this is a Failure, otherwise Success(id)
   */
  private def subscribe(subscription: SubscriptionRequest): Try[Long] = {
    Try {
      val newId = singleStores.idPrevayler execute GetAndUpdateId
      val endTime = subEndTimestamp(subscription.ttl)
      val currentTime = System.currentTimeMillis()
      val currentTimestamp = new Timestamp(currentTime)

      val subId = subscription.callback match {
        case cb@Some(callback: RawCallback) =>
          throw RawCallbackFound(s"Tried to subscribe with RawCallback: ${callback.address}")
        case cb@Some(callback: DefinedCallback) => subscription.interval match {
          case Duration(-1, duration.SECONDS) => {
            //normal event subscription


            singleStores.subStore execute AddEventSub(
              EventSub(
                newId,
                OdfTypes.getLeafs(subscription.odf).iterator.map(_.path).toSeq,
                endTime,
                callback
              )
            )
            log.debug(s"Successfully added event subscription with id: $newId and callback: $callback")
            newId
          }
          case dur@Duration(-2, duration.SECONDS) => throw new NotImplementedError("Interval -2 not supported") //subscription for new node
          case dur: FiniteDuration if dur.gteq(minIntervalDuration) => {
            val iSub = IntervalSub(newId,
              OdfTypes.getLeafs(subscription.odf).iterator.map(_.path).toSeq,
              endTime,
              callback,
              dur,
              //new Timestamp(currentTime + dur.toMillis),
              currentTimestamp
            )

            singleStores.subStore execute AddIntervalSub(
              iSub
            )
            intervalMap.put(newId, intervalScheduler.schedule(dur,dur,self,HandleIntervals(newId)))


            log.info(s"Successfully added interval subscription with id: $newId and callback $callback")
            newId
          }
          case dur: Duration => {
            val msg = s"Duration $dur is unsupported"
            log.error(msg)
            throw new Exception(msg)
          }
        }
        case None => {
          val paths = OdfTypes.getLeafs(subscription.odf).iterator.map(_.path).toSeq
          subscription.interval match {
            case Duration(-1, duration.SECONDS) => {
              //event poll sub
              singleStores.subStore execute AddPollSub(
                PollEventSub(
                  newId,
                  endTime,
                  currentTimestamp,
                  currentTimestamp,
                  paths
                )
              )

              log.debug(s"Successfully added polled event subscription with id: $newId")
              newId
            }
            case dur: FiniteDuration if dur.gteq(minIntervalDuration) => {
              //interval poll
              singleStores.subStore execute AddPollSub(
                PollIntervalSub(
                  newId,
                  endTime,
                  dur,
                  currentTimestamp,
                  currentTimestamp,
                  paths
                )
              )
              newId
            }
            case dur: Duration => {
              log.error(s"Duration $dur is unsupported")
              throw new Exception(s"Duration $dur is unsupported")
            }

          }
        }
      }
      subscription.ttl match {
        case dur: FiniteDuration => ttlScheduler.scheduleOnce(dur, self, SubscriptionTimeout(newId))
        case _ =>
      }
      subId
    }
  }

  /**
   * Helper method to get the Timestamp for removing the subscription
   * @param subttl time to live of the subscription
   * @return endTime of subscription as Timestamp
   */
  private def subEndTimestamp(subttl: Duration): Timestamp = {
    if (subttl.isFinite()) {
      new Timestamp(System.currentTimeMillis() + subttl.toMillis)
    } else {
      new Timestamp(Long.MaxValue)
    }
  }


}
