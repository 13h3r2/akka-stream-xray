package akka.stream


import akka.actor.{ActorRef, ActorRefFactory}
import akka.util.Timeout
import casino.spray.actor.{RouteBuilder, SprayServer}
import spray.json._
import spray.routing._

trait XJsonFormat extends DefaultJsonProtocol {
  implicit val nodeFormat = jsonFormat2(Node)
  implicit val edgeFormat = jsonFormat3(Edge)
  implicit val graphFormat = jsonFormat2(GraphShape)
//  implicit val actorInterpreterMetricsFormat = new JsonFormat[ActorInterpreterMetrics] {
//    override def write(obj: ActorInterpreterMetrics) =
//      JsObject(
//        "demand" -> JsNumber(obj.downstreamDemand()),
//        "buffer" -> JsNumber(obj.upstreamInputBuffer())
//      )
//    override def read(json: JsValue) = ???
//  }
//  implicit val graphStateFormat = jsonFormat1(GraphState)
}

class XRayApi(meter: ActorRef) extends RouteBuilder with SprayServer with XJsonFormat {
  override def route(implicit arf: ActorRefFactory, settings: RoutingSettings, timeout: Timeout): Route = {
    implicit val ec = arf.dispatcher
    import akka.pattern.ask
    import casino.spray.api.Directives._
    import spray.httpx.SprayJsonSupport._
    withCorsHeaders {
      path("graph") {
        get {
          complete {
            (meter ? XMeter.QueryGraph).mapTo[GraphShape]
          }
        }
      } ~
      path("graph-state") {
        get {
          complete { "{}"
//            (meter ? XMeter.QueryGraphState).mapTo[GraphsState]
          }
        }
      }
    }
  }
  override def routeBuilder = this
}
