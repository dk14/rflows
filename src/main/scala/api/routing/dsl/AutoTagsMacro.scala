/*
package api.routing.dsl

import scala.annotation.StaticAnnotation
import scala.reflect.macros.Context
import scala.language.experimental.macros

/**
 * Created by dkondratiuk on 6/17/15.
class tg extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro tgMacro.impl
}

object tgMacro {
  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    val inputs = annottees.map(_.tree).toList

    val output = inputs.head match {
      case q"implicit object $name extends $parent { ..$body }" =>
        q"""
            implicit object $name extends $parent {
              ..${
                body.map {
                  case x@q"val $name: $typ = $body" =>
                    q"""val $name $typ = $body tagged"""
                }
              }
            }
          """
    }
    c.Expr[Any](output)
  }
}
*/
*/
