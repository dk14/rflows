package api.routing.dsl

/**
  * Created by dkondratiuk on 6/2/15.
  */

import api.routing.metrics.DotVisualizer

import scala.reflect.runtime.universe._
import scala.language.higherKinds

import scala.concurrent._
import scala.language.implicitConversions

trait RoutingDSL[Ctx] extends RoutingDSLBase[Ctx] {

  type Meta = MetaBase

  case class MetaImpl(flow: Flow[Any, Any], ctx: Ctx) extends Meta

  def meta[In, Out](ctx: Ctx, flow: Flow[In, Out]) = MetaImpl(flow.asInstanceOf[Flow[Any, Any]], ctx)

}

case class FlowException(msg: String, t: Throwable) extends Exception(msg, t)

trait RoutingDSLBase[Ctx] {

  def moduleName = "root"

  type Meta <: MetaBase

  trait MetaBase { def flow: Flow[Any, Any]; def ctx: Ctx }

  def meta[In, Out](ctx: Ctx, flow: Flow[In, Out]): Meta

  case class Data[+State](data: State, ctx: Ctx, meta: Meta) {
    def withMeta[T](action: Meta => T) = action(meta) //we need this for measurements, to pass meta-info about current flow
    def fut = Future.successful(data)
  }

  implicit def toData[State](data: State)(implicit ctx: Ctx, meta: Meta): Data[State] = Data(data, ctx, meta)

  implicit def toDataList[T](data: Seq[T])(implicit ctx: Ctx, meta: Meta): Seq[Data[T]] = data map (Data(_, ctx, meta))

  implicit def fromData[T](data: Data[T]) = data.data

  implicit def fromDataF[In, Out](f: In => Out) = (in: Data[In]) => f(in.data)

  implicit def ec: ExecutionContext

  sealed trait FlowTag

  trait Group {

    trait Tag extends FlowTag

    private var _registry = List.empty[Flow[_, _]]
    def registry = _registry

    protected implicit class ToTagged[In: TypeTag, Out: TypeTag](f: Flow[In, Out]) {
      def tagged = {
        _registry ::= f
        TaggedFlow[In, Out, Tag](f) //, tag.toString.dropWhile('\"'!=).tail.takeWhile('\"'!=)
      }
    }
  }

  import scala.reflect.runtime.universe._

  case class TaggedFlow[-In: TypeTag, +Out: TypeTag, Tag: WeakTypeTag](f: Flow[In, Out]) extends Flow[In, Out] {
    def name = f.name
    override def toString = f.toString
  }

  sealed abstract class Flow[-In, +Out](implicit val in: TypeTag[_ >: In], out: TypeTag[Out]) extends ((Seq[In], Ctx) => Future[Seq[Out]]) {
    val signature = s"${in.tpe.typeSymbol.name} => ${out.tpe.typeSymbol.name}"

    def name: String

    def apply(in: Seq[In], ctx: Ctx) = execute(in, this)(ctx)

    def hasNormalName = (!this.isInstanceOf[Compose[_, _, _]] && !this.isInstanceOf[TaggedFlow[_, _, _]] && name.nonEmpty)

    private[RoutingDSLBase] def tag[Tag <: FlowTag: WeakTypeTag] = {
      implicit val tag = in.asInstanceOf[TypeTag[In]]
      TaggedFlow[In, Out, Tag](this)
    }

    override def toString = name

  }

  implicit def toFuture[T](v: T) = Promise.successful(v).future

  object Act {
    def simple[In: TypeTag, Out: TypeTag](name: String, handler: Data[In] => Out): Act[In, Out] = Act(name, handler(_: Data[In]))
  }

  case class Act[-In: TypeTag, +Out: TypeTag](name: String, handler: Data[In] => Future[Out]) extends Flow[In, Out]

  object Split {
    def simple[In: TypeTag, Middle: TypeTag, Out: TypeTag, Tag <: Group#Tag: WeakTypeTag, G <: Group](name: String, handler: Data[In] => Seq[(Middle, TaggedFlow[Middle, _ <: Out, Tag])])(implicit ev: G#Tag =:= Tag, g: G) = {

      def normalize(in: Seq[(Middle, TaggedFlow[Middle, _ <: Out, Tag])]) = in.map(x => Future.successful(x._1) -> x._2)
      Split(name, handler andThen normalize)
    }

    def route[In: TypeTag, Middle: TypeTag, Out: TypeTag, Tag <: Group#Tag: WeakTypeTag, G <: Group](name: String, handler: Data[In] => (Middle, TaggedFlow[Middle, _ <: Out, Tag]))(implicit ev: G#Tag =:= Tag, g: G) = {

      def normalize(in: (Middle, TaggedFlow[Middle, _ <: Out, Tag])) = Seq((Future.successful(in._1), in._2))
      Split(name, handler andThen normalize) |> Aggregate.simple(name + "_", _.head)
    }
  }

  case class Split[-In: TypeTag, Middle: TypeTag, +Out: TypeTag, Tag <: Group#Tag: WeakTypeTag, G <: Group](name: String, handler: Data[In] => Seq[(Future[Middle], TaggedFlow[Middle, _ <: Out, Tag])])(implicit ev: G#Tag =:= Tag, g: G) extends Flow[In, Out] {

    lazy val grp = g.registry

    override def toString = name + "[" + grp.map(_.name).reverse.mkString(", ") + "]"
  }

  object Aggregate {
    def simple[In: TypeTag, Out: TypeTag](name: String, handler: Data[Seq[In]] => Out) = {
      def normalize(o: Out) = Future.successful(o)
      Aggregate[In, Out](name, handler andThen normalize)
    }
  }

  case class Aggregate[-In: TypeTag, +Out: TypeTag](name: String, handler: Data[Seq[In]] => Future[Out]) extends Flow[In, Out]

  case class Compose[-In: TypeTag, Middle: TypeTag, +Out: TypeTag](do1: Flow[In, Middle], do2: Flow[Middle, Out]) extends Flow[In, Out] {
    val name = s"${do1.name} |> ${do2.name}"
    override def toString = s"${do1.toString} |> ${do2.toString}"
  }

  private def flatFutureSequence[A](f: Seq[Future[Seq[A]]]) = Future sequence f map (_.flatten)

  def before[T](f: Flow[T, _], in: Seq[T])(implicit ctx: Ctx) = {}

  def after[T](f: Flow[_, T], out: Seq[T])(implicit ctx: Ctx): Seq[T] = out

  def failure(f: Flow[_, _], t: Throwable)(implicit ctx: Ctx) = {}

  import scala.util.Try

  def log[In, Out](f: Flow[In, Out], in: Seq[In])(action: => Future[Seq[Out]])(implicit ctx: Ctx): Future[Seq[Out]] = {

    def name(t: Throwable) = (if (f.hasNormalName) f.name + ": " else "") + t.getMessage
    before(f, in)
    Try(action)
      .recover { case t: Throwable => Future.failed[Seq[Out]](FlowException(name(t), t)) }
      .get
      .recoverWith { case t: Throwable => failure(f, t); Future.failed(FlowException(name(t), t)) } map (after(f, _))
  }

  protected def execute[In, Out](in: Seq[In], d: Flow[In, Out])(implicit ctx: Ctx): Future[Seq[Out]] = log(d, in) {
    implicit val metaImplicit: Meta = meta(ctx, d)
    implicit def toDataFunction[In, Out](f: Data[In] => Out): In => Out = (x: In) => f(Data(x, ctx, metaImplicit))
    d match {
      case TaggedFlow(f) => execute(in, f)
      case a: Act[In, Out] =>
        flatFutureSequence (for (i <- in map a.handler) yield i.map(Seq(_)))
      case s: Split[In, Any, Out, _, _] @unchecked =>
        flatFutureSequence(for ((fut, d: Flow[Any, Out]) <- in flatMap s.handler) yield fut flatMap (f => execute(Seq(f), d)))
      case Aggregate(name, handler)            => handler(in).map(Seq(_))
      case c: Compose[In, Any, Out] @unchecked => execute(in, c.do1) flatMap (execute(_, c.do2))
    }
  }

  implicit class RichFlow[-In: TypeTag, Middle: TypeTag](f: Flow[In, Middle]) {
    def |>[Out: TypeTag](f2: Flow[Middle, Out]) = Compose(f, f2)
  }

}

class RoutingDSLImpl[Ctx](implicit val ec: ExecutionContext) extends RoutingDSL[Ctx] with DotVisualizer[Ctx]

