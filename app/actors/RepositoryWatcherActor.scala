package actors

import javax.inject._

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.WSClient
import play.api.mvc.WebSocket.FrameFormatter
import play.api.Logger

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import akka.actor._

import services.RepositoryQuery

import websockets.{IncomingMessage, SubscribeMessage, UnsubscribeMessage}
import websockets.{RefreshMessage, InfoMessage, ErrorMessage}

object RepositoryWatcherActor {

	def props(outChannel: ActorRef)(implicit ws: WSClient) = Props(new RepositoryWatcherActor(outChannel)(ws))

	case class Refresh(interval: Int)
}

class RepositoryWatcherActor(outChannel: ActorRef)(implicit ws: WSClient) extends Actor {
	import RepositoryWatcherActor.Refresh

	implicit val actorSystem = context.system
	val repositoryQuery = new RepositoryQuery()

	// A map of type Map[interval, (interval_event_handle, List[repository])]
	var subscriptionList = scala.collection.mutable.Map[Long, (Cancellable, scala.collection.mutable.ListBuffer[String])]()

	def receive = {

		case js: JsValue =>

			incomingMessageFromJsValue(js) match {

				case Success(incomingMessageResult) => {

					incomingMessageResult.fold(

						errors => {
							Logger.error(s"Command rejected due to validation errors "+errors)
							outChannel ! Json.toJson(ErrorMessage("Command rejected due to validation error"))
						},

						incomingMessage => {

							incomingMessage match {
								case m: SubscribeMessage if m.repository.contains("/") => 
									subscribeRepository(m.repository, m.interval, self) match {
										case Success(_) => 
											outChannel ! Json.toJson(InfoMessage(s"Subscription started for repository ${m.repository} interval ${m.interval}"))

										case Failure(_) => 
											outChannel ! Json.toJson(ErrorMessage(s"Subscription failed or already subscribed ${m.repository}"))
									}

								case m: SubscribeMessage => 
									subscribeRepositories(m.repository, m.interval, self) map {
										case c: Long if c > 0 => 
											outChannel ! Json.toJson(InfoMessage(s"Subscription started for ${m.repository} repositories at interval ${m.interval}"))

										case _ => 
											outChannel ! Json.toJson(ErrorMessage(s"Subscription failed or already subscribed for ${m.repository}"))
									}

								case m: UnsubscribeMessage =>
									unsubscribe(m.repository) match {
										case Success(_) =>
											outChannel ! Json.toJson(InfoMessage(s"Unsubscribe done for repository ${m.repository}"))

										case Failure(f) => 
											outChannel ! Json.toJson(ErrorMessage(s"Can't unsubscribe repository ${m.repository}"))
									}
							}
						}
					)
				}

				case Failure(f) => {
					Logger.error("Incoming command couldn't be validated "+f)
					outChannel ! Json.toJson(ErrorMessage("Incoming command couldn't be validated"))
				}
			}
	
		case r: Refresh => {
			Logger.debug(s"Processing Refresh event for interval ${r.interval}")

			subscriptionList.get(r.interval) match {
				case Some((cancellable, repositoryList)) =>

					repositoryQuery.queryUserRepositories(repositoryList.toList).onComplete {

						case Success(countByRepo) => {
							countByRepo.foreach {
								case (repo, Right(stargazersCount)) => {
									Logger.debug(s"Refreshing repo $repo with count $stargazersCount")
									outChannel ! Json.toJson(RefreshMessage(repo, stargazersCount))
								}
								case (repo, Left(e)) => {
									outChannel ! Json.toJson(ErrorMessage(s"$repo query $e"))
								}
							}
						}

						case Failure(f) => {
							outChannel ! Json.toJson(ErrorMessage("Failed querying repositories"))
						}
					}

					case None => {
						Logger.error(s"Can't refresh interval ${r.interval} (no map entry)")
				}
			}

			outChannel ! Json.toJson(InfoMessage(s"tick for interval ${r.interval}"))
		}
	}

	/*
	 * Subscribes to a list of repositories
	 * Returns the count of subscriptions
	 */
	def subscribeRepositories(user: String, interval: Int, repositoryWatcherActor: ActorRef)(implicit system: ActorSystem): Future[Long] = {

		repositoryQuery.listUserRepositories(user).map {
			case repositoryList: List[String] =>
				repositoryList.foreach( r => subscribeRepository(r, interval, repositoryWatcherActor)(system) )
				repositoryList.length
		}
	}

	/*
	 * Subscribes to a repository
	 * Starts a ticker for the first repository subscription on a given interval
	 * Each interval has its own ticker (one ticker per interval)
	 */
	def subscribeRepository(repository: String, interval: Int, repositoryWatcherActor: ActorRef)(implicit system: ActorSystem): Try[Boolean] = Try({

		subscriptionList.values.flatMap(_._2).toList.contains(repository) match {
			case true => 
				throw new Exception("Already subscribed")

			case false => {
				subscriptionList.get(interval) match {
					case Some((cancellable, repositoryList)) => 
						subscriptionList(interval) = (cancellable, repositoryList += repository)

					case None =>
						val cancellable = system.scheduler.schedule(500 milliseconds, (interval*1000) milliseconds, repositoryWatcherActor, Refresh(interval))
						subscriptionList(interval) = (cancellable, scala.collection.mutable.ListBuffer(repository))
				}

				false
			}
		}
	})

	/*
	 * Unsubscribes from a repository
	 */
	def unsubscribe(repository: String): Try[Boolean] = Try({

		subscriptionList.foreach {
			case (interval, (cancellable, repositoryList)) =>
				if ( repositoryList.contains(repository) ) {
					// If the list contains only one element then we can 
					// 		- cancel the Cancellable
					//		- remove the Map entry for the current interval
					if ( repositoryList.size == 1 ) {
						Logger.info(s"Cancels the ticker for the ${repository} repository")
						cancellable.cancel()
						subscriptionList.remove(interval)
					} else {
						subscriptionList(interval) = (cancellable, repositoryList -= repository)
					}
				}
		}

		false
	})

	/*
	 * Validates a given json and returns a SubscribeMessage or an UnsubscribeMessage
	 */
	def incomingMessageFromJsValue(js: JsValue): Try[JsResult[IncomingMessage]] = Try({
		(js \ "action").asOpt[String] match {
			case Some(action) => {
				action match {
					case IncomingMessage.subscribeMessage => 
						js.validate[SubscribeMessage]

					case IncomingMessage.unsubscribeMessage => 
						js.validate[UnsubscribeMessage]

					case _ => 
						Logger.error(s"incomingMessageFromJsValue unknown action ${action}")
						throw new Exception(s"Action ${action} can't be processed")
				}
			}

			case _ =>
				Logger.error(s"incomingMessageFromJsValue action not found")
				throw new Exception("Action missing")
		}
	})
}
