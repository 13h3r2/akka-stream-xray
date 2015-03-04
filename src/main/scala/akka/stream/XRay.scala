package akka.stream

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.dispatch.Dispatchers
import akka.stream.actor.ActorSubscriber
import akka.stream.impl
import akka.stream.impl.ActorFlowMaterializerImpl._
import akka.stream.impl.GenJunctions.ZipWithModule
import akka.stream.impl.Junctions._
import akka.stream.impl.Stages.{DirectProcessor, StageModule}
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl._
import akka.stream.scaladsl.OperationAttributes
import akka.stream.stage.{Context, PushStage}
import org.reactivestreams.{Processor, Publisher, Subscriber}

import scala.concurrent.{Await, ExecutionContextExecutor}


object XRay {
  def apply()(implicit system: ActorSystem) = new XRay()
}

class XRay()(implicit system: ActorSystem) {

  val meter = system.actorOf(Props[XMeter], "meter")
  val api = system.actorOf(Props(new XRayApi(meter)), "api")

  def materializer(materializerSettings: Option[ActorFlowMaterializerSettings] = None,
    namePrefix: Option[String] = None,
    optimizations: Optimizations = Optimizations.none)
    (implicit context: ActorRefFactory): ActorFlowMaterializer = {
    val settings = materializerSettings getOrElse ActorFlowMaterializerSettings(system)

    new XActorFlowMaterializerImpl(
      settings,
      system.dispatchers,
      context.actorOf(StreamSupervisor.props(settings).withDispatcher(settings.dispatcher)),
      FlowNameCounter(system).counter,
      namePrefix.getOrElse("flow"),
      optimizations,
      meter)
  }
}

//class NotificationStage(meter: ActorRef, xf: XFlow) extends PushStage[Any, Any] {
//  override def onPush(elem: Any, ctx: Context[Any]) = ctx.push(elem)
//
//  override def onUpstreamFinish(ctx: Context[Any]) = {
//    meter ! XFlowFinished(xf)
//    super.onUpstreamFinish(ctx)
//  }
//
//  override def onDownstreamFinish(ctx: Context[Any]) = {
//    meter ! XFlowFinished(xf)
//    super.onDownstreamFinish(ctx)
//  }
//
//  override def onUpstreamFailure(cause: Throwable, ctx: Context[Any]) = {
//    meter ! XFlowFinished(xf)
//    super.onUpstreamFailure(cause, ctx)
//  }
//}
//
//class XRay private()(implicit system: ActorSystem) {
//  val meter = system.actorOf(Props[XMeter], "meter")
//}

/**
 * INTERNAL API
 */
class XActorFlowMaterializerImpl(override val settings: ActorFlowMaterializerSettings,
                                      override val dispatchers: Dispatchers,
                                      override val supervisor: ActorRef,
                                      override val flowNameCounter: AtomicLong,
                                      override val namePrefix: String,
                                      override val optimizations: Optimizations,
                                      val meter: ActorRef)
  extends ActorFlowMaterializerImpl(settings, dispatchers, supervisor, flowNameCounter, namePrefix, optimizations)
{
  import ActorFlowMaterializerImpl._
  import akka.stream.impl.Stages._

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  override def materialize[Mat](runnableFlow: Graph[ClosedShape, Mat]): Mat = {
    println("Call materialize")
    runnableFlow.module.validate()

    val session = new MaterializerSession(runnableFlow.module) {
      var nestLevel = 0
      private val flowName = createFlowName()
      private var nextId = 0
      private def stageName(attr: OperationAttributes): String = {
        val name = s"$flowName-$nextId-${attr.name}"
        nextId += 1
        name
      }

      override protected def materializeAtomic(atomic: Module, effectiveAttributes: OperationAttributes): Any = {
        meter ! atomic
        atomic match {
          case sink: SinkModule[_, _] ⇒
            val (sub, mat) = sink.create(XActorFlowMaterializerImpl.this, stageName(effectiveAttributes))
            assignPort(sink.shape.inlet, sub.asInstanceOf[Subscriber[Any]])
            mat
          case source: SourceModule[_, _] ⇒
            val (pub, mat) = source.create(XActorFlowMaterializerImpl.this, stageName(effectiveAttributes))
            assignPort(source.shape.outlet, pub.asInstanceOf[Publisher[Any]])
            mat

          case stage: StageModule ⇒
            val (processor, mat) = processorFor(stage, effectiveAttributes, calcSettings(effectiveAttributes)(settings))
            assignPort(stage.inPort, processor)
            assignPort(stage.outPort, processor)
            mat

          case junction: JunctionModule ⇒
            materializeJunction(junction, effectiveAttributes, calcSettings(effectiveAttributes)(settings))
        }
      }

      private def processorFor(op: StageModule,
                               effectiveAttributes: OperationAttributes,
                               effectiveSettings: ActorFlowMaterializerSettings): (Processor[Any, Any], Any) = op match {
        case DirectProcessor(processorFactory, _) ⇒ processorFactory()
        case _ ⇒
          val (opprops, mat) = ActorProcessorFactory.props(XActorFlowMaterializerImpl.this, op, effectiveAttributes)
          val processor = ActorProcessorFactory[Any, Any](actorOf(
            opprops,
            stageName(effectiveAttributes),
            effectiveSettings.dispatcher))
          processor -> mat
      }

      private def materializeJunction(op: JunctionModule,
                                      effectiveAttributes: OperationAttributes,
                                      effectiveSettings: ActorFlowMaterializerSettings): Unit = {
        op match {
          case fanin: FanInModule ⇒
            val (props, inputs, output) = fanin match {

              case MergeModule(shape, _) ⇒
                (FairMerge.props(effectiveSettings, shape.inArray.size), shape.inArray.toSeq, shape.out)

              case f: FlexiMergeModule[t, p] ⇒
                val flexi = f.flexi(f.shape)
                (FlexiMerge.props(effectiveSettings, f.shape, flexi), f.shape.inlets, f.shape.outlets.head)

              case MergePreferredModule(shape, _) ⇒
                (UnfairMerge.props(effectiveSettings, shape.inlets.size), shape.preferred +: shape.inArray.toSeq, shape.out)

              case ConcatModule(shape, _) ⇒
                require(shape.inArray.size == 2, "currently only supporting concatenation of exactly two inputs") // FIXME
                (Concat.props(effectiveSettings), shape.inArray.toSeq, shape.out)

              case zip: ZipWithModule ⇒
                (zip.props(effectiveSettings), zip.shape.inlets, zip.outPorts.head)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveSettings.dispatcher)
            val publisher = new ActorPublisher[Any](impl)
            impl ! ExposedPublisher(publisher)
            for ((in, id) ← inputs.zipWithIndex) {
              assignPort(in, FanIn.SubInput[Any](impl, id))
            }
            assignPort(output, publisher)

          case fanout: FanOutModule ⇒
            val (props, in, outs) = fanout match {

              case r: FlexiRouteModule[t, p] ⇒
                val flexi = r.flexi(r.shape)
                (FlexiRoute.props(effectiveSettings, r.shape, flexi), r.shape.inlets.head: InPort, r.shape.outlets)

              case BroadcastModule(shape, _) ⇒
                (Broadcast.props(effectiveSettings, shape.outArray.size), shape.in, shape.outArray.toSeq)

              case BalanceModule(shape, waitForDownstreams, _) ⇒
                (Balance.props(effectiveSettings, shape.outArray.size, waitForDownstreams), shape.in, shape.outArray.toSeq)

              case UnzipModule(shape, _) ⇒
                (Unzip.props(effectiveSettings), shape.in, shape.outlets)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveSettings.dispatcher)
            val size = outs.size
            def factory(id: Int) = new ActorPublisher[Any](impl) {
              override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
            }
            val publishers =
              if (outs.size < 8) Vector.tabulate(size)(factory)
              else List.tabulate(size)(factory)
            impl ! FanOut.ExposedPublishers(publishers)

            publishers.zip(outs).foreach { case (pub, out) ⇒ assignPort(out, pub) }
            val subscriber = ActorSubscriber[Any](impl)
            assignPort(in, subscriber)

        }
      }

      override protected def materializeModule(module: Module, effectiveAttributes: OperationAttributes) = {
        nestLevel += 1
        val result = super.materializeModule(module, effectiveAttributes)
        nestLevel -=1
        if(nestLevel == 0) {
          meter ! XModule(module)
        }
        result
      }
    }

    session.materialize().asInstanceOf[Mat]
  }

  override private[akka] def actorOf(props: Props, name: String, dispatcher: String): ActorRef = supervisor match {
    case ref: LocalActorRef ⇒
      ref.underlying.attachChild(props.withDispatcher(dispatcher), name, systemService = false)
    case ref: RepointableActorRef ⇒
      if (ref.isStarted)
        ref.underlying.asInstanceOf[ActorCell].attachChild(props.withDispatcher(dispatcher), name, systemService = false)
      else {
        implicit val timeout = ref.system.settings.CreationTimeout
        import akka.pattern._
        val f = (supervisor ? StreamSupervisor.Materialize(props.withDispatcher(dispatcher), name)).mapTo[ActorRef]
        Await.result(f, timeout.duration)
      }
    case unknown ⇒
      throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${unknown.getClass.getName}]")
  }

}
