package akka.stream

import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.impl.Ast.AstNode
import akka.stream.impl.fusing.OneBoundedInterpreter
import akka.stream.stage.Stage


trait ActorInterpreterMetrics {
  def upstreamBatchRemaining(): Int
  def upstreamInputBuffer(): Int
  def downstreamDemand(): Long

}
private[akka] object XActorInterpreter {
  def props(settings: ActorFlowMaterializerSettings, op: AstNode, ops: Seq[Stage[_, _]]): Props =
    Props(new XActorInterpreter(settings, op, ops))
}

private[akka] class XActorInterpreter(val settings: ActorFlowMaterializerSettings, op: AstNode, val ops: Seq[Stage[_, _]])
  extends Actor with ActorLogging {
  private val upstream = new XBatchingActorInputBoundary(settings.initialInputBufferSize)
  private val downstream = new XActorOutputBoundary(self, settings.debugLogging, log)
  private val interpreter = new OneBoundedInterpreter(upstream +: ops :+ downstream)
  interpreter.init()

  val metrics = new ActorInterpreterMetrics {
    override def upstreamBatchRemaining() = upstream.batchRemaining
    override def downstreamDemand() = downstream.downstreamDemand
    override def upstreamInputBuffer() = upstream.inputBufferElements
  }

  context.actorSelection("/user/meter") ! XMeter.Register(op, metrics)

  def receive: Receive = upstream.subreceive orElse downstream.subreceive

  override protected[akka] def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    super.aroundReceive(receive, msg)
    if (interpreter.isFinished) context.stop(self)
  }

  override def postStop(): Unit = {
    upstream.cancel()
    downstream.fail(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted", reason)
  }
}

