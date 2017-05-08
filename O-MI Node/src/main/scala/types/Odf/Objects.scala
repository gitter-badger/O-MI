package odf

import scala.collection.{ Seq, Map }
import scala.collection.immutable.HashMap 
import parsing.xmlGen.scalaxb.DataRecord
import parsing.xmlGen.xmlTypes.{ObjectsType, ObjectType}

class Objects(
  val version: Option[String] = None,
  val attributes: Map[String,String] = HashMap.empty
) extends Node {
  val parentPath: Option[Path] = None
  def createAncestors: Seq[Node] = {
    Vector()
  }
  def union( that: Objects ) : Objects ={
    new Objects(
      version.orElse(that.version),//What if versions are differents?
      attributes ++ that.attributes
    )
  }
  implicit def asObjectsType( objects: Seq[ObjectType]) : ObjectsType ={
    ObjectsType(
      objects,
      attributes = attributesToDataRecord( attributes ) ++ version.map{
        case version: String => "@version" -> DataRecord(version)
      }  
    )
  }
}
