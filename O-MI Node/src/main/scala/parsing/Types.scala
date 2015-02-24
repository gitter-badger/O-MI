package parsing
import java.sql.Timestamp

object Types {
  /** absract trait that represent either error or request in the O-MI
    *
    */
  abstract sealed trait ParseMsg

  /** case class that represents parsing error
   *  @param msg error message that describes the problem.
   */
  case class ParseError(msg: String) extends ParseMsg
  case class OneTimeRead( ttl: String,
                          sensors: Seq[ OdfObject],
                          begin: Option[Timestamp] = None,
                          end: Option[Timestamp] = None,
                          newest: Option[Int] = None,
                          oldest: Option[Int] = None,
                          callback: Option[String] = None,
                          requestId: Seq[ String] = Seq.empty
                        ) extends ParseMsg
  case class Write( ttl: String,
                    sensors: Seq[ OdfObject],
                    callback: Option[String] = None,
                    requestId: Seq[ String] = Seq.empty
                  ) extends ParseMsg
  case class Subscription(  ttl: String,
                            interval: String,
                            sensors: Seq[ OdfObject],
                            begin: Option[Timestamp] = None,
                            end: Option[Timestamp] = None,
                            newest: Option[Int] = None,
                            oldest: Option[Int] = None,
                            callback: Option[String] = None,
                            requestId: Seq[ String] = Seq.empty
                            ) extends ParseMsg {

    def hasInterval  = interval.toDouble >= 0.0
    def isEventBased = interval.toDouble == -1
  }
  case class Result(  returnValue: String,
                      returnCode: String,
                      parseMsgOp: Option[ Seq[ OdfObject] ],
                      requestId: Seq[ String] = Seq.empty
                    ) extends ParseMsg
  case class Cancel(  ttl: String,
                      requestId: Seq[ String]
                    ) extends ParseMsg


  /** case classs that represnts a value of InfoItem in the O-DF
    *
    * @param optional timestamp when value was measured
    * @param measured value
    */
  case class TimedValue( time: Option[Timestamp], value: String)

  /** absract trait that reprasents an node in the O-DF either Object or InfoItem
    *
    */
  abstract sealed trait OdfNode

  /** case class that represents an InfoItem in the O-DF
   *  
   *  @param path path to the InfoItem as a Seq[String] e.g. Seq("Objects","SmartHouse","SmartFridge","PowerConsumption")
   *  @param InfoItem's values found in xml structure, TimedValue.
   *  @param metadata Object may contain metadata, 
   *         metadata can contain e.g. value type, units or similar information
   */
  case class OdfInfoItem( path: Path,
                          timedValues: Seq[ TimedValue],
                          metadata: String 
                        ) extends OdfNode
  /** case class that represents an Object in the O-DF
   *  
   *  @param path path to the Object as a Seq[String] e.g. Seq("Objects","SmartHouse","SmartFridge")
   *  @param Object's childs found in xml structure.
   *  @param Object's InfoItems found in xml structure.
   *  @param metadata Object may contain metadata, 
   *         metadata can contain e.g. value type, units or similar information
   */
  case class OdfObject( path: Path,
                        childs: Seq[OdfObject],
                        sensors: Seq[OdfInfoItem]
                      ) extends OdfNode

  type  OdfParseResult = Either[ParseError, OdfObject]

  /**
   * Path is a wrapper for Seq[String] representing path to an O-DF Object
   * It abstracts path seperators ('/') from error prone actions such as joining
   * two paths or creating new Paths from user input.
   * Path can be used as a sequence via an implicit conversion or _.toSeq
   */
  class Path(pathSeq: Seq[String]){
    import Path._
    val toSeq = {
      val normalized = pathSeq.filterNot(_ == "")
      normalized.toSeq
    }

    def this(pathStr: String) = this{
      pathStr.split("/")
    }

    /**
     * Join two paths.
     * @param otherPath other path to join at the end of this one
     * @return new path with other joined to this path
     */
    def /(otherPath: Path): Path = Path(this ++ otherPath)

    def /(otherPathStr: String): Path = {
      this / Path(otherPathStr)
    }
    override def equals(that: Any): Boolean = that match{
      case thatPath: Path => thatPath.toSeq.equals(this.toSeq)
      case _ => false
    }

    /**
     * Creates a path string which represents this path with '/' seperators.
     * Representation doesn't start nor end with a '/'.
     */
    override def toString: String = this.mkString("/")
  }


  object Path {
    def apply(pathStr: String): Path = new Path(pathStr)
    def apply(pathSeq: Seq[String]): Path = new Path(pathSeq)
    val empty = new Path(Seq.empty)

    import scala.language.implicitConversions // XXX: maybe a little bit stupid place for this

    implicit def PathAsSeq(p: Path): Seq[String] = p.toSeq
    implicit def SeqAsPath(s: Seq[String]): Path = Path(s)
  }
}
