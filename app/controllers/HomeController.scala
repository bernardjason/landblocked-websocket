package controllers

import akka.actor.{ActorSystem, _}
import akka.stream.Materializer
import javax.inject.{Inject, _}
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.collection.JavaConversions._

@Singleton
class HomeController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, assetsFinder: AssetsFinder) extends AbstractController(cc) {

  def socket = WebSocket.accept[String, String] { request =>

    ActorFlow.actorRef { out =>
      MyWebSocketActor.props(out)
    }
  }
  def players = Action {
    val allPlayers = GameObject.runningGame.values().map(
      g => g.playerIds.toList
    )

    Ok(Json.toJson(allPlayers.flatten))
  }

  def index = Action {
    Ok(views.html.index("landblocked is ready."))
  }
}







