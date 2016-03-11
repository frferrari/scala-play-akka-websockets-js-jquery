package websockets

import javax.inject._

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.data.validation.ValidationError

case class IncomingEvent(action: String, repository: String, interval: Option[Int])

object IncomingEvent {
	implicit val incomingEventReads: Reads[IncomingEvent] = (
		(JsPath \ "action").read[String](IncomingEventJsonValidation.validateAction) and
		(JsPath \ "repository").read[String] and
		IncomingEventJsonValidation.validateInterval
	)(IncomingEvent.apply _)
}

object IncomingEventJsonValidation {
	/*
	 * Validates an action
	 *
	 * Must be "subscribe" or "unsubscribe"
	 */
	def validateAction(implicit r: Reads[String]): Reads[String] = {
		r.filter(ValidationError("error.action.unknown"))(action => List("subscribe", "unsubscribe").contains(action))
	}

	/*
	 * Validates an interval which is :
	 *
	 * - mandatory when the action is "subscribe"
	 */
	def validateInterval = (
		(JsPath \ "action").read[String] and
		(JsPath \ "interval").readNullable[Int]
	).tupled
	.filter(ValidationError("error.interval.missing")) { 
		case (action, interval) if action == "subscribe" && interval.isEmpty => false
		case (action, interval) if action == "subscribe" && interval.isDefined => if (interval.get > 0) true else false
		case _ => true
	}
	.map(t => t._2) // Keep only the interval
}
