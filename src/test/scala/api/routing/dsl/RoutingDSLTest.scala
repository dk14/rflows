package api.routing.dsl

import api.routing.metrics.DotVisualizer
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Span }
import scala.language.postfixOps

import scala.concurrent._
import scala.util.Try

case class Ctx(name: String)

trait SampleRouter extends RoutingDSLImpl[Ctx] with DotVisualizer[Ctx] {

  val log = List.fill(11)(Promise[String])

  def printt(s: String) = log.find(x => Try(x.success(s)).isSuccess)

  override def before[T](f: Flow[T, _], in: Seq[T])(implicit ctx: Ctx) = f match {
    case _: Compose[_, _, _]    => printt(s"Entering ${f.name}")
    case _: TaggedFlow[_, _, _] =>
    case _                      => printt(s"Starting ${f.name}")
  }

  implicit object Grp extends Group { //group of flows used inside splitter
    val Flow1 = Act("chunk1", process) |> Act("chunk12", process) tagged
    val Flow2 = Act("chunk2", process) tagged
  }

  implicit object Grp2 extends Group { //group of flows used inside splitter
    val Flow1 = Act("chunk21", process) |> Act("chunk212", process) tagged
    val Flow2 = Act("chunk22", process) tagged
  }

  lazy val Flow = Act("map", process) |> Split("flatMap", split) |> Aggregate("reduce", aggregate) |> Act.simple("postMap", _.data) //flow itself

  def process(a: Data[String]) = Future(a + "!") //enrich

  def split(a: Data[String]) = Seq(Future(a.data) -> Grp.Flow1, Future(a.data) -> Grp.Flow2) //duplicate and send to Flow1 and Flow2

  def aggregate(aa: Data[Seq[String]]) = Future(aa.mkString) //merge

}

trait LoopRouter extends RoutingDSLImpl[Ctx] with DotVisualizer[Ctx] {

  implicit object LoopBack extends Group { //group of flows used inside splitter
    val Return = Act.simple[Int, Int]("chunk1", _.data) tagged
    lazy val Loop = Flow tagged //go to aggregate
  }

  lazy val Flow: Flow[Int, Int] = Split("decAndSplit", split) |> Aggregate("identity", aggregate)

  def split(a: Data[Int]) =
    Seq(if (a.data == 0) Future(a.data) -> LoopBack.Return else Future(a.data - 1) -> LoopBack.Loop)

  def aggregate(aa: Data[Seq[Int]]) = Future(aa.head) //merge

}

/**
  * Created by dkondratiuk on 6/3/15.
  */
class RoutingDSLTest extends FunSuite with Matchers with ScalaFutures {

  import scala.concurrent.ExecutionContext.Implicits.global

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(1500, Millis))

  val impl = new SampleRouter {}

  test("perform map -> flatMap -> [chunk1 -> chunk12, chunk2] -> reduce -> postMap") {

    impl.Flow.toString shouldBe ("map |> flatMap[chunk1 |> chunk12, chunk2] |> reduce |> postMap")

    val result = impl.Flow(Seq("a"), Ctx("name"))

    //println(impl.getSimpleDotGraphF(impl.Flow, List(impl.Stat("flatMap", "SomeTime", "0ms"))))

    result.futureValue shouldEqual Seq("a!!!a!!")

    whenReady(Future sequence impl.log.map(_.future)){ log =>

      log should contain ("Entering map |> flatMap |> reduce |> postMap")
      log should contain ("Entering map |> flatMap |> reduce")
      log should contain ("Entering map |> flatMap")

      log should contain ("Starting map")
      log should contain ("Starting flatMap")
      log should contain ("Starting chunk2")
      log should contain ("Starting chunk1")
      log should contain ("Starting chunk12")
      log should contain ("Starting reduce")
      log should contain ("Starting postMap")

      val rr = (0 to 1000).map(_ => impl.Flow.apply(Seq("a"), Ctx("name")).map(_.head))
      val r = Future.sequence(rr.toList).map(_.toSet)

      r.futureValue shouldEqual Set("a!!!a!!")

    }

  }

  test("perform some simple loop") {
    val looper = new LoopRouter {}
    val r = looper.Flow(Seq(5), Ctx("name"))
    //println(looper.getSimpleDotGraphF(looper.Flow, List(looper.Stat("decAndSplit", "DecTime", "0ms"))))
    r.futureValue shouldEqual Seq(0)

  }

}
