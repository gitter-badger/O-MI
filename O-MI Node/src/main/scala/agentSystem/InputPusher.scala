package agentSystem

import database._
import parsing.Types._
import parsing.Types.Path._
import java.util.Date

/** Trait for pushing data from sensors to db.
  *
  */
trait InputPusher {
  /** Function for handling sequences of OdfObject.
    *
    */
  def handleObjects( objs: Seq[OdfObject] ) : Unit
  /** Function for handling sequences of OdfInfoItem.
    *
    */
  def handleInfoItems( infoitems: Seq[OdfInfoItem]) : Unit  
  /** Function for handling sequences of path and value pairs.
    *
    */
  def handlePathValuePairs( pairs: Seq[(String,TimedValue)] ): Unit
  /** Function for handling sequences of path and MetaData pairs.
    *
    */
  def handlePathMetaDataPairs( pairs: Seq[(Path,String)] ): Unit
}

/** Object for pushing data from sensors to db.
  *
  */
object InputPusher extends InputPusher{
  
  /** Function for handling sequences of OdfObject.
    *
    */
  override def handleObjects( objs: Seq[OdfObject] ) : Unit = {
    for(obj <- objs){
      if(obj.childs.nonEmpty)
        handleObjects(obj.childs)
      if(obj.sensors.nonEmpty)
        handleInfoItems(obj.sensors)
    }
  }

  /** Function for handling sequences of OdfInfoItem.
    *
    */
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
            SQLite.set(sensorData)
      }  
    }
  } 
  
  /** Function for handling sequences of path and value pairs.
    *
    */
  override def handlePathValuePairs( pairs: Seq[(String,TimedValue)] ) : Unit ={
    SQLite.setMany(pairs.toList)
  }
  def handlePathMetaDataPairs( pairs: Seq[(Path,String)] ): Unit ={
    pairs.foreach{ pair => SQLite.setMetaData(pair._1, pair._2)}
  }

}
