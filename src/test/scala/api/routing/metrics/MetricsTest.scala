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
    import instrInst._
    val somethingTime = tagged(timer, "metric", "name" -> "value")

    implicit val m = MetricContextImpl("test")

    somethingTime time {
      doSomething
    }

    val ctx = somethingTime.timerContext()

    Future {
      doSomething
      ctx.stop()
    }

    //or just

    Future {
      doSomething
    } measure "metric"

    val hitsCount = tagged(counter, "hits", "cache" -> "city")

    hitsCount.inc()

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
