package api.routing.metrics

import java.util.concurrent._
import java.util.logging.{Level, Logger}
import com.typesafe.config._
import nl.grons.metrics.scala._
import scala.concurrent.ExecutionContext
import scala.language.postfixOps

/**
  * Created by dkondratiuk on 6/5/15.
  */
object AppMetrics {

  implicit val metricRegistry = new com.codahale.metrics.MetricRegistry()

  val taggedMetricsRegistry = new TaggedMetricsRegistry(poolSize)

  lazy val config = ConfigFactory.load()

  lazy val poolSize = config.getInt("reporting.tags.size")


}


object TaggedName {

  val Parser = """([^\[]*)\[(.*)\]""".r
  def fromRaw(s: String) = s match {
    case Parser(name, params) => TaggedName("", name, params.split(",").map(_.span('='!=)).toMap.filterKeys(_.nonEmpty).mapValues(_.tail))
  }

}

case class TaggedName(baseName: String, name: String, tags: Map[String, String]) {
  private val concatName = baseName + name + tags.map{ case (a, b) => a + b }.mkString
  require((concatName.toSet & Set('[', ']', '=', ',')).isEmpty, "No special symbols: `[]=,` in " + tags)

  lazy val raw = baseName + "." + rawWithoutBase
  lazy val rawWithoutBase = s"$name[${tags.map{ case (a, b) => a + "=" + b }.mkString(",")}]"
  override def toString = raw
}

class TaggedMetricsRegistry(val size: Int = 100000, val isMeta: Boolean = true)(implicit val metricRegistry: com.codahale.metrics.MetricRegistry) {

  lazy val metaMetrics = new MetricBuilder(MetricName("tagsOverflow"), metricRegistry)

  private type TagsRegistry = ConcurrentRingSet[String]

  val registry = new ConcurrentHashMap[String, TagsRegistry]

  def remove(name: String): Unit = {
    metricRegistry.remove(name)
    if (isMeta) metaMetrics.counter(name).inc()
  }

  private def getOrAdd(name: String): TagsRegistry = {
    registry.putIfAbsent(name, new ConcurrentRingSet[String](size)(remove))
    Option(registry.get(name)) getOrElse getOrAdd(name)
  }

  def put(n: TaggedName) = getOrAdd(n.name).put(n.raw)

  def unregister(name: String) = Option(registry.get(name)) map (_ close) foreach (_ map (metricRegistry.remove))

}

trait MetricsContext {
  def step: String
}

case class MetricContextImpl(step: String) extends MetricsContext

object MetricsContextDefault extends MetricsContext {
  def step = "default"
}

trait Instrumented extends InstrumentedBuilder {

  val logger = Logger.getLogger("metrics-sys")

  implicit lazy val metricRegistry = AppMetrics.metricRegistry
  lazy val taggedMetricsRegistry = AppMetrics.taggedMetricsRegistry
  override lazy val metricBuilder = new MetricBuilder(MetricName("dfapi"), metricRegistry)
  implicit lazy val mb = metricBuilder

  type MetricsCoproduct = Timer with Counter with Meter with Histogram with Gauge[_]

  import nl.grons.metrics.scala._

  def tagged[T](metricFactory: String => T, name: String, tags: (String, String)*)(implicit metricBuilder: MetricBuilder, ev: MetricsCoproduct <:< T): T = {
    val nm = TaggedName(metricBuilder.baseName.name, name, tags.toMap)
    val res = metricFactory(nm.rawWithoutBase)
    if (metricRegistry.getMetrics.containsKey(nm.raw))
      taggedMetricsRegistry.put(nm)
    else logger.log(Level.WARNING, s"Metric rejected: ${name}, raw: ${nm.raw}")

    res
  }

  /**
    * Allows you to get ranged statistics for value, like that
    *
    *                      4
    *            3         _
    *            _        | |         2
    *           | |       | |         _
    *           | |       | |        | |
    *  _________| |_______| |________| |____
    *  range:   0-4       5-9       10-15
    *  bucket:   0         1          2
    *
    *  granularity = 5
    */
  def discrete(name: String, granularity: Int, additionalTags: (String, String)*)(value: Double) =
    tagged(counter, name, (("bucket" -> (value / granularity).toInt.toString) +: additionalTags.toSeq): _*).inc()

  implicit class RichFuture[T](f: scala.concurrent.Future[T])(implicit fctx: ExecutionContext) { //TODO add metrics context
    def measure(name: String, tags: (String, String)*)(implicit mctx: MetricsContext): scala.concurrent.Future[T] = {
      val ctx = tagged(timer, name, tags ++ Seq("flowName" -> mctx.step): _*).timerContext()
      f.map{ x => ctx.stop(); x }
    }
  }

  def unregister(name: String): Unit = taggedMetricsRegistry.unregister(name)

  def timer(name: String) = metrics.timer(name, "")
  def counter(name: String) = metrics.counter(name, "")
  def meter(name: String) = metrics.meter(name, "")
  def histogram(name: String) = metrics.histogram(name, "")

}