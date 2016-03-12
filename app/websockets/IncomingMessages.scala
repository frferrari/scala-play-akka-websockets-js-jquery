package websockets

import javax.inject._

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.data.validation.ValidationError

object IncomingMessage {
	val subscribeMessage = "subscribe"
	val unsubscribeMessage = "unsubscribe"
}

abstract class IncomingMessage(action: String)

case class SubscribeMessage(repository: String, interval: Int, action: String = IncomingMessage.subscribeMessage) extends IncomingMessage(action)
case class UnsubscribeMessage(repository: String, action: String = IncomingMessage.unsubscribeMessage) extends IncomingMessage(action)

object SubscribeMessage {
	implicit val SubscribeMessageWrites: Reads[SubscribeMessage] = (
		(JsPath \ "repository").read[String] and
		(JsPath \ "interval").read[Int](min(1)) and
		(JsPath \ "action").read[String]
	)(SubscribeMessage.apply _)
}

object UnsubscribeMessage {
	implicit val UnsubscribeMessageWrites: Reads[UnsubscribeMessage] = (
		(JsPath \ "repository").read[String] and
		(JsPath \ "action").read[String]
	)(UnsubscribeMessage.apply _)
}
