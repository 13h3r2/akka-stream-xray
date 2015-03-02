package akka.stream

import akka.actor.Actor
import akka.event.LoggingReceive
import akka.stream.impl.Ast.{AstNode, JunctionAstNode}
import akka.stream.scaladsl._
import org.reactivestreams.{Publisher, Subscriber}


class XMeter() extends Actor {
  import akka.stream.XMeter._
  var flows: List[XFlow] = Nil
  var junctions: List[XJunction] = Nil
  var metrics: Map[AstNode, ActorInterpreterMetrics] = Map.empty

  var ids = Map[Any, String]()
  def id(obj: Any) = {
    ids.getOrElse(obj, {
      val nextId = ids.size.toString
      ids += (obj -> nextId)
      nextId
    })
  }

  override def receive = LoggingReceive {
    case Register(op, m) =>
      metrics += (op -> m)
    case f: XFlow =>
      flows :+= f
    case j: XJunction =>
      junctions :+= j
    case QueryGraphState =>
      sender() ! GraphsState(metrics.map { case (key, v) => (id(key), v) })
    case XFlowFinished(flow) =>
      flows = flows.filterNot(_ == flow)
      junctions = junctions.filter { j =>
        j.inputs.exists(sub =>
          flows.map(_.to).collect {
            case SubscriberSink(s) => s
          }.contains(sub)
        ) ||
        j.outputs.exists(sub =>
          flows.map(_.from).collect {
            case PublisherSource(s) => s
          }.contains(sub)
        )
      }
    case QueryGraph =>
      val junctionNodes = junctions.map { j =>
        Node(id(j), j.op.attributes.name)
      }
      val flowNodes = flows.flatMap { f =>
        val opNodes = f.ops.map { op => Node(id(op), op.attributes.name) }
        val fromNode = f.from match {
          case PublisherSource(publisher) => None
          case x => Some(Node(id(x), x.getClass.getSimpleName))
        }
        val toNode = f.to match {
          case SubscriberSink(sub) => None
          case x => Some(Node(id(x), x.getClass.getSimpleName))
        }
        fromNode.toSeq ++ toNode.toSeq ++ opNodes
      }
      
      val edges = flows.flatMap { x =>
        val fromId = x.from match {
          case s@PublisherSource(p) => junctions
            .find(_.outputs.contains(p))
            .map(id)
            .getOrElse(id(s))
          case x => id(x)
        }
        val toId = x.to match {
          case s@SubscriberSink(p) => junctions
            .find(_.inputs.contains(p))
            .map(id)
            .getOrElse(id(s))
          case x => id(x)
        }
        (fromId +: x.ops.map(id).reverse :+ toId).sliding(2).map { it =>
          it.toSeq match {
            case from :: to :: Nil => Edge(from, to, "")
          }
        }.toSeq
      }
      sender() ! Graph(flowNodes ++ junctionNodes, edges)
  }
}

object XMeter {
  case object QueryGraph
  case object QueryGraphState
  case class Register(op: AstNode, metrics: ActorInterpreterMetrics)
  case object Init
}

class XJunction(val op: JunctionAstNode, val inputs: Seq[Subscriber[_]], val outputs: Seq[Publisher[_]])
class XFlow(val ops: List[AstNode], val from: Source[_], val to: Sink[_]) {
  override def toString = s"XFlow($ops)"
}
case class XFlowFinished(flow: XFlow)

case class Node(id: String, name: String)
case class Edge(from: String, to: String, name: String)
case class Graph(nodes: Seq[Node], edges: Seq[Edge])
case class GraphsState(state: Map[String, ActorInterpreterMetrics])