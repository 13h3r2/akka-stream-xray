package gypsy

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._


object Test extends App {
//  implicit val as = ActorSystem()
//  val xray = XRay()
//  implicit val m = xray.materializer()
////  implicit val m = ActorFlowMaterializer()
//
//  val flow = Flow() { implicit b =>
//    import akka.stream.scaladsl.FlowGraphImplicits._
//    val in = UndefinedSource[Int]
//    val out = UndefinedSink[Int]
////    val bcast = Broadcast[Int]
////    val merge = Merge[Int]
//
//    in ~> Flow[Int].map(x => x) ~> out
////    in ~> bcast ~> Flow[Int].map(x => x) ~> merge ~> out
////          bcast ~> Flow[Int].map(x => x) ~> merge
////          bcast ~> Flow[Int].map(x => x) ~> merge
////          bcast ~> Flow[Int].map(x => x) ~> merge
//    (in, out)
//  }
//
//  Source(1 to 10 map { x =>
//    Source((1 to 1000).to[scala.collection.immutable.Seq])
//  })
//    .flatten(FlattenStrategy.concat)
//    .map{ x => Thread.sleep(1000); x }
//    .map { x => println(x);x }
//    .to(Sink.ignore)
//    .run()
}
