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

	case class Refresh(repository: String, interval: Int)
}

class RepositoryWatcherActor(outChannel: ActorRef)(implicit ws: WSClient) extends Actor {
	import RepositoryWatcherActor.Refresh

	implicit val actorSystem = context.system
	val repositoryQuery = new RepositoryQuery()

	var subscriptionList = scala.collection.mutable.Map[String, (Long, Cancellable)]()

	def receive = {

		case js: JsValue => 

			val incomingEventResult = js.validate[IncomingEvent]

			incomingEventResult.fold(

				errors => {
					outChannel ! Json.obj("type" -> "error", "msg" -> "command rejected")
				},

				incomingEvent => {

					incomingEvent.action match {
						case "subscribe" => 
							subscribe(incomingEvent.repository, incomingEvent.interval.get, self) match {
								case Success(_) => 
									outChannel ! Json.obj("type" -> "info", "msg" -> s"subscription started for repository ${incomingEvent.repository} interval ${incomingEvent.interval.get}")

								case Failure(_) => 
									outChannel ! Json.obj("type" -> "error", "msg" -> "subscription failed or already subscribed")
							}

						case "unsubscribe" =>
							unsubscribe(incomingEvent.repository) match {
								case Success(_) =>
									outChannel ! Json.obj("type" -> "info", "msg" -> "unsubscribe done")
									refreshClient

								case Failure(_) => 
									outChannel ! Json.obj("type" -> "error", "msg" -> "can't unsubscribe")
							}
					}
				}
			)

		case r: Refresh => {
			Logger.debug(s"Processing Refresh event for repository ${r.repository}")

			// Retrieve the stargazersCount for the repo to Refresh
			/* You could use the following code to update a list of repositories for a given user
			val futureCountByRepo = for {
				repositories <- repositoryQuery.listUserRepositories("frferrari")
				stargazersCountByRepo <- repositoryQuery.queryUserRepositories(repositories)
			} yield (stargazersCountByRepo)

			OR for a single repository

			val futureCountByRepo = for {
				stargazersCountByRepo <- repositoryQuery.queryUserRepositories(List(r.repository))
			} yield (stargazersCountByRepo)
			*/

			// Update the stargazersCount and send ALL the counters back to the web client
			repositoryQuery.queryUserRepositories(List(r.repository)).onComplete {
				case Success(countByRepo) => {
					countByRepo.collect {
						case (repo, Some(stargazersCount)) => {
							Logger.debug(s"Refreshing repo $repo with count $stargazersCount")
							updateStargazersCount(repo, stargazersCount)
						}
					}

					refreshClient
				}

				case Failure(f) => {
					outChannel ! Json.obj("type" -> "error", "msg" -> "Failure")
				}
			}

			outChannel ! Json.obj("type" -> "info", "msg" -> s"tick for repository ${r.repository}")
		}
	}

	/*
	 *
	 */
	def subscribe(repository: String, interval: Int, repositoryWatcherActor: ActorRef)(implicit system: ActorSystem): Try[Boolean] = Try({

		subscriptionList.contains(repository) match {			
			case true => 
				throw new Exception("Already subscribed")

			case false => {
				val cancellable = system.scheduler.schedule(0 milliseconds, (interval*1000) milliseconds, repositoryWatcherActor, Refresh(repository, interval))
				subscriptionList += (repository -> (interval, cancellable))
				false
			}
		}
	})

	/*
	 *
	 */
	def unsubscribe(repository: String): Try[Boolean] = Try({

		subscriptionList.contains(repository) match {
			case false => 
				throw new Exception("You haven't subscribed to this repository, can't unsubscribe")

			case true => {
				val cancellable = subscriptionList(repository)._2
				cancellable.cancel()

				subscriptionList -= repository				
				false
			}
		}
	})

	/*
	 *
	 */
	def updateStargazersCount(repository: String, stargazersCount: Long) = {
		subscriptionList.get(repository) match {

			case Some((_, cancellable)) => 
				subscriptionList(repository) = (stargazersCount, cancellable)

			case None =>
				Logger.error(s"updateStargazersCount($repository) Could not update the stargazersCount")
		}
	}

	/*
	 * Sends the whole list of subscribed repositories to the client
	 */
	def refreshClient = {
		val jsStargazersCount = Json.toJson( subscriptionList.map { 
			case(repo, (stargazersCount, c)) => (repo -> stargazersCount)
		})

		outChannel ! Json.obj("type" -> "refresh", "counts" -> jsStargazersCount)		
	}
}
