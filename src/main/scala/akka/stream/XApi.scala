package akka.stream

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.stream.impl.Ast.{StageFactory, AstNode, JunctionAstNode}
import akka.stream.impl.{ActorFlowMaterializerImpl, Ast, FlowNameCounter, StreamSupervisor}
import akka.stream.scaladsl.{Key, MaterializedMap, Sink, Source}
import akka.stream.stage.{Context, PushStage}
import akka.util.Timeout
import casino.spray.actor.{RouteBuilder, SprayServer}
import org.reactivestreams.Processor
import spray.json._
import spray.routing._

trait XJsonFormat extends DefaultJsonProtocol {
  implicit val nodeFormat = jsonFormat2(Node)
  implicit val edgeFormat = jsonFormat3(Edge)
  implicit val graphFormat = jsonFormat2(Graph)
  implicit val actorInterpreterMetricsFormat = new JsonFormat[ActorInterpreterMetrics] {
    override def write(obj: ActorInterpreterMetrics) =
      JsObject(
        "demand" -> JsNumber(obj.downstreamDemand()),
        "buffer" -> JsNumber(obj.upstreamInputBuffer())
      )
    override def read(json: JsValue) = ???
  }
  implicit val graphStateFormat = jsonFormat1(GraphsState)
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
            (meter ? XMeter.QueryGraph).mapTo[Graph]
          }
        }
      } ~
      path("graph-state") {
        get {
          complete {
            (meter ? XMeter.QueryGraphState).mapTo[GraphsState]
          }
        }
      }
    }
  }
  override def routeBuilder = this
}

object XRay {
  def apply()(implicit system: ActorSystem) = new XRay()
}

class NotificationStage(meter: ActorRef, xf: XFlow) extends PushStage[Any, Any] {
  override def onPush(elem: Any, ctx: Context[Any]) = ctx.push(elem)

  override def onUpstreamFinish(ctx: Context[Any]) = {
    meter ! XFlowFinished(xf)
    super.onUpstreamFinish(ctx)
  }

  override def onDownstreamFinish(ctx: Context[Any]) = {
    meter ! XFlowFinished(xf)
    super.onDownstreamFinish(ctx)
  }

  override def onUpstreamFailure(cause: Throwable, ctx: Context[Any]) = {
    meter ! XFlowFinished(xf)
    super.onUpstreamFailure(cause, ctx)
  }
}

class XRay private ()(implicit system: ActorSystem) {
  val meter = system.actorOf(Props[XMeter], "meter")
  val api = system.actorOf(Props(classOf[XRayApi], meter), "api")
  def materializer() = {
    val settings = ActorFlowMaterializerSettings(system)
    new ActorFlowMaterializerImpl(
      settings,
      system.dispatchers,
      system.actorOf(StreamSupervisor.props(settings).withDispatcher(settings.dispatcher)),
      FlowNameCounter(system).counter,
      "flow") {

      override def materialize[In, Out](source: Source[In], sink: Sink[Out], rawOps: List[AstNode], keys: List[Key[_]]) = {
        val xf = new XFlow(rawOps, source, sink)
        meter ! xf
        val result = super.materialize(source, sink, StageFactory(() => new NotificationStage(meter, xf)) +: rawOps, keys)
        result
      }

      override private[akka] def processorForNode[In, Out](op: AstNode, flowName: String, n: Int) = op match  {
        case Ast.DirectProcessor(p, _) ⇒ (p().asInstanceOf[Processor[In, Out]], MaterializedMap.empty)
        case Ast.DirectProcessorWithKey(p, key, _) ⇒
          val (processor, value) = p()
          (processor.asInstanceOf[Processor[In, Out]], MaterializedMap.empty.updated(key, value))
        case _ ⇒
          val name = s"$flowName-$n-${op.attributes.name}"
          val props = XActorProcessorFactory.props(this, op, name)
          (XActorProcessorFactory[In, Out](actorOf(props, name, op)), MaterializedMap.empty)
      }

      override def materializeJunction[In, Out](op: JunctionAstNode, inputCount: Int, outputCount: Int) = {
        val result = super.materializeJunction[In, Out](op, inputCount, outputCount)
        meter ! new XJunction(op, result._1, result._2)
        result
      }
    }

  }
}