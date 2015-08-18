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

  //--------------------Meta-information required by metrics and handlers-----------------------------------------------

  type Meta <: MetaBase

  type TT[T] = TypeTag[T]

  type C = Ctx

  trait MetaBase {
    def flow: Flow[Any, Any]
    def ctx: C
  }

  def meta[In, Out](ctx: C, flow: Flow[In, Out]): Meta

  case class Data[+State](data: State, ctx: C, meta: Meta) {
    def withMeta[T](action: Meta => T) = action(meta) //we need this for measurements, to pass meta-info about current flow
    def fut = Future.successful(data)
  }

  implicit def fromData[T](data: Data[T]) = data.data

  implicit def ec: ExecutionContext

  //-------Type Functions used for splitter-----------------------------------------------------------------------------

  type GTag = Group#Tag //path-dependent tag as a part of a group (see 'Flow Grouping')

  type boundTo[Tag <: GTag, G <: Group] = G#Tag =:= Tag //prevents ambiguous implicit Group resolution

  type Route[From, FromRaw, +To, Tag <: GTag] = (From, TaggedFlow[FromRaw, _ <: To, Tag]) //just a route directive

  type SplitHandler [-In, M, +Out, Tag <: GTag] = Data[In] => Seq[Route[Future[M], M, Out, Tag]] //general splitting handler

  type SplitHandlerSimple[-In, M, +Out, Tag <: GTag] = Data[In] => Seq[Route[M, M, Out, Tag]]

  type SplitHandlerSingle[-In, M, +Out, Tag <: GTag] = Data[In] => Route[M, M, Out, Tag]

  //-----------------------DSL itself-----------------------------------------------------------------------------------

  sealed abstract class Flow[-In, +Out](implicit val in: TT[_ >: In], val out: TT[_ <: Out]) extends ((Seq[In], Ctx) => Future[Seq[Out]]) {
    val signature = s"${in.tpe.typeSymbol.name} => ${out.tpe.typeSymbol.name}"

    def name: String

    def apply(in: Seq[In], ctx: C) = execute(in, this)(ctx)

    def hasNormalName = this.isInstanceOf[BasicFlow] && name.nonEmpty

    override def toString = stringify(this)

  }

  trait BasicFlow //just a marker
  trait AuxiliaryFlow //just a marker

  case class Act[-In: TT, +Out: TT](name: String, handler: Data[In] => Future[Out]) extends Flow[In, Out] with BasicFlow

  case class Split[-In: TT, Middle: TT, +Out: TT, Tag <: GTag, G <: Group]
    (name: String, handler: SplitHandler[In, Middle, Out, Tag])(implicit val ev: Tag boundTo G, val g: G) extends Flow[In, Out] with BasicFlow

  case class Aggregate[-In: TT, +Out: TT](name: String, handler: Data[Seq[In]] => Future[Out]) extends Flow[In, Out] with BasicFlow

  case class Compose[-In: TT, Middle: TT, +Out: TT](name: String, do1: Flow[In, Middle], do2: Flow[Middle, Out]) extends Flow[In, Out] with AuxiliaryFlow


  implicit class RichFlow[-In: TT, Middle: TT](f: Flow[In, Middle]) {
    def |>[Out: TypeTag](f2: Flow[Middle, Out]) = Compose(s"${f.name} |> ${f2.name}", f, f2)
  }


  //--------------------------------PrettyPrint-------------------------------------------------------------------------

  def stringify[In, Out](f: Flow[In, Out]): String = f match {
    case TaggedFlow(f) => stringify(f)
    case a: Act[In, Out] => a.name
    case s: Split[In, Any, Out, _, _] @unchecked =>
      s.name + "[" + s.g.registry.map(_.name).reverse.mkString(", ") + "]"
    case Aggregate(name, handler)            => name
    case c: Compose[In, Any, Out] @unchecked => s"${c.do1.toString} |> ${c.do2.toString}"
  }

  //----------------------------------Use these wrappers to simplify DSL usage------------------------------------------

  object Act {
    def simple[In: TT, Out: TT](name: String, handler: Data[In] => Out): Act[In, Out] =
      Act(name, handler andThen (Future.successful _))
  }

  object Split {
    def simple[In: TT, Middle: TT, Out: TT, Tag <: GTag, G <: Group]
      (name: String, handler: SplitHandlerSimple[In, Middle, Out, Tag])(implicit ev: Tag boundTo G, g: G) = {

      def normalize(in: Seq[(Middle, TaggedFlow[Middle, _ <: Out, Tag])]) = in.map(x => Future.successful(x._1) -> x._2)
      Split(name, handler andThen normalize)
    }

    def route[In: TT, Middle: TT, Out: TT, Tag <: GTag, G <: Group]
      (name: String, handler: SplitHandlerSingle[In, Middle, Out, Tag])(implicit ev: Tag boundTo G, g: G) = {

      def normalize(in: (Middle, TaggedFlow[Middle, _ <: Out, Tag])) = Seq((Future.successful(in._1), in._2))
      Split(name, handler andThen normalize) |> Aggregate.simple(name + "_", _.head)
    }
  }

  object Aggregate {
    def simple[In: TT, Out: TT](name: String, handler: Data[Seq[In]] => Out) = {
      def normalize(o: Out) = Future.successful(o)
      Aggregate[In, Out](name, handler andThen normalize)
    }
  }

  //--------------Flow Grouping for splitters---------------------------------------------------------------------------

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

  case class TaggedFlow[-In: TypeTag, +Out: TypeTag, Tag: WeakTypeTag](f: Flow[In, Out]) extends Flow[In, Out] with AuxiliaryFlow {
    def name = f.name
    override def toString = f.toString
  }

  //----------------------AOP-------------------------------------------------------------------------------------------

  def before[T](f: Flow[T, _], in: Seq[T])(implicit ctx: Ctx) = {}

  def after[T](f: Flow[_, T], out: Seq[T])(implicit ctx: Ctx): Seq[T] = out

  def failure(f: Flow[_, _], t: Throwable)(implicit ctx: Ctx) = {}

  import scala.util.Try

  def log[In, Out](f: Flow[In, Out], in: Seq[In])(action: => Future[Seq[Out]])(implicit ctx: Ctx): Future[Seq[Out]] = {

    def name(t: Throwable) = (if (f.hasNormalName) f.name + ": " else "") + t.getMessage
    before(f, in)
    Try(action)
      .recover { case t: Throwable => Future.failed[Seq[Out]](t) }
      .get
      .recoverWith { case t: Throwable => failure(f, t); Future.failed(FlowException(name(t), t)) } map (after(f, _))
  }

  //----------------Executor itself-------------------------------------------------------------------------------------

  protected def execute[In, Out](in: Seq[In], d: Flow[In, Out])(implicit ctx: C): Future[Seq[Out]] = log(d, in) {
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

  private def flatFutureSequence[A](f: Seq[Future[Seq[A]]]) = Future sequence f map (_.flatten)

}

class RoutingDSLImpl[Ctx](implicit val ec: ExecutionContext) extends RoutingDSL[Ctx] with DotVisualizer[Ctx]

