package akka.stream

import akka.actor._
import akka.stream.impl._

private[akka] object XActorProcessorFactory {

  import akka.stream.impl.Ast._
  def props(materializer: ActorFlowMaterializer, op: AstNode, name: String): Props = {
    val settings = materializer.settings // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    op match {
      case Fused(ops, _)              ⇒ XActorInterpreter.props(settings, op, ops)
      case Map(f, _)                  ⇒ XActorInterpreter.props(settings, op, List(fusing.Map(f)))
      case Filter(p, _)               ⇒ XActorInterpreter.props(settings, op, List(fusing.Filter(p)))
      case Drop(n, _)                 ⇒ XActorInterpreter.props(settings, op, List(fusing.Drop(n)))
      case Take(n, _)                 ⇒ XActorInterpreter.props(settings, op, List(fusing.Take(n)))
      case Collect(pf, _)             ⇒ XActorInterpreter.props(settings, op, List(fusing.Collect(pf)))
      case Scan(z, f, _)              ⇒ XActorInterpreter.props(settings, op, List(fusing.Scan(z, f)))
      case Expand(s, f, _)            ⇒ XActorInterpreter.props(settings, op, List(fusing.Expand(s, f)))
      case Conflate(s, f, _)          ⇒ XActorInterpreter.props(settings, op, List(fusing.Conflate(s, f)))
      case Buffer(n, s, _)            ⇒ XActorInterpreter.props(settings, op, List(fusing.Buffer(n, s)))
      case MapConcat(f, _)            ⇒ XActorInterpreter.props(settings, op, List(fusing.MapConcat(f)))
      case MapAsync(f, _)             ⇒ MapAsyncProcessorImpl.props(settings, f)
      case MapAsyncUnordered(f, _)    ⇒ MapAsyncUnorderedProcessorImpl.props(settings, f)
      case Grouped(n, _)              ⇒ XActorInterpreter.props(settings, op, List(fusing.Grouped(n)))
      case GroupBy(f, _)              ⇒ GroupByProcessorImpl.props(settings, f)
      case PrefixAndTail(n, _)        ⇒ PrefixAndTailImpl.props(settings, n)
      case SplitWhen(p, _)            ⇒ SplitWhenProcessorImpl.props(settings, p)
      case ConcatAll(_)               ⇒ ConcatAllImpl.props(materializer) //FIXME closes over the materializer, is this good?
      case StageFactory(mkStage, _)   ⇒ XActorInterpreter.props(settings, op, List(mkStage()))
      case TimerTransform(mkStage, _) ⇒ TimerTransformerProcessorsImpl.props(settings, mkStage())
    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}

