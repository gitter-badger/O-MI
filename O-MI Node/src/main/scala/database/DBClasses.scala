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
package database

import slick.driver.H2Driver.api._
import java.sql.Timestamp

import scala.concurrent.Await
import scala.concurrent.duration._
//import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.collection.JavaConversions.asJavaIterable


import types._
import types.OdfTypes._
import types.OmiTypes.SubLike
//import types.Path._
import database._


/**
 * Base trait for databases. Has basic protected interface.
 */
trait DBBase{
  protected[this] val db: Database


  //private[this] val dbtimeout = http.Boot.Settings

  /**
   * We use a more synchronized api for db using these two functions.
   * Takes a DBIO as parameter and runs it returning the result.
   */
  protected def runSync[R]: DBIOAction[R, NoStream, Nothing] => R =
    io => Await.result(db.run(io), 5.minutes)
    // db operations shouldn't last longer than 5 minutes TODO: make a config option

  /**
   * Takes a DBIO as parameter and runs it waiting for it to finish.
   */
  protected def runWait: DBIOAction[_, NoStream, Nothing] => Unit =
    io => Await.ready(db.run(io), 5.minutes) // db operations shouldn't last longer than 5 minutes

}




/**
 * Public datatypes
 */


case class SubscriptionItem(
  val subId: Long,
  val path: Path,
  val lastValue: Option[String] // for event polling subs
)

// For db table type, match to and use subclasses of this
sealed trait DBSubInternal

/**
 * DBSub class to represent subscription information
 * @param ttl time to live. in seconds. subscription expires after ttl seconds
 * @param interval to store the interval value to DB
 * @param callback optional callback address. use None if no address is needed
 */
case class DBSub(
  val id: Long,
  val interval: Duration,
  val startTime: Timestamp,
  val ttl: Duration,
  val callback: Option[String]
) extends SubLike with DBSubInternal

case class NewDBSub(
  val interval: Duration,
  val startTime: Timestamp,
  val ttl: Duration,
  val callback: Option[String]
) extends SubLike with DBSubInternal


/**
 * Represents one sensor value
 */
case class DBValue(
  hierarchyId: Int,
  timestamp: Timestamp,
  value: String,
  valueType: String,
    valueId: Option[Long] = None
) {
  def toOdf = OdfValue(value, valueType, timestamp)
}




trait OmiNodeTables extends DBBase {

  implicit val pathColumnType = MappedColumnType.base[Path, String](
    { _.toString }, // Path to String
    { Path(_) }     // String to Path
    )




  /**
   * Implementation of the http://en.wikipedia.org/wiki/Nested_set_model
   * with depth.
   * @param id 
   * @param path
   * @param leftBoundary Nested set model: left value
   * @param rightBoundary Nested set model: right value
   * @param depth Extended nested set model: depth of this node in the tree
   * @param description for the corresponding odf node (Object or InfoItem)
   * @param pollRefCount Count of references to this node from active poll subscriptions
   */
  case class DBNode(
    id: Option[Int],
    path: Path,
    leftBoundary: Int,
    rightBoundary: Int,
    depth: Int,
    description: String,
    pollRefCount: Int,
    isInfoItem: Boolean 
  ) {
    def descriptionOdfOption =
      if (description.nonEmpty) Some(OdfDescription(description))
      else None


    def toOdfObject: OdfObject = toOdfObject()
    def toOdfObject(infoitems: Iterable[OdfInfoItem] = Iterable(), objects: Iterable[OdfObject] = Iterable()) =
      OdfObject(path, infoitems, objects, descriptionOdfOption, None)

    def toOdfObjects: OdfObjects = OdfObjects()


    def toOdfInfoItem: OdfInfoItem = toOdfInfoItem()
    def toOdfInfoItem(values: Iterable[OdfValue] = Iterable()) =
      OdfInfoItem(path, values, descriptionOdfOption, None)
  }

  implicit val DBNodeOrdering = Ordering.by[DBNode, Int](_.leftBoundary)

  /**
   * (Boilerplate) Table to store object hierarchy.
   */
  class DBNodesTable(tag: Tag)
    extends Table[DBNode](tag, "HIERARCHYNODES") {
    /** This is the PrimaryKey */
    def id            = column[Int]("HIERARCHYID", O.PrimaryKey, O.AutoInc)
    def path          = column[Path]("PATH")
    def leftBoundary  = column[Int]("LEFTBOUNDARY")
    def rightBoundary = column[Int]("RIGHTBOUNDARY")
    def depth         = column[Int]("DEPTH")
    def description   = column[String]("DESCRIPTION")
    def pollRefCount  = column[Int]("POLLREFCOUNT")
    def isInfoItem    = column[Boolean]("ISINFOITEM")

    def pathIndex = index("IDX_HIERARCHYNODES_PATH", path, unique = true)

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (id.?, path, leftBoundary, rightBoundary, depth, description, pollRefCount, isInfoItem) <> (
      DBNode.tupled,
      DBNode.unapply
    )
  }
  protected[this] val hierarchyNodes = TableQuery[DBNodesTable] //table for storing hierarchy
  protected[this] val hierarchyWithInsertId = hierarchyNodes returning hierarchyNodes.map(_.id)

  trait HierarchyFKey[A] extends Table[A] {
    val hierarchyfkName: String
    def hierarchyId = column[Int]("HIERARCHYID")
    def hierarchy = foreignKey(hierarchyfkName, hierarchyId, hierarchyNodes)(
      _.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
  }






  /**
   * (Boilerplate) Table for storing latest sensor data to database
   */
  class DBValuesTable(tag: Tag)
    extends Table[DBValue](tag, "SENSORVALUES") with HierarchyFKey[DBValue] {
    val hierarchyfkName = "VALUESHIERARCHY_FK"
    // from extension:
    //def hierarchyId = column[Int]("HIERARCHYID")
    def id            = column[Long]("VALUEID", O.PrimaryKey, O.AutoInc)
    def timestamp = column[Timestamp]("TIME",O.SqlType("TIMESTAMP(3)"))
    def value = column[String]("VALUE")
    def valueType = column[String]("VALUETYPE")

    /** Primary Key: (hierarchyId, timestamp) */
    //def pk = primaryKey("PK_DBDATA", (hierarchyId, timestamp))

    def * = (hierarchyId, timestamp, value, valueType, id.?) <> (DBValue.tupled, DBValue.unapply)
  }

  protected[this] val latestValues = TableQuery[DBValuesTable] //table for sensor data







  case class DBMetaData(
    val hierarchyId: Int,
    val metadata: String
  ) {
    def toOdf = OdfMetaData(metadata)
  }

  /**
   * (Boilerplate) Table for storing metadata for sensors as string e.g XML block as string
   */
  class DBMetaDatasTable(tag: Tag)
    extends Table[DBMetaData](tag, "METADATA") with HierarchyFKey[DBMetaData] {
    val hierarchyfkName = "METADATAHIERARCHY_FK"
    /** This is the PrimaryKey */
    override def hierarchyId = column[Int]("HIERARCHYID", O.PrimaryKey)
    def metadata    = column[String]("METADATA")

    def * = (hierarchyId, metadata) <> (DBMetaData.tupled, DBMetaData.unapply)
  }
  protected[this] val metadatas = TableQuery[DBMetaDatasTable]//table for metadata information







  /**
   * (Boilerplate) Table for O-MI subscription information
   */
  class DBSubsTable(tag: Tag)
    extends Table[DBSubInternal](tag, "SUBSCRIPTIONS") {
    /** This is the PrimaryKey */
    def id        = column[Long]("ID", O.PrimaryKey, O.AutoInc)
    def interval  = column[Double]("INTERVAL")
    def startTime = column[Timestamp]("START")
    def ttl       = column[Double]("TTL")
    def callback  = column[Option[String]]("CALLBACK")


    private[this] def dbsubTupled:
      ((Option[Long], Double, Timestamp, Double, Option[String])) => DBSubInternal = {
        case (None, interval_, startTime_, ttl_, callback_) =>
          val durationTtl =
            if (ttl_ == -1.0) Duration.Inf else ttl_.seconds

          val durationInt = parsing.OmiParser.parseInterval(interval_)
          NewDBSub(durationInt, startTime_, durationTtl, callback_)

        case (Some(id_), interval_, startTime_, ttl_, callback_) =>
          val durationTtl =
            if (ttl_ == -1.0) Duration.Inf else ttl_.seconds

          val durationInt = parsing.OmiParser.parseInterval(interval_)
          DBSub(id_, durationInt, startTime_, durationTtl, callback_)
      }
    private[this] def dbsubUnapply: 
      DBSubInternal => Option[(Option[Long], Double, Timestamp, Double, Option[String])] = {
        case DBSub(id_, interval_, startTime_, ttl_, callback_) =>
          val ttlDouble = if (ttl_.isFinite) ttl_.toUnit(SECONDS) else -1.0

          Some((Some(id_), interval_.toUnit(SECONDS), startTime_, ttlDouble, callback_))

        case NewDBSub(interval_, startTime_, ttl_, callback_) =>
          val ttlDouble = if (ttl_.isFinite) ttl_.toUnit(SECONDS) else -1.0

          Some((None, interval_.toUnit(SECONDS), startTime_, ttlDouble, callback_))
          
        case _ => None
      }

    def * =
      (id.?, interval, startTime, ttl, callback).shaped <> (
      dbsubTupled, dbsubUnapply
    )
  }

  protected[this] val subs = TableQuery[DBSubsTable]
  protected[this] val subsWithInsertId = subs returning subs.map(_.id)

  trait SubFKey[A] extends Table[A] {
    val subfkName: String
    def subId = column[Long]("SUBID")
    def sub   = foreignKey(subfkName, subId, subs)(
      _.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade
    )
  }





  case class DBSubscriptionItem(
    val subId: Long,
    val hierarchyId: Int,
    val lastValue: Option[String] // for event polling subs
  )
  /**
   * Storing paths of subscriptions
   */
  class DBSubscribedItemsTable(tag: Tag)
      extends Table[DBSubscriptionItem](tag, "SUBITEMS")
      with SubFKey[DBSubscriptionItem]
      with HierarchyFKey[DBSubscriptionItem] {
    val hierarchyfkName = "SUBITEMSHIERARCHY_FK"
    val subfkName = "SUBITEMSSUB_FK"
    // from extension:
    //def subId = column[Int]("SUBID")
    //def hierarchyId = column[Int]("HIERARCHYID")
    def lastValue = column[Option[String]]("LASTVALUE")
    def pk = primaryKey("PK_SUBITEMS", (subId, hierarchyId))
    def * = (subId, hierarchyId, lastValue) <> (DBSubscriptionItem.tupled, DBSubscriptionItem.unapply)
  }

  protected[this] val subItems = TableQuery[DBSubscribedItemsTable]

  protected[this] val allTables =
    Seq( hierarchyNodes
       , latestValues
       , metadatas
       , subs
       , subItems
       )

  protected[this] val allSchemas = allTables map (_.schema) reduceLeft (_ ++ _)

  /**
   * Empties all the data from the database
   * 
   */
  def clearDB() = runWait(
    DBIO.seq(
      (allTables map (_.delete)): _* 
    ).andThen(hierarchyNodes += DBNode(None, Path("/Objects"), 1, 2, Path("/Objects").length, "", 0, false))
  )

  def dropDB() = runWait( allSchemas.drop )
    
}
