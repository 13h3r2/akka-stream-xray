package gypsy

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._


object Test extends App {
  implicit val as = ActorSystem()
  val xray = XRay()
  implicit val m = xray.materializer()

  val flow = Flow() { implicit b =>
    import FlowGraph.Implicits._
    val bcast = b.add(Broadcast[Int](2))
    val merge = b.add(Merge[String](2))

    bcast ~> Flow[Int].filter(_ % 2 == 0).map(x => s"filtered-$x") ~> merge
    bcast ~> Flow[Int].map(x => s"mapped - $x") ~> merge

    bcast.in -> merge.out
  }

  Source(1 to 100 map { x =>
    Source((1 to 1000).to[scala.collection.immutable.Seq])
  })
    .flatten(FlattenStrategy.concat)
    .map{ x => Thread.sleep(300); x }
    .via(flow)
    .filter { x => println(x); true }
    .to(Sink.ignore())
    .run()
}
