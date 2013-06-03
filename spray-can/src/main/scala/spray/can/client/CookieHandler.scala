package spray.can.client

import spray.io._
import akka.actor.ActorRef
import spray.can.Http
import spray.http._
import spray.can.rendering.HttpRequestPartRenderingContext
import java.net.{ CookieManager, HttpCookie ⇒ JCookie }
import scala.collection.immutable.Queue
import scala.collection.JavaConverters._
import spray.http.parser.HttpParser

object CookieHandler {
  def apply(): PipelineStage = {
    val cookieJar = new CookieManager().getCookieStore() //gotta use the store directly cause CookieManager is busted in SE6

    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines {
          import context.log

          var uriQueue = Queue.empty[Uri]
          val emptyJavaMap = Map.empty[String, List[String]].mapValues(_.asJava).asJava

          val commandPipeline: CPL = {
            case cmd @ HttpRequestPartRenderingContext(x: HttpRequest, ack) ⇒
              val currentUri = x.uri
              uriQueue = uriQueue enqueue currentUri

              val cookieList = cookieJar.get(currentUri.toJUri).asScala.toList.map { javaCookie ⇒
                HttpHeaders.RawHeader("Cookie", javaCookie.toString)
              }

              val (erroredHeaders,parsedHeaders) = HttpParser.parseHeaders(cookieList)
              if (erroredHeaders.size > 0) {
                log.warning("Error parsing the following cookies in the jar: {}", erroredHeaders)
              }

              val cookiedRequest = x.withHeaders(x.headers ++ parsedHeaders)

              commandPL(HttpRequestPartRenderingContext(cookiedRequest, ack))

            case cmd ⇒ commandPL(cmd)
          }

          val eventPipeline: EPL = {
            case ev @ Http.MessageEvent(x: HttpResponse) ⇒
              val currentUri = uriQueue.head
              uriQueue = uriQueue.tail

              val cookiesCombined = "set-cookie2: " + (x.headers.collect {
                case c: HttpHeaders.`Set-Cookie` ⇒ c.value
              }.mkString(","))

              JCookie.parse(cookiesCombined).asScala.foreach { cookie ⇒
                cookieJar.add(currentUri.toJUri, cookie)
              }

              eventPL(ev)

            case ev ⇒ eventPL(ev)
          }

          def dispatch(receiver: ActorRef, msg: Any): Unit =
            commandPL(Pipeline.Tell(receiver, msg, context.self))
        }
    }
  }
}
