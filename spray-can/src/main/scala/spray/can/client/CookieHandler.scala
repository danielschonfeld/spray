package spray.can.client

import spray.io._
import spray.can.Http
import spray.http._
import spray.can.rendering.{ RequestPartRenderingContext }

object CookieHandler {
  def apply(): PipelineStage = {
    val cookieJar = new CookieJar

    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines {
          import context.log
          var currentUri: Uri = _

          val commandPipeline: CPL = {
            case cmd @ RequestPartRenderingContext(x: HttpRequest, ack) ⇒
              currentUri = x.uri

              val cookiedRequest = cookieJar.get(currentUri) match {
                case Some(cookieList) ⇒
                  x.withHeaders(x.headers ++ List(HttpHeaders.`Cookie`(cookieList)))
                case None ⇒
                  x
              }

              commandPL(RequestPartRenderingContext(cookiedRequest, ack))

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

  private class CookieJar {
    val jar = Map.empty[Uri, List[HttpCookie]]

    def put(u: Uri, c: HttpCookie) {
      this.put(u, List(c))
    }

    def put(u: Uri, newList: List[HttpCookie]) {
      val cookieList = this.get(u)
      cookieList map { oldList ⇒ jar + (u -> (newList :: oldList)) }
    }

    def get(u: Uri): Option[List[HttpCookie]] =
      jar.get(u).map { staleCookielist ⇒
        val freshCookies = staleCookielist.filter(_.expires < Some(DateTime.now))
        jar + (u -> freshCookies)
        freshCookies
      }
  }
}

