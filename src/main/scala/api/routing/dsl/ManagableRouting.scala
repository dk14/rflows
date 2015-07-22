package api.routing.dsl

import java.util.concurrent.{ Executors, TimeUnit }

import com.typesafe.config.{ ConfigFactory, Config }

import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration.Duration
import scala.util.Try

/**
  * Created by dkondratiuk on 7/6/15.
  */
trait ManagableFlows[Ctx] extends RoutingDSLBase[Ctx] {

  lazy val flowTimeout = Duration(10, TimeUnit.SECONDS)

  lazy val flowRetries = 1 //TODO implement

  lazy val scheduler = Executors.newScheduledThreadPool(1)

  override def log[In, Out](f: Flow[In, Out], in: Seq[In])(action: => Future[Seq[Out]])(implicit ctx: Ctx): Future[Seq[Out]] = {

    val r = super.log(f, in)(action)

    val p = Promise[Seq[Out]]()
    p tryCompleteWith r

    if (f.hasNormalName && !f.isInstanceOf[Split[_, _, _, _, _]]) {
      val action = new Runnable {
        override def run(): Unit = p tryFailure new Exception(s"Act timeout: ${f.name}!")
      }
      scheduler.schedule(action, flowTimeout.toMillis, TimeUnit.MILLISECONDS)
    }
    p.future
  }

}

trait ManagableFlowsConfig[C] extends ManagableFlows[C] {

  lazy val config: Config = ConfigFactory.load()

  override lazy val flowTimeout = Duration(
    Try(config.getDuration("flow.timeout", TimeUnit.MILLISECONDS)).getOrElse(10000L), TimeUnit.MILLISECONDS)

  override lazy val flowRetries = Try(config.getInt("flow.retries")).getOrElse(1) //TODO implement

}
