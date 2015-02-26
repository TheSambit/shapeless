/*
 * Copyright (c) 2013-15 Miles Sabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shapeless

import scala.language.dynamics
import scala.language.existentials
import scala.language.experimental.macros

import scala.reflect.macros.Context

import tag.@@
import scala.util.Try

trait Witness {
  type T
  val value: T {}
}

object Witness extends Dynamic {
  type Aux[T0] = Witness { type T = T0 }
  type Lt[Lub] = Witness { type T <: Lub }

  implicit def apply[T]: Witness.Aux[T] = macro SingletonTypeMacros.materializeImpl[T]

  implicit def apply[T](t: T): Witness.Lt[T] = macro SingletonTypeMacros.convertImpl[T]

  implicit val witness0: Witness.Aux[_0] =
    new Witness {
      type T = _0
      val value = Nat._0
    }

  implicit def witnessN[P <: Nat]: Witness.Aux[Succ[P]] =
    new Witness {
      type T = Succ[P]
      val value = new Succ[P]()
    }

  def selectDynamic(tpeSelector: String): Any = macro SingletonTypeMacros.witnessTypeImpl
}

trait WitnessWith[TC[_]] extends Witness {
  val instance: TC[T]
  type Out
}

trait LowPriorityWitnessWith {
  implicit def apply2[H, TC2[_ <: H, _], S <: H, T](t: T): WitnessWith.Lt[({ type λ[X] = TC2[S, X] })#λ, T] =
    macro SingletonTypeMacros.convertInstanceImpl2[H, TC2, S, T]
}

object WitnessWith extends LowPriorityWitnessWith {
  type Aux[TC[_], T0] = WitnessWith[TC] { type T = T0  }
  type Lt[TC[_], Lub] = WitnessWith[TC] { type T <: Lub }

  implicit def apply1[TC[_], T](t: T): WitnessWith.Lt[TC, T] = macro SingletonTypeMacros.convertInstanceImpl1[TC, T]
}

trait SingletonTypeUtils[C <: Context] extends ReprTypes {
  val c: C

  import c.universe.{ Try => _, _ }

  def singletonOpsTpe = typeOf[syntax.SingletonOps]
  val SymTpe = typeOf[scala.Symbol]

  object LiteralSymbol {
    def unapply(t: Tree): Option[String] = t match {
      case q""" scala.Symbol.apply(${Literal(Constant(s: String))}) """ => Some(s)
      case _ => None
    }
  }

  object SingletonSymbolType {
    val atatTpe = typeOf[@@[_,_]].typeConstructor
    val TaggedSym = typeOf[tag.Tagged[_]].typeConstructor.typeSymbol

    def apply(s: String): Type = appliedType(atatTpe, List(SymTpe, ConstantType(Constant(s))))

    def unapply(t: Type): Option[String] =
      t match {
        case RefinedType(List(SymTpe, TypeRef(_, TaggedSym, List(ConstantType(Constant(s: String))))), _) => Some(s)
        case _ => None
      }
  }

  def mkSingletonSymbol(s: String): Tree = {
    val sTpe = SingletonSymbolType(s)
    q"""_root_.scala.Symbol($s).asInstanceOf[$sTpe]"""
  }

  object SingletonType {
    def unapply(t: Tree): Option[Type] = (t, t.tpe) match {
      case (Literal(k: Constant), _) => Some(ConstantType(k))
      case (LiteralSymbol(s), _) => Some(SingletonSymbolType(s))
      case (_, keyType: SingleType) => Some(keyType)
      case (q""" $sops.narrow """, _) if sops.tpe <:< singletonOpsTpe =>
        Some(sops.tpe.member(newTypeName("T")).typeSignature)
      case _ => None
    }
  }

  def parseLiteralType(typeStr: String): Option[c.Type] =
    for {
      parsed <- Try(c.parse(typeStr)).toOption
      checked = c.typeCheck(parsed, silent = true)
      if checked != EmptyTree
      tpe <- SingletonType.unapply(checked)
    } yield tpe

  def parseStandardType(typeStr: String): Option[c.Type] =
    for {
      parsed <- Try(c.parse(s"null.asInstanceOf[$typeStr]")).toOption
      checked = c.typeCheck(parsed, silent = true)
      if checked != EmptyTree
    } yield checked.tpe

  def parseType(typeStr: String): Option[c.Type] =
    parseStandardType(typeStr) orElse parseLiteralType(typeStr)

  def typeCarrier(tpe: c.Type) = {
    val carrier = c.typeCheck(q"null.asInstanceOf[{ type T = $tpe }]", silent = true).tpe

    // We can't yield a useful value here, so return Unit instead which is at least guaranteed
    // to result in a runtime exception if the value is used in term position.
    Literal(Constant(())).setType(carrier)
  }
}

class SingletonTypeMacros[C <: Context](val c: C) extends SingletonTypeUtils[C] {
  import syntax.SingletonOps
  import c.universe._

  type SingletonOpsLt[Lub] = SingletonOps { type T <: Lub }

  import c.universe._

  def mkWitness(sTpe: Type, s: Tree): Tree = {
    val name = newTypeName(c.fresh())

    q"""
      {
        final class $name extends _root_.shapeless.Witness {
          type T = $sTpe
          val value: $sTpe = $s
        }
        new $name
      }
    """
  }

  def mkWitnessWith(parent: Type, sTpe: Type, s: Tree, i: Tree): Tree = {
    val name = newTypeName(c.fresh())
    val iTpe =
      (i.tpe match {
        case NullaryMethodType(resTpe) => resTpe
        case other => other
      }).normalize
    val iOut = iTpe.member(newTypeName("Out")) match {
      case NoSymbol => definitions.NothingClass
      case other => other
    }

    q"""
      {
        final class $name extends $parent {
          val instance: $iTpe = $i
          type T = $sTpe
          type Out = $iOut
          val value: $sTpe = $s
        }
        new $name
      }
    """
  }

  def mkOps(sTpe: Type, w: Tree): Tree = {
    val name = newTypeName(c.fresh())

    q"""
      {
        final class $name extends _root_.shapeless.syntax.SingletonOps {
          type T = $sTpe
          val witness = $w
        }
        new $name
      }
    """
  }

  def mkAttributedRef(pre: Type, sym: Symbol): Tree = {
    val global = c.universe.asInstanceOf[scala.tools.nsc.Global]
    val gPre = pre.asInstanceOf[global.Type]
    val gSym = sym.asInstanceOf[global.Symbol]
    global.gen.mkAttributedRef(gPre, gSym).asInstanceOf[Tree]
  }

  def materializeImpl[T: WeakTypeTag]: Tree = {
    val tpe = weakTypeOf[T].normalize
    val value =
      tpe match {
        case ConstantType(c: Constant) => Literal(c)

        case SingleType(p, v) if !v.isParameter => mkAttributedRef(p, v)

        case SingletonSymbolType(c) => mkSingletonSymbol(c)

        case _ =>
          c.abort(c.enclosingPosition, s"Type argument $tpe is not a singleton type")
      }
    mkWitness(tpe, value)
  }

  def extractResult[T](t: Expr[T])(mkResult: (Type, Tree) => Tree): Tree =
    (t.actualType, t.tree) match {
      case (tpe @ ConstantType(c: Constant), _) =>
        mkResult(tpe, Literal(c))

      case (tpe @ SingleType(p, v), tree) if !v.isParameter =>
        mkResult(tpe, tree)

      case (SymTpe, LiteralSymbol(s)) =>
        mkResult(SingletonSymbolType(s), mkSingletonSymbol(s))

      case _ =>
        c.abort(c.enclosingPosition, s"Expression ${t.tree} does not evaluate to a constant or a stable value")
    }

  def convertImpl[T](t: Expr[T]): Tree = extractResult(t)(mkWitness)

  def inferInstance(tci: Type): Tree = {
    val i = c.inferImplicitValue(tci)
    if(i == EmptyTree)
      c.abort(c.enclosingPosition, s"Unable to resolve implicit value of type $tci")
    i
  }

  def convertInstanceImpl1[TC[_], T](t: Expr[T])
    (implicit tcTag: WeakTypeTag[TC[_]]): Tree =
      extractResult(t) { (sTpe, value) =>
        val tc = tcTag.tpe.typeConstructor
        val wwTC = typeOf[WitnessWith[Nothing]].typeConstructor
        val parent = appliedType(wwTC, List(tc))
        val tci = appliedType(tc, List(sTpe))
        val i = inferInstance(tci)
        mkWitnessWith(parent, sTpe, value, i)
      }

  def convertInstanceImpl2[H, TC2[_ <: H, _], S <: H, T](t: Expr[T])
    (implicit tc2Tag: WeakTypeTag[TC2[_, _]], sTag: WeakTypeTag[S]): Tree =
      extractResult(t) { (sTpe, value) =>
        val tc2 = tc2Tag.tpe.typeConstructor
        val s = sTag.tpe

        val parent = weakTypeOf[WitnessWith[({ type λ[X] = TC2[S, X] })#λ]].map {
          case TypeRef(prefix, sym, args) if sym.isFreeType =>
            typeRef(NoPrefix, tc2.typeSymbol, args)
          case tpe => tpe
        }

        val tci = appliedType(tc2, List(s, sTpe))
        val i = inferInstance(tci)
        mkWitnessWith(parent, sTpe, value, i)
      }

  def mkSingletonOps(t: Expr[Any]): Tree =
    extractResult(t) { (tpe, tree) => mkOps(tpe, mkWitness(tpe, tree)) }

  def narrowSymbol[S <: String : WeakTypeTag](t: Expr[scala.Symbol]): Tree = {
    (weakTypeOf[S], t.tree) match {
      case (ConstantType(Constant(s1: String)), LiteralSymbol(s2)) if s1 == s2 =>
        mkSingletonSymbol(s1)
      case _ =>
        c.abort(c.enclosingPosition, s"Expression ${t.tree} is not an appropriate Symbol literal")
    }
  }

  def witnessTypeImpl(tpeSelector: c.Tree): c.Tree = {
    val q"${tpeString: String}" = tpeSelector
    val tpe =
      parseLiteralType(tpeString)
        .getOrElse(c.abort(c.enclosingPosition, s"Malformed literal $tpeString"))

    typeCarrier(tpe)
  }
}

object SingletonTypeMacros {
  import syntax.SingletonOps

  def inst(c: Context) = new SingletonTypeMacros[c.type](c)

  def materializeImpl[T: c.WeakTypeTag](c: Context): c.Expr[Witness.Aux[T]] =
    c.Expr[Witness.Aux[T]](inst(c).materializeImpl[T])

  def convertImpl[T](c: Context)(t: c.Expr[T]): c.Expr[Witness.Lt[T]] = 
    c.Expr[Witness.Lt[T]](inst(c).convertImpl(t))

  def convertInstanceImpl1[TC[_], T](c: Context)(t: c.Expr[T])
    (implicit tcTag: c.WeakTypeTag[TC[_]]):
      c.Expr[WitnessWith.Lt[TC, T]] = c.Expr[WitnessWith.Lt[TC, T]](inst(c).convertInstanceImpl1[TC, T](t))

  def convertInstanceImpl2[H, TC2[_ <: H, _], S <: H, T](c: Context)(t: c.Expr[T])
    (implicit tc2Tag: c.WeakTypeTag[TC2[_, _]], sTag: c.WeakTypeTag[S]):
      c.Expr[WitnessWith.Lt[({ type λ[X] = TC2[S, X] })#λ, T]] =
        c.Expr[WitnessWith.Lt[({ type λ[X] = TC2[S, X] })#λ, T]](inst(c).convertInstanceImpl2[H, TC2, S, T](t))

  def mkSingletonOps(c: Context)(t: c.Expr[Any]): c.Expr[SingletonOps] =
    c.Expr[SingletonOps](inst(c).mkSingletonOps(t))

  def narrowSymbol[S <: String : c.WeakTypeTag](c: Context)(t: c.Expr[scala.Symbol]):
    c.Expr[scala.Symbol @@ S] = c.Expr[scala.Symbol @@ S](inst(c).narrowSymbol[S](t))

  def witnessTypeImpl(c: Context)(tpeSelector: c.Expr[String]): c.Expr[Any] =
    c.Expr[Any](inst(c).witnessTypeImpl(tpeSelector.tree))
}
