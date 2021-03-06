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
package parsing

import java.io.File
import java.sql.Timestamp
import javax.xml.transform.stream.StreamSource

import scala.collection.JavaConversions.{asJavaIterable, iterableAsScalaIterable}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node,UnprefixedAttribute }

import akka.http.scaladsl.model.RemoteAddress
import parsing.xmlGen.xmlTypes
import types.OdfTypes._
import types.OmiTypes._
import types.OmiTypes.Callback._ // implicit: String => Callback
import types.ParseError._
import types._

/** Parser for messages with O-MI protocol*/
object OmiParser extends Parser[OmiParseResult] {

  protected[this] override def schemaPath = new StreamSource(getClass.getClassLoader().getResourceAsStream("omi.xsd"))

  /**
   * Public method for parsing the xml file into OmiParseResults.
   *
   *  @param file XML formatted string to be parsed. Should be in O-MI format.
   *  @return OmiParseResults
   */
  def parse(file: File, user: Option[RemoteAddress]): OmiParseResult = {
    val parsed = Try(
      XMLParser.loadFile(file)
    )

    parseTry(parsed, user)
  }


  /**
   * Public method for parsing the xml string into OmiParseResults.
   *
   *  @param xml_msg XML formatted string to be parsed. Should be in O-MI format.
   *  @return OmiParseResults
   */
  def parse(xml_msg: String, user: Option[RemoteAddress] = None): OmiParseResult = {
    /*Convert the string into scala.xml.Elem. If the message contains invalid XML, send correct ParseError*/
   val parsed = Try(
     XMLParser.loadString(xml_msg)
   )
   parseTry(parsed, user)

  }

  private def parseTry(parsed: Try[Elem], user: Option[RemoteAddress]): OmiParseResult = {
    parsed match {
      case Success(root) => parseOmi(root, user)
      case Failure(f) => Left( Iterable( ParseError(s"OmiParser: Invalid XML: ${f.getMessage}")))
    }
  }

  /**
   * Public method for parsing the xml root node into OmiParseResults.
   *
   *  @param root XML formatted string to be parsed. Should be in O-MI format.
   *  @return OmiParseResults
   */
  @deprecated("Not supported because of xml external entity attack fix, use this.XMLParser! -- TK", "2016-04-01")
  def parse(root: xml.Node, user: Option[RemoteAddress]): OmiParseResult = parseOmi(root, user)

  private def parseOmi(root: xml.Node, user: Option[RemoteAddress]): OmiParseResult = schemaValidation(root) match {
    case errors : Seq[ParseError] if errors.nonEmpty=>
      Left(errors.map { pe: ParseError => ParseError("OmiParser: " + pe.msg) })
    case empty : Seq[ParseError] if empty.isEmpty =>
      Try{
        val envelope = xmlGen.scalaxb.fromXML[xmlTypes.OmiEnvelope](root)

        //protocol version check
        if(envelope.version != supportedVersion) throw new Exception(s"Unsupported protocol version: ${envelope.version} current supported Version is $supportedVersion")

        // Try to recognize unsupported features
        envelope.omienvelopeoption.value match {
          case request: xmlTypes.RequestBaseTypable if request.nodeList.isDefined =>
            throw new NotImplementedError("nodeList attribute functionality is not supported")
          case _ => //noop
        }

        envelope.omienvelopeoption.value match {
          case read: xmlTypes.ReadRequest => parseRead(read, parseTTL(envelope.ttl), user)
          case write: xmlTypes.WriteRequest => parseWrite(write, parseTTL(envelope.ttl), user)
          case cancel: xmlTypes.CancelRequest => parseCancel(cancel, parseTTL(envelope.ttl))
          case response: xmlTypes.ResponseListType => parseResponse(response, parseTTL(envelope.ttl))
          case _ => throw new Exception("Unknown request type returned by scalaxb")
        }
        } match {
          case Success(res) => res
          case Failure(e) => Left( Iterable( ParseError(e + " thrown when parsed.") ) )
          case _ => throw new Exception("Unknown end state from OmiParser.")
        }
  }

  // fixes problem with duration: -1.0.seconds == -999999999 nanoseconds
  def parseInterval(v: Double): Duration =
    v match{
      case -1.0 =>  -1.seconds
      case w if w >= 0 => w.seconds
      case _ => throw new IllegalArgumentException("Illegal interval, only positive or -1 are allowed.")
    }
  def parseTTL(v: Double): Duration =
    v match{
      case -1.0 => Duration.Inf
      case 0.0 => Duration.Inf
      case w if w > 0 => w.seconds
      case _ => throw new IllegalArgumentException("Negative Interval, diffrent than -1 isn't allowed.")
    }

  def parseRequestID(id: xmlTypes.IdType): Long = id.value.trim.toLong

  private[this] def parseRead(read: xmlTypes.ReadRequest, ttl: Duration, user: Option[RemoteAddress]): OmiParseResult = {
    val callback = read.callback.map{ addr => RawCallback( addr.toString ) }
    if(read.requestID.nonEmpty) {
      Right(Iterable(
        PollRequest(
          callback,
          OdfTreeCollection(read.requestID.map(parseRequestID):_*),
          ttl,
          user
        )))
    } else{
      read.msg match {
        case Some(msg) => {
          val odfParseResult = parseMsg(read.msg, read.msgformat)
          odfParseResult match {
            case Left(errors)  => Left(errors)
            case Right(odf) => 
              read.interval match {
                case None =>
                  Right(Iterable(
                    ReadRequest(
                      odf,
                      gcalendarToTimestampOption(read.begin),
                      gcalendarToTimestampOption(read.end),
                      read.newest,
                      read.oldest,
                      callback,
                      ttl,
                      user
                    )
                  ))
                case Some(interval) =>
                  Right(Iterable(
                    SubscriptionRequest(
                      parseInterval(interval),
                      odf,
                      read.newest,
                      read.oldest,
                      callback,
                      ttl,
                      user
                    )
                  ))
              }
          }
        }
                case None => {
                  Left(
                    Iterable(
                      ParseError("Invalid Read request, needs either of \"omi:msg\" or \"omi:requestID\" elements.")
                    )
                  )
                }
      }
    }
  }

  private[this] def parseWrite(write: xmlTypes.WriteRequest, ttl: Duration, user: Option[RemoteAddress]): OmiParseResult = {
    val odfParseResult = parseMsg(write.msg, write.msgformat)
    val callback = write.callback.map{ addr => RawCallback( addr.toString ) }
    odfParseResult match {
      case Left(errors)  => Left(errors)
      case Right(odf) =>
        Right(Iterable(
          WriteRequest(
            odf,
            callback,
            ttl,
            user
          )
        ))
    }
  }

  private[this] def parseCancel(cancel: xmlTypes.CancelRequest, ttl: Duration): OmiParseResult = {
    Right(Iterable(
      CancelRequest(
        OdfTreeCollection(cancel.requestID.map(parseRequestID):_*),
        ttl
      )
    ))
  }
  private[this] def parseResponse(response: xmlTypes.ResponseListType, ttl: Duration): OmiParseResult = Try{
    Iterable(
      ResponseRequest(
        OdfTreeCollection(response.result.map{
          result =>
            OmiResult(
              OmiReturn(
                result.returnValue.returnCode,
                result.returnValue.description
              ),
            OdfTreeCollection( result.requestID.map(parseRequestID).toSeq : _* ), 
            result.msg.map{
              case msg : xmlGen.scalaxb.DataRecord[Any] => 
                //TODO: figure right type parameter
                val odfParseResult = parseMsg(result.msg, result.msgformat)
                odfParseResult match {
                  case Left(errors)  => throw combineErrors(iterableAsScalaIterable(errors))
                  case Right(odf) => odf
                }
            }
            )
        }:_*)
      , ttl)
    )
  } match {
    case Success( requests: Iterable[OmiRequest] ) => Right(requests)
    case Failure(error : ParseError) =>  Left(Iterable(error))
    case Failure(t: Throwable) => throw t
  }

  private[this] def parseMsg(msgO: Option[xmlGen.scalaxb.DataRecord[Any]], format: Option[String]): OdfParseResult = msgO match{
    case None =>
      Left(Iterable(ParseError("OmiParser: No msg element found in write request.")))
    case Some(msg) if format.isEmpty =>
      Left(Iterable(ParseError("OmiParser: Missing msgformat attribute.")))
    case Some(msg) if format.nonEmpty =>

      val data = msg.as[Elem]
      format match {
        case Some("odf") =>
          val odf = (data \ "Objects")
          odf.headOption match {
            case Some(head) =>
              parseOdf(head/*.asInstanceOf[Elem] % new UnprefixedAttribute("xmlns", "odf.xsd",Node.NoAttributes)*/)
            case None =>
              Left(Iterable(ParseError("No Objects child found in msg.")))
          }
            case _ =>
              Left(Iterable(ParseError("Unknown msgformat attribute")))
      }
  }
  private[this] def parseOdf(node: Node): OdfParseResult = OdfParser.parse(node)

  def gcalendarToTimestampOption(gcal: Option[javax.xml.datatype.XMLGregorianCalendar]): Option[Timestamp] = gcal match {
    case None => None
    case Some(cal) => Some(new Timestamp(cal.toGregorianCalendar().getTimeInMillis()));
  }
  def uriToStringOption(opt: Option[java.net.URI]): Option[String] = opt map {
    uri => uri.toString
  }
}


