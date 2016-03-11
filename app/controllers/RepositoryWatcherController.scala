package controllers

import javax.inject._

import play.api.mvc._
import play.api.libs.json._
import play.api.Play.current
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Action, Controller, WebSocket}
import play.api.libs.ws.WSClient

import scala.concurrent.Future

import akka.actor._
import akka.stream.Materializer

import actors.RepositoryWatcherActor

/*
 * Help was found here
 *
 * https://github.com/JAVEO/clustered-chat/blob/master/app
 */

@Singleton
class RepositoryWatcherController @Inject()(system: ActorSystem, mat: Materializer, ws: WSClient) extends Controller {

	implicit val implicitMaterializer: Materializer = mat
	implicit val implicitActorSystem: ActorSystem = system
	implicit val implicitWSClient: WSClient = ws

	def index = Action {
		Ok(views.html.index(""))
	}

	def websocket = WebSocket.acceptOrResult[JsValue, JsValue] { implicit request =>
		println("starting RepositoryWatcherActor")

		Future.successful(Right(ActorFlow.actorRef(RepositoryWatcherActor.props)))
	}  
}
