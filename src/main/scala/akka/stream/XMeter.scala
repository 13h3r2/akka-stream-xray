package akka.stream

import akka.actor.Actor
import akka.event.LoggingReceive
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.{SinkModule, SourceModule}


class XMeter() extends Actor {

  case class ModuleInfo(module: Module, name: String)

  import akka.stream.XMeter._
  var modules: Seq[ModuleInfo] = Nil
  var toplevel: Seq[Module] = Nil

  var ids = Map[Any, String]()
  def id(obj: Any) = ids.getOrElse(obj, {
      val nextId = ids.size.toString
      ids += (obj -> nextId)
      nextId
    })

  def moduleName(module: Module): String = module match {
    case x: SourceModule[_, _] => x.shape.outlet.toString
    case x: SinkModule[_, _] => x.shape.inlet.toString
    case x: Module => x.attributes.name.toString
  }

  override def receive = LoggingReceive {
    case (name: Option[String], m: Module) => modules :+= ModuleInfo(m, name.getOrElse(moduleName(m)))
    case x: XModule => toplevel :+= x.module
    case QueryGraph =>
      val flowNodes = modules.map { m =>
        Node(id(m), m.name)
      }

      val inPorts = modules.flatMap { m => m.module.inPorts.map(_ -> m) }.toMap
      val outPorts = modules.flatMap { m => m.module.outPorts.map(_ -> m) }.toMap

      val edges = toplevel.flatMap(_.downstreams.map {
        case (out, in) =>
          Edge(id(outPorts(out)), id(inPorts(in)))
      }).toSeq
      sender() ! GraphShape(flowNodes, edges)
  }
}

object XMeter {
  case object QueryGraph
  case object QueryGraphState
}

//case class XFlowFinished(flow: XFlow)
case class XModule(module: Module)

case class Node(id: String, name: String)
case class Edge(from: String, to: String, name: String = "")
case class GraphShape(nodes: Seq[Node], edges: Seq[Edge])
//case class GraphsState(state: Map[String, ActorInterpreterMetrics])