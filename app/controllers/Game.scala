package controllers

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

case class Game(
                 playerIds: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String](),
                 entries: ConcurrentHashMap[String, GameObject] = new ConcurrentHashMap[String, GameObject],
                 remove: ConcurrentHashMap[String, GameObject] = new ConcurrentHashMap[String, GameObject],
                 commonObjectIds:ConcurrentHashMap[String, GameObject] = new ConcurrentHashMap[String, GameObject],
                 created: Long = System.currentTimeMillis(),
                 messages: ConcurrentLinkedQueue[Message] = new ConcurrentLinkedQueue[Message](),
                 sentMessages: ConcurrentHashMap[String, Message] = new ConcurrentHashMap[String, Message](),
               )
