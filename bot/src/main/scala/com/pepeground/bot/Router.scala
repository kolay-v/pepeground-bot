package com.pepeground.bot

import cats.instances.future._
import com.pepeground.bot.handlers._
import com.bot4s.telegram.Implicits._
import com.bot4s.telegram.api._
import com.bot4s.telegram.methods._
import com.bot4s.telegram.models._
import com.bot4s.telegram.future._
import com.bot4s.telegram.api.declarative.{Commands }
import com.bot4s.telegram.clients.FutureSttpClient
import sttp.client3.SttpBackend
import sttp.client3.okhttp.OkHttpFutureBackend
import com.bot4s.telegram.api.RequestHandler
import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import com.typesafe.scalalogging._
import org.slf4j.LoggerFactory
import scalikejdbc._

class Router
    extends TelegramBot
    with Polling
    with Commands[Future] {
  def token = Config.bot.telegramToken
    implicit val backend                        = OkHttpFutureBackend()
  override val client: RequestHandler[Future] = new FutureSttpClient(token)

  override val logger = Logger(LoggerFactory.getLogger(this.getClass))
  private val botName = Config.bot.name.toLowerCase

  override def receiveMessage(msg: Message): Future[Unit] = {
    DB localTx { implicit session =>
      Try(processMessage(msg)) match {
        case Success(_: Unit) => super.receiveMessage(msg)
        case Failure(e: Throwable) => throw e
      }
    }
  }

  private def processMessage(msg: Message)(implicit session: DBSession): Unit = {
    for (text <- msg.text) cleanCmd(text) match {
      case c if expectedCmd(c, "/repost") => RepostHandler(msg).call() match {
        case Some(s: ForwardMessage) =>
          request(s) onComplete {
            case Success(_) => makeResponse(text, SendMessage(msg.source, "reposted", replyToMessageId = msg.messageId))
            case Failure(_) =>
          }

        case None =>
      }
      case c if expectedCmd(c, "/get_stats") => GetStatsHandler(msg).call() match {
        case Some(s: String) =>  makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
        case None =>
      }
      case c if expectedCmd(c, "/cool_story") => CoolStoryHandler(msg).call() match {
        case Some(s: String) => makeResponse(text, SendMessage(msg.source, s))
        case None =>
      }
      case c if expectedCmd(c, "/set_gab") =>
        val level: Option[Int] = text.split(" ").take(2) match {
          case Array(_, randomLevel) => Try(randomLevel.toInt).toOption match {
            case Some(l: Int) => Some(l)
            case None => None
          }
          case _ => None
        }

        level match {
          case Some(l: Int) =>
            SetGabHandler(msg).call(l) match {
              case Some(s: String) => makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
              case None =>
            }
          case None => makeResponse(text, SendMessage(msg.source, "Wrong percent", replyToMessageId = msg.messageId))
        }
      case c if expectedCmd(c, "/get_gab") => GetGabHandler(msg).call() match {
        case Some(s: String) => makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
        case None =>
      }
      case c if expectedCmd(c, "/ping") => PingHandler(msg).call() match {
        case Some(s: String) => makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
        case None =>
      }
      case c if expectedCmd(c, "/set_repost_channel") => {
        val chatUsername: Option[String] = msg.entities match {
          case Some(msgEntities: Seq[MessageEntity]) => {
            msgEntities.find {
              msgEntity: MessageEntity => msgEntity.`type` == "mention"
            } match {
              case Some(msgEntity: MessageEntity) => {
                val offset: Int = msgEntity.offset
                text.substring(offset, offset + msgEntity.length)
              }
              case None => None
            }
          }
          case _ => None
        }

        val chatMemberRequest: Option[Future[ChatMember]] = msg.from.flatMap {
          u: User => request(GetChatMember(ChatId(msg.chat.id), u.id.toInt))
        }

        chatUsername match {
          case Some(l: String) =>
            SetRepostChatHandler(msg, chatMemberRequest).call(l) match {
              case Some(s: String) => makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
              case None =>
            }
          case None => makeResponse(text, SendMessage(msg.source, "No chat username", replyToMessageId = msg.messageId))
        }
      }
      case c if expectedCmd(c, "/get_repost_channel") => GetRepostChatHandler(msg).call() match {
        case Some(s: String) => makeResponse(text, SendMessage(msg.source, s, replyToMessageId = msg.messageId))
        case None =>
      }
      case s =>
        if(!s.startsWith("/")) handleMessage(msg)
    }
  }

  private def cleanCmd(cmd: String): String = cmd.takeWhile(s => s != ' ' ).toLowerCase

  private def expectedCmd(cmd: String, expected: String): Boolean = {
    cmd.split("@") match {
      case Array(c: String, name: String) => c == expected && name.toLowerCase == botName
      case Array(c: String) => c == expected
      case _ => false
    }
  }

  private def handleMessage(msg: Message)(implicit session: DBSession): Unit = {
    MessageHandler(msg).call() match {
      case Some(res: Either[Option[String], Option[String]]) => res match {
        case Left(s: Option[String]) => if(s.nonEmpty) makeResponse("message", SendMessage(msg.source, s.get, replyToMessageId = msg.messageId))
        case Right(s: Option[String]) => if(s.nonEmpty) makeResponse("message", SendMessage(msg.source, s.get))
      }
      case _ =>
    }
  }

  private def makeResponse(context: String, msg: SendMessage): Unit = {
    logger.info("Response for %s, with text: %s".format(context, msg.text))
    request(msg)
  }
}
