package spray.can.client

import spray.io._
import akka.actor.ActorRef
import spray.can.Http
import spray.http._
import spray.can.rendering.HttpRequestPartRenderingContext
import scala.collection.immutable.Queue

object CookieHandler {
  def apply(): PipelineStage = {
    val cookieJar = new CookieJar

    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines {
          import context.log

          var currentUri: Uri = _

          val commandPipeline: CPL = {
            case cmd @ HttpRequestPartRenderingContext(x: HttpRequest, ack) ⇒
              currentUri = x.uri

              val cookiedRequest = cookieJar.get(currentUri) match {
                case Some(cookieList) ⇒
                  x.withHeaders(x.headers ++ List(HttpHeaders.`Cookie`(cookieList)))
                case None ⇒
                  x
              }

              commandPL(HttpRequestPartRenderingContext(cookiedRequest, ack))

            case cmd ⇒ commandPL(cmd)
          }

          val eventPipeline: EPL = {
            case ev @ Http.MessageEvent(x: HttpResponse) ⇒

              val collectedCookieList = x.headers.collect {
                case HttpHeaders.`Set-Cookie`(c) ⇒ c
              }

              cookieJar.put(currentUri, collectedCookieList)

              eventPL(ev)

            case ev ⇒ eventPL(ev)
          }
        }
    }
  }
}

class CookieJar {
  val jar = Map.empty[Uri, List[HttpCookie]]

  def put(u: Uri, c: HttpCookie) {
    this.put(u, List(c))
  }

  def put(u: Uri, lc: List[HttpCookie]) {
    val cookieList = this.get(u)
    cookieList map { oldList ⇒ jar + (u -> (lc :: oldList)) }
  }

  def get(u: Uri): Option[List[HttpCookie]] = {
    val optionOfFreshCookies = for {
      maybeCookies ← jar.get(u)
      freshCookies ← Some(maybeCookies.filter(_.secure))
    } yield freshCookies

    optionOfFreshCookies match {
      case Some(fcl: List[HttpCookie]) ⇒ jar + (u -> fcl)
      case None                        ⇒
    }

    optionOfFreshCookies
  }
}