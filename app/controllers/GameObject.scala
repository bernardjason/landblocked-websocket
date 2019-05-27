package controllers

import play.Logger

case class GameObject(what: String, id: String, var state: String, var changed:Int , full: String, var ttl: Long = -1L) {
  def timeToDie = {
    if (ttl < 0) ttl = GameObject.ttl + GameObject.FUTURE
  }
}

object GameObject {

  val FUTURE = 100L
  var ttl = System.currentTimeMillis()

  val runningGame: java.util.HashMap[String, Game] = new java.util.HashMap[String, Game]
  val GAME_TIME = 300 * 1000

  def newGame(name: String): Game = {
    this.synchronized {
      if (runningGame.get(name) == null) {
        runningGame.put(name, new Game())
      } else {
        val g = runningGame.get(name)
        if ( g.created + GAME_TIME < System.currentTimeMillis() ) {
          runningGame.put(name, new Game())
        }
      }
    }
    if (runningGame.get(name) == null) {
      Logger.info("NULL ILLEGAL")
      System.exit(0)
    }

    return runningGame.get(name)
  }

}
