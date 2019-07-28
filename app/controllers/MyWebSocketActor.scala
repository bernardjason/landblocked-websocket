package controllers

import akka.actor.{Actor, ActorRef, Props, _}
import play.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}


case class Vector3(x: Float, y: Float, z: Float)

case class Matrix4(values: Array[Float])

case class Login(prefix: String, name: String, game: String, var hellosReceived: Int = 0)

object Login {
  def apply(prefix: String, name: String, game: String): Login = new Login(prefix, name, game)
}


case class Message(id: String, msg: String, full: String) {
  val ttl = System.currentTimeMillis() + 200
}

object MyWebSocketActor {
  def props(out: ActorRef)(implicit ec: ExecutionContext) = Props(new MyWebSocketActor(out))
}

class MyWebSocketActor(out: ActorRef)(implicit ec: ExecutionContext) extends Actor {

  implicit val loginReads: Reads[Login] = (
    (JsPath \ "prefix").read[String] and
      (JsPath \ "name").read[String] and
      (JsPath \ "game").read[String]
    ) (Login.apply _)

  var login: Option[Login] = None
  var gameName: String = null
  var game: Game = null
  var end = false
  var oktoSend = false
  val UNCHANGED = 0
  val CHANGED = 2

  var ticker = 0L

  val timeTaken: ArrayBuffer[Long] = ArrayBuffer()

  var skippedCommon = 0
  var commonSent = 0

  def logEntries = {
    ticker = ticker + 1
    if (ticker % 320 == 0) {
      Logger.info("-----------------------------------------------------")
      Logger.info(s"login info ${login}")
      Logger.info(s"Objects commonObjects=${game.commonObjectIds.size()} entries=${game.entries.size()} messages=${game.messages.size} remove=${game.remove.size} sentMessages=${game.sentMessages.size()}")
      for (o <- game.entries.values()) {
        Logger.info(o.id + " " + o.what + " " + o.state + " " + o.full)
      }
      val avg = timeTaken.foldLeft(0L)(_ + _).toFloat / timeTaken.length.toFloat
      //Logger.info("Average " + timeTaken.toString() )
      Logger.info("Average to send is " + avg + " " + game.entries.size)
      timeTaken.clear()
      for (g <- game.commonObjectIds.values) {
        if (g.state == "ALIVE" && g.changed > UNCHANGED) {
          println(g.full)
        }
      }
      Logger.info(s"skippedCommon=${skippedCommon} commonSent=${commonSent}")
      if (game.created + GameObject.GAME_TIME < System.currentTimeMillis()) {
        Logger.info("******* Game Over ********************")
      }
      Logger.info("-----------------------------------------------------")
    }
  }

  def receive = {
    case stringMessage: String =>
      val json: JsValue = Json.parse(stringMessage)
      if (stringMessage.contains("HELLO")) {
        oktoSend = true
        login.get.hellosReceived = login.get.hellosReceived + 1
        sendToClient
      } else if (login.isEmpty) {
        json.validate[Login] match {
          case s: JsSuccess[Login] => {
            val l: Login = s.get
            login = Some(l)
            game = GameObject.newGame(login.get.game)
            gameName = login.get.game
            Logger.info("LOGGED IN " + " " + login + " " + s"game is ${login.get.game}" + game)
            game.playerIds.add(l.prefix)

            /*
            sendMessagesToPlayer onComplete {
              case Success(ok) => Logger.info("thread complete ")
              case Failure(t) => Logger.warn("thread error: " + t.getMessage)
            }
             */
          }
          case e: JsError => {
            Logger.info("ERROR " + e)
          }
        }
      } else {
        if (stringMessage.contains("msg")) {
          val msg = json("msg").as[String]
          val id = json("id").as[String]


          if (game.sentMessages.get(id) == null) {
            if (id == "0") {
              for (prefix <- game.playerIds) {
                val genId = s"${prefix}_${id}"
                val m = Message(genId, msg, stringMessage)
                game.sentMessages.put(genId, m)
                game.messages.add(m)
                Logger.debug("BROADCAST RECEIVED MESSAGE " + genId + " " + m + "  " + stringMessage)

              }
            } else {
              val m = Message(id, msg, stringMessage)
              game.sentMessages.put(id, m)
              game.messages.add(m)
              Logger.debug("GOT A MESSAGE " + msg + "  " + stringMessage)
            }
          } else {
            Logger.debug("IGNORE DOUBLE SEND GOT A MESSAGE " + msg + "  " + stringMessage)
          }
        } else {
          val what = json("what").as[String]
          val id = json("id").as[String]
          val state = json("state").as[String]
          val changed = json("changed").as[Int]
          val g = GameObject(what, id, state, changed, stringMessage)
          if (id.startsWith("C")) {
            game.commonObjectIds.put(id, g)
            /*
            if (state == "DEAD") game.commonObjectIds.put(id, g)
            else if (!game.commonObjectIds.contains(id)) game.commonObjectIds.put(id, g)
             */
          }

          if (id.startsWith(login.get.prefix) || state == "DEAD") {
            val existsAlready = game.entries.get(id)
            if (existsAlready == null || existsAlready.state != "DEAD") {
              game.entries.put(id, g)
            }
          }
        }
      }

      shouldIStopGame
  }

  def shouldIStopGame = {
    if (game.created + GameObject.GAME_TIME < System.currentTimeMillis()) {
      for (prefix <- game.playerIds) {
        val genId = s"${prefix}_9"
        val m = Message(genId, "{\"id\":\"0\",\"msg\":\"GAME_OVER\"}", "{\"id\":\"0\",\"msg\":\"GAME_OVER\"}")
        game.sentMessages.put(genId, m)
        game.messages.add(m)
        Logger.debug("BROADCAST MESSAGE " + genId + " " + m)
      }
    }
  }

  private def sendMessagesToPlayer = {
    Future {
      Logger.info("Running asynchronously on another thread")
      for (g <- game.commonObjectIds.values) {
        out ! g.full
      }
      while (end == false) {

        for (waitAWhile <- 0 to 3 if !oktoSend && !end) {
          Thread.sleep(5)
        }
        if (!oktoSend) {
          Logger.warn("DID NOT RECEIVE HELLO FROM " + login)
        }
        oktoSend = false
        sendToClient
      }
      Logger.info("Send message thread complete!!!!")
    }
  }

  private def sendToClient = {
    val now = System.currentTimeMillis()

    GameObject.ttl = now

    shouldIStopGame

    skippedCommon = 0
    commonSent = 0
    for (g <- game.commonObjectIds.values) {
      if (g.state == "ALIVE" && g.changed >= CHANGED) {
        out ! g.full
        commonSent = commonSent + 1
      } else {
        skippedCommon = skippedCommon + 1
      }
    }

    for (g <- game.entries.values()) {
      if (g.state == "DEAD" || g.id.startsWith(login.get.prefix) == false) {
        if (g.state == "DEAD") {
          Logger.debug(login.get.prefix + " SEND " + g.ttl + " " + g.full)
        }
        out ! g.full

        if (g.state == "DEAD") {
          g.timeToDie
          game.remove.put(g.id, g)
        }
      }
    }
    for (g <- game.remove.values()) {
      if (g.ttl < GameObject.ttl) {
        Logger.debug("REMOVE **" + " " + g.state + " " + "** " + " " + g)
        out ! g.full
        game.entries.remove(g.id)
        if ((g.id.startsWith("C"))) {
          // make sure we keep dead ones forever
          game.commonObjectIds.put(g.id, g)
        }
      }
    }
    game.remove.clear()
    for (msg <- game.messages.toList) {
      if (msg.id == "0" || msg.id.startsWith(login.get.prefix)) {
        Logger.debug("SEND " + login.get.prefix + " " + msg.id + " " + msg.full)
        out ! msg.full

        val was = game.messages.size()
        game.messages.removeIf(m => m.id == msg.id)
        //game.messages.remove(msg)
      } else {
        //Logger.debug("WONT SEND TO " + login.get.prefix + " " + msg.id + " " + msg.full)
      }
    }
    val time = System.currentTimeMillis()
    if (game.sentMessages.size() > 0) {
      for (k <- game.sentMessages.keys().toArray) {
        val m = game.sentMessages.get(k)
        if (m != null && m.ttl < time) {
          game.sentMessages.remove(k)
        }
      }
    }
    timeTaken += (System.currentTimeMillis() - now)
    //Thread.sleep(10)
    logEntries
  }

  private def cleanupOnClose = {
    end = true;
    Logger.info("---------------------  Starting to logged off ---------------------------------")
    for (g <- game.entries.values()) {
      if (g.id.startsWith(login.get.prefix)) {
        val remove = g.copy(state = "DEAD", full = g.full.replace("ALIVE", "DEAD"))
        g.timeToDie
        game.remove.put(remove.id, remove)
        Logger.info("FINISHED now " + remove)
      }
    }
    game.playerIds.remove(login.get.prefix)
    if (game.playerIds.size() == 0) {
      GameObject.runningGame.remove(gameName)
      Logger.info("No players so remove game");
    }
    Logger.info("Logged off so finish")
    Logger.info("Player list is now " + game.playerIds.toList)
    Logger.info("Games list  now " + GameObject.runningGame.keySet())
  }

  override def postStop() = {
    Logger.info("CLOSED!!!!!!!!!!!!!" + " " + login)
    end = true
    cleanupOnClose
  }
}
