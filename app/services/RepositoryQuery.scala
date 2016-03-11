package services

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.http.HttpEntity

import scala.concurrent.Future
import scala.concurrent.duration._

class RepositoryQuery {

	/*
	 * List the github user repositories given a user name
	 *
	 * The returned list contains strings of type user/repository
	 * ex: List("frferrari/tmepic1", "frferrari/tmepic2", "frferrari/tmepic3")
	 *
	 * Nil is returned when an error occurred
	 */
	def listUserRepositories(user: String)(implicit ws: WSClient): Future[List[String]] = {
		Logger.debug(s"Querying github repositories for user $user")

		val userRepositories = ws.url("https://api.github.com/search/repositories")
			.withHeaders("Accept" -> "application/json")
			.withQueryString("q" -> s"user:frferrari")
			.withRequestTimeout(10000.millis)
			.get()

		userRepositories.map { response =>
			response.status match {
				case 200 	=> (response.json \ "items" \\ "full_name").map(_.as[String]).toList
				case _ 		=> Nil
			}
		}
	}

	/*
	 * List the stargazers_count for a list of repositories
	 * The list of repositories must contain strings of type user/repository
	 * ex: List("frferrari/tmepic1", "frferrari/tmepic2", "frferrari/tmepic3")
	 */
	def queryUserRepositories(repositories: List[String])(implicit ws: WSClient): Future[Map[String, Option[Long]]] = {
		/* 
		 * See Composing futures here :
		 *		
		 * http://doc.akka.io/docs/akka/snapshot/scala/futures.html
		 */
		Future.traverse(repositories)(queryUserRepository).map(_.toMap)
	}

	/*
	 * Retrieve the stargazers_count for a given repository. The repository is a string
	 * of type user/repository (ex: frferrari/tmepic3)
	 *
	 * The stargazers_count can be :
	 * - Option(startgazers_count) 	when successfully retrieved
	 * - None 											when the count failed to be retrieved
	 */
	def queryUserRepository(repository: String)(implicit ws: WSClient): Future[(String, Option[Long])] = {
		Logger.debug(s"Querying github repository ${repository}")

		val userRepository = ws.url("https://api.github.com/search/repositories")
			.withHeaders("Accept" -> "application/json")
			.withQueryString("q" -> s"repo:${repository}")
			.withRequestTimeout(10000.millis)
			.get()

		userRepository.map { response => 
			response.status match {
				case 200 	=> (repository, (response.json \\ "stargazers_count")(0).asOpt[Long])
				case _ 		=> (repository, None)
			}
		}
	}
}
