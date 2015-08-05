package api.routing.metrics

import java.util.concurrent.atomic.AtomicLong

import nl.grons.metrics.scala.{ MetricName, MetricBuilder }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, Matchers }

import scala.concurrent.Future

/**
  * Created by dkondratiuk on 6/8/15.
  */
class MetricsTest extends FunSuite with Matchers with ScalaFutures {

  def doSomething = {}

  def instr = new Instrumented {
    override implicit lazy val metricRegistry = new com.codahale.metrics.MetricRegistry()
    override lazy val taggedMetricsRegistry = new TaggedMetricsRegistry(10, false)(metricRegistry)
  }

  test("put too much metrics") {

    val instrInst = instr
    import instrInst._

    val r = (0 to 100500).par foreach (i => tagged(timer, "metric", "name" -> i.toString))

    metricRegistry.getMetrics.size() shouldBe (taggedMetricsRegistry.size)

  }

  test("put not much metrics") {

    val instrInst = instr
    import instrInst._

    val r = (0 to 100500).par foreach (i => tagged(timer, "metric", "name" -> "ab"))

    metricRegistry.getMetrics.size() shouldBe 1
    taggedMetricsRegistry.registry.size shouldBe 1
    taggedMetricsRegistry.registry.values().iterator().next().size shouldBe 1

  }

  test("ring test ") {
    val c = new AtomicLong(0)
    val ring = new ConcurrentRingSet[String](10)(_ => c.incrementAndGet())
    val r = (0 to 100500).par map (i => ring.put(i.toString))
    c.get() shouldBe 100501 - 10
  }

  test("ring uniqueness test") {
    val c = new AtomicLong(0)
    val ring = new ConcurrentRingSet[String](10)(_ => c.incrementAndGet())
    val r = (0 to 100500).par map (i => ring.put("a"))
    c.get() shouldBe 0
  }

  test("just usage examples") {
    import scala.concurrent.ExecutionContext.Implicits.global
    val instrInst = instr
    import instrInst._ // in your application, just extend `Instrumented` instead of that


    //---------------Example1----------------

    //example of simple timer, it measures time of doSomething and sends it to yammer-metrics (to be picked up by reporters later)
    val somethingTime = tagged(timer, "metric", "name" -> "value")

    somethingTime time {
      doSomething
    }


    //---------------Example2----------------
    val ctx = somethingTime.timerContext()

    Future {
      doSomething
      ctx.stop()
    }

    //or just:

    //---------------Example3----------------

    implicit val m = MetricContextImpl("test") //this is needed for Future.measure syntax

    val myFuture = Future {
      doSomething
    } measure "metric" //measure is ad-hoc method of the Future, which takes name (and optionally tags) and returns same Future

    val myFuture2 = Future { //just another example, which shows how to use tags
      doSomething
    } measure ("metric", "tag1" -> "value1", "tag2" -> "value2") //tag3 -> value3 etc...


    //---------------Example4----------------

    val hitsCount = tagged(counter, "hits", "cache" -> "city")

    hitsCount.inc()

    //---------------Example5----------------

    implicit val extr = TagsExtractor[String](r => Seq("firstLetter" -> r.head.toString)) //this gonna add firstLetter tag with value "r" (see "result" below)

    val result = time("measurement") { //this is one of measurements, that is affected by extractor
      doSomething
      "result"
    }


  }

  test("metric name") {
    val baseName = ""
    val name = "aaaa"
    val tags = Map.empty[String, String]
    val tn = TaggedName.fromRaw(TaggedName(baseName, name, tags).raw)
    tn.name shouldBe ("." + name)
    tn.tags shouldBe (tags)
  }

  test("metric name and tags") {
    val baseName = ""
    val name = "aaaa"
    val tags = Map("aaaa" -> "bbb", "cccc" -> "dddd")
    val tn = TaggedName.fromRaw(TaggedName(baseName, name, tags).raw)
    tn.name shouldBe ("." + name)
    tn.tags shouldBe (tags)
  }

}
