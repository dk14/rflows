package api.routing.dsl

import java.util.concurrent.TimeUnit

import api.routing.metrics._
import com.codahale._
import com.typesafe.config.Config
import scala.concurrent.Future
import scala.language.postfixOps
/**
  *
  * It's routing with auto-metrics
  *
  * @author dkondratiuk on 6/10/15.
  */
trait InstrumentedRouting[Ctx] extends RoutingDSLBase[Ctx] with Instrumented with EdgesProvider[Ctx] with ManagableFlowsConfig[Ctx] {

  override type Meta = MetaBase with MetricsContext //we need this type to propagate MetricsContext into services and logic

  def meta[In, Out](context: Ctx, fl: Flow[In, Out]): Meta = new MetaBase with MetricsContext {
    def flow: Flow[Any, Any] = fl.asInstanceOf[Flow[Any, Any]]
    def ctx = context
    def step: String = flow.name
  }

  def extractTags[In](in: Seq[In], ctx: Ctx): Map[String, String] = Map()

  override def log[In, Out](f: Flow[In, Out], in: Seq[In])(action: => Future[Seq[Out]])(implicit ctx: Ctx): Future[Seq[Out]] = {
    if (!f.hasNormalName) {
      val tmCtx = tagged(timer, "flow", Seq("flowName" -> f.name) ++ extractTags(in, ctx).toSeq: _*) timerContext ()
      super.log(f, in)(action)
        .map(x => { tmCtx.stop(); x })
        .recoverWith { case t: Throwable => tmCtx.stop(); Future.failed(t) }
    }
    else {
      super.log(f, in)(action)
    }
  }

}

trait FlowRegistration[Ctx] extends RoutingDSLBase[Ctx] {

  implicit class RegisterFlow[In, Out](f: Flow[In, Out]) {
    def register: Flow[In, Out] = { registerCompositeFlow(f); f }
  }

  def registerCompositeFlow[In, Out](f: Flow[In, Out]) = {}
}

