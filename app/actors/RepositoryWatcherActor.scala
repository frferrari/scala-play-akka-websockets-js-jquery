package actors

import javax.inject._

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Logger
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import akka.actor._

import websockets.IncomingEvent
import services.RepositoryQuery

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

			val incomingEventResult = js.validate[IncomingEvent]

			incomingEventResult.fold(

				errors => {
					outChannel ! Json.obj("type" -> "error", "msg" -> "command rejected")
				},

				incomingEvent => {

					incomingEvent.action match {
						case "subscribe" if incomingEvent.repository.contains("/") => 
							subscribeRepository(incomingEvent.repository, incomingEvent.interval.get, self) match {
								case Success(_) => 
									outChannel ! Json.obj("type" -> "info", "msg" -> s"subscription started for repository ${incomingEvent.repository} interval ${incomingEvent.interval.get}")

								case Failure(_) => 
									outChannel ! Json.obj("type" -> "error", "msg" -> s"subscription failed or already subscribed ${incomingEvent.repository}")
							}

						case "subscribe" => 
							subscribeRepositories(incomingEvent.repository, incomingEvent.interval.get, self) map {
								case c: Long if c > 0 => 
									outChannel ! Json.obj("type" -> "info", "msg" -> s"subscription started for ${incomingEvent.repository} repositories at interval ${incomingEvent.interval.get}")

								case _ => 
									outChannel ! Json.obj("type" -> "error", "msg" -> s"subscription failed or already subscribed for ${incomingEvent.repository}")
							}

						case "unsubscribe" =>
							unsubscribe(incomingEvent.repository) match {
								case Success(_) =>
									outChannel ! Json.obj("type" -> "info", "msg" -> s"unsubscribe done for repository ${incomingEvent.repository}")

								case Failure(f) => 
									outChannel ! Json.obj("type" -> "error", "msg" -> s"can't unsubscribe repository ${incomingEvent.repository}")
							}
					}
				}
			)

		case r: Refresh => {
			Logger.debug(s"Processing Refresh event for interval ${r.interval}")

			// Update the stargazersCount and send the counters back to the web client
			subscriptionList.get(r.interval) match {
				case Some((cancellable, repositoryList)) =>

					repositoryQuery.queryUserRepositories(repositoryList.toList).onComplete {

						case Success(countByRepo) => {
							countByRepo.foreach {
								case (repo, Right(stargazersCount)) => {
									Logger.debug(s"Refreshing repo $repo with count $stargazersCount")
									outChannel ! Json.obj("type" -> "refresh", "repo" -> repo, "count" -> stargazersCount)
								}
								case (repo, Left(e)) => {
									outChannel ! Json.obj("type" -> "error", "msg" -> s"$repo query $e")
								}
							}
						}

						case Failure(f) => {
							outChannel ! Json.obj("type" -> "error", "msg" -> "Failure while querying repositories")
						}
					}

					case None =>
						Logger.error(s"Can't refresh interval ${r.interval} (no map entry)")
			}

			outChannel ! Json.obj("type" -> "info", "msg" -> s"tick for interval ${r.interval}")
		}
	}

	/*
	 *
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
						val cancellable = system.scheduler.schedule(2000 milliseconds, (interval*1000) milliseconds, repositoryWatcherActor, Refresh(interval))
						subscriptionList(interval) = (cancellable, scala.collection.mutable.ListBuffer(repository))
				}
				false
			}
		}
	})

	/*
	 *
	 */
	def subscribeRepositories(user: String, interval: Int, repositoryWatcherActor: ActorRef)(implicit system: ActorSystem): Future[Long] = {

		repositoryQuery.listUserRepositories(user).map {
			case repositoryList: List[String] =>
				repositoryList.foreach( r => subscribeRepository(r, interval, repositoryWatcherActor)(system) )
				repositoryList.length
		}
	}

	/*
	 *
	 */
	def unsubscribe(repository: String): Try[Boolean] = Try({

		subscriptionList.foreach {
			case (interval, (cancellable, repositoryList)) =>
				if ( repositoryList.contains(repository) ) {
					// If the list contains only one element then we can 
					// 		- cancel the Cancellable
					//		- remove the Map entry for the current interval
					if ( repositoryList.size == 1 ) {
						cancellable.cancel()
						subscriptionList.remove(interval)
					} else {
						subscriptionList(interval) = (cancellable, repositoryList -= repository)
					}
				}
		}

		false
	})
}
