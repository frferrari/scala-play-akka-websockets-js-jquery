package websockets

import play.api.libs.json._
import play.api.libs.functional.syntax._

/*
 * Help was found here :
 *
 * http://mandubian.com/2012/09/08/unveiling-play-2-dot-1-json-api-part1-jspath-reads-combinators/
 */

object OutcomingMessage {
	val errorMessage = "error"
	val infoMessage = "info"
	val refreshMessage = "refresh"
}
abstract class OutcomingMessage(messageType: String)

case class RefreshMessage(repository: String, stargazersCount: String, messageType: String = OutcomingMessage.refreshMessage) extends OutcomingMessage(messageType)
case class ErrorMessage(message: String, messageType: String = OutcomingMessage.errorMessage) extends OutcomingMessage(messageType)
case class InfoMessage(message: String, messageType: String = OutcomingMessage.infoMessage) extends OutcomingMessage(messageType)

object RefreshMessage {
	implicit val RefreshMessageWrites: Writes[RefreshMessage] = (
		(JsPath \ "repository").write[String] and
		(JsPath \ "stars").write[String] and
		(JsPath \ "type").write[String]
	)(unlift(RefreshMessage.unapply))
}

object ErrorMessage {
	implicit val ErrorMessageWrites: Writes[ErrorMessage] = (
		(JsPath \ "msg").write[String] and
		(JsPath \ "type").write[String]
	)(unlift(ErrorMessage.unapply))
}

object InfoMessage {
	implicit val InfoMessageWrites: Writes[InfoMessage] = (
		(JsPath \ "msg").write[String] and
		(JsPath \ "type").write[String]
	)(unlift(InfoMessage.unapply))
}
