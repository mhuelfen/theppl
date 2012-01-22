package com.github.riedelcastro.theppl.logic

import com.github.riedelcastro.theppl.{Variable, State}
import com.github.riedelcastro.theppl.util._


/**
 * A term evaluates to a value given a possible world/state.
 * @author sriedel
 */
trait Term[+V] {
  def eval(state: State): Option[V]
  def value(state: State) = eval(state).get
  def variables: Iterable[Variable[Any]]
}

case class Constant[+V](value: V) extends Term[V] {
  def eval(state: State) = Some(value)
  def variables = Seq.empty
}

trait Composite[T, This <: Composite[T, This]] extends Term[T] {
  def parts: Seq[Term[Any]]
  def variables = parts.flatMap(_.variables).toSet.toSeq
  def genericCreate(p: Seq[Term[Any]]): This
  def genericEval(p: Seq[Any]): T
  def eval(state: State) = {
    val partsEval = parts.map(_.eval(state))
    if (partsEval.exists(_.isEmpty)) None else Some(genericEval(partsEval.map(_.get)))
  }
  object Caster {
    implicit def cast[T](t: Any) = t.asInstanceOf[T]
  }
}

trait FunApp[R, F, This <: FunApp[R, F, This]] extends Composite[R, This] {
  def f: Term[Any]
  def args: Seq[Term[Any]]
  def parts = f +: args
  override def toString = f + args.mkString("(", ",", ")")

  def genericFunAppEval(f: F, args: Seq[Any]): R
  def genericEval(p: Seq[Any]) = genericFunAppEval(p(0).asInstanceOf[F], p.drop(1))


}

case class FunApp1[A1, R](f: Term[A1 => R],
                          a1: Term[A1])
  extends FunApp[R, A1 => R, FunApp1[A1, R]] {

  import Caster._

  def args = Seq(a1)
  def genericCreate(p: Seq[Term[Any]]) = FunApp1(p(0), p(1))
  def genericFunAppEval(f: A1 => R, args: Seq[Any]) = f(args(0))

}

case class FunApp2[A1, A2, R](f: Term[(A1, A2) => R],
                              a1: Term[A1], a2: Term[A2])
  extends FunApp[R, (A1, A2) => R, FunApp2[A1, A2, R]] {

  import Caster._

  def args = Seq(a1, a2)
  def genericCreate(p: Seq[Term[Any]]) = FunApp2(p(0), p(1), p(2))
  def genericFunAppEval(f: (A1, A2) => R, args: Seq[Any]) = f(args(0), args(1))
}


class UniqueVar[+T](name: String, val domain: Seq[T]) extends Variable[T] with Term[T] {
  override def toString = name
}

case class Dom[+T](name: Symbol, values: Seq[T]) extends Builder1[UniqueVar[T], Nothing] {
  private var count = 0
  def newName() = {count += 1; "x" + count}
  def argument = new UniqueVar[T](newName(), values)
  def built = sys.error("empty")
  override def toString = name.toString()

  def -->[R](range: Dom[R]) = (this, range)

}

case class TupleTerm2[T1, T2](arg1: Term[T1], arg2: Term[T2]) extends Composite[(T1, T2), TupleTerm2[T1, T2]] {

  import Caster._

  def parts = Seq(arg1, arg2)
  def genericCreate(p: Seq[Term[Any]]) = TupleTerm2(p(0), p(1))
  def genericEval(p: Seq[Any]) = (p(0), p(1))

}

object LogicPlayground {

  trait FunTerm1[A1, R] extends Term[A1 => R] {
    def apply(a: Term[A1]): FunApp1[A1, R] = FunApp1(this, a)
  }
  trait FunTerm2[A1, A2, R] extends Term[(A1, A2) => R] {
    def apply(a1: Term[A1], a2: Term[A2]): FunApp2[A1, A2, R] = FunApp2(this, a1, a2)
  }


  //  case class FunApp2[A1,A2, R](f:Term[(A1,A2)=>R],a1:Term[A1],a2:Term[A2]) extends Term[R] {
  //    def eval(state: State) = f.eval(state)(a1.value(state),a2.value(state))
  //    def variables = (f.variables ++ a1.variables ++ a2.variables).toSet.toSeq
  //  }
  //  case class FunApp3[A1,A2,A3, R](f:Term[(A1,A2,A3)=>R],a1:Term[A1],a2:Term[A2],a3:Term[A3]) extends Term[R] {
  //    def eval(state: State) = f.eval(state)(a1.value(state),a2.value(state),a3.value(state))
  //    def variables = (f.variables ++ a1.variables ++ a2.variables ++ a3.variables).toSet.toSeq
  //  }

  trait GroundAtom[R] extends Variable[R] {
    def range: Dom[R]
    def domain = range.values
  }

  case class GroundAtom1[A1, R](name: Symbol, a1: A1, range: Dom[R]) extends GroundAtom[R]
  case class GroundAtom2[A1, A2, R](name: Symbol, a1: A1, a2: A2, range: Dom[R]) extends GroundAtom[R]


  trait Pred[F, R] extends Term[F] {
    def genericMapping(args: Seq[Any]): Variable[R]
    def name: Symbol
    def domains: Seq[Dom[Any]]
    def variables = StreamUtil.allTuples(domains.map(_.values)).map(genericMapping(_))
    override def toString = name.toString()
    def c[T](t: Any) = t.asInstanceOf[T]
  }
  case class Pred1[A1, R](name: Symbol, dom1: Dom[A1], range: Dom[R] = Bools)
    extends FunTerm1[A1, R] with Pred[A1 => R, R] {

    def domains = Seq(dom1)
    def genericMapping(args: Seq[Any]) = mapping(c[A1](args(0)))
    def mapping(a1: A1) = GroundAtom1(name, a1, range)
    def eval(state: State) = Some((a1: A1) => state(mapping(a1)))
  }

  case class Pred2[A1, A2, R](name: Symbol, dom1: Dom[A1], dom2: Dom[A2], range: Dom[R] = Bools)
    extends FunTerm2[A1, A2, R] with Pred[(A1, A2) => R, R] {

    def domains = Seq(dom1, dom2)
    def genericMapping(args: Seq[Any]) = mapping(c[A1](args(0)), c[A2](args(1)))
    def mapping(a1: A1, a2: A2) = GroundAtom2(name, a1, a2, range)
    def eval(state: State) = Some((a1: A1, a2: A2) => state(mapping(a1, a2)))
  }


  def simplify[T](term: Term[T]): Term[T] = {
    term match {
      case FunApp1(Constant(f), Constant(a)) => Constant(f(a))
      case FunApp1(pred: Pred1[_, _], Constant(a)) => pred.mapping(a)
      case _ => term
    }
  }


  //alternative: introduce FunApp1, FunApp2[A1,A2,R](t:FunTerm2[A1,A2,R],a1:A1,a2:A2)
  //object Pred { def apply[D1,D2](dom1,dom2): FunTerm2[D1,D2,Boolean] }
  //Predicate fun term evaluates to a function that takes (d1,d2) values, maps them to a variable, and then returns
  //the value associated with this variable
  //val friends = Pred2(person,person)
  //val pred3 = Pred3(person,person,person)
  //friends(x,y)

  //  def $[T](t: T): Constant[T] = Constant(t)
  //  def $[A1,A2,R](f: (A1,A2)=>R):Constant[(A1,A2)=>R] = new Constant(f) with Function2[Term[A1],Term[A2],FunApp[(A1, A2),R]]{
  //    def apply(arg1:Term[A1],arg2:Term[A2]) = FunApp(this,TupleTerm2(arg1,arg2))
  //  }
  def $(i: Int) = Constant(i)
  def $[A, R](f: A => R) = new Constant(f) {
    def apply(a: Term[A]): FunApp1[A, R] = FunApp1(this, a)
  }

  def $2[A1, A2, R](f: Tuple2[A1, A2] => R) = new Constant(f) {
    def apply(a: (Term[A1], Term[A2])): FunApp1[(A1, A2), R] = FunApp1[Tuple2[A1, A2], R](this.asInstanceOf[Term[Tuple2[A1, A2] => R]], TupleTerm2(a._1, a._2))
  }

  def $22[A1, A2, R](f: (A1, A2) => R) = new Constant(f) with FunTerm2[A1, A2, R]

  case class Forall(variables: Seq[Variable[Any]], term: Term[Boolean]) extends Term[Boolean] {
    //todo: generate all states
    def eval(state: State) = {
      val results = State.allStates(variables).map(term.eval(_))
      if (results.exists(_.isEmpty)) None else Some(results.forall(_.get))
    }
  }

  def forall(builder: BuilderN[Variable[Any], Term[Boolean]]) = {
    Forall(builder.arguments, builder.built)
  }

  case class Dot(variables: Seq[Variable[Any]], term: Term[Double]) extends Term[Double] {
    //todo: evaluate for each state to get a real vector, then dot product with weights
    //todo: feat = (value1,value2,..)
    def eval(state: State) = null
  }

  def dot(builder: BuilderN[Variable[Any], Term[Double]]) = {
    null
  }

  //  implicit def toTT2[T1, T2](pair: (Term[T1], Term[T2])) = TupleTerm2(pair._1, pair._2)

  implicit def symbolToPredBuilder(name: Symbol) = {
    new AnyRef {
      def :=[T, R](doms: (Dom[T], Dom[R])) = Pred1(name, doms._1, doms._2)
      def :=[A1,A2,R](doms:((Dom[A1],Dom[A2]),Dom[R])) = Pred2(name, doms._1._1, doms._1._2, doms._2)
    }
  }

  trait BooleanTermBuilder {
    def arg1: Term[Boolean]
    def &&(arg2: Term[Boolean]) = And(arg1, arg2)

  }

  implicit def boolTermToBuilder(term: Term[Boolean]): BooleanTermBuilder = new BooleanTermBuilder {
    def arg1 = term
  }

  object And extends Constant((x: Boolean, y: Boolean) => x && y) with FunTerm2[Boolean, Boolean, Boolean] {
    override def toString = "and"
  }

  object Bools extends Dom('bools, Seq(true, false))


  def main(args: Array[String]) {
    val values = Range(0, 10)
    val add = (x: (Int, Int)) => x._1 + x._2
    //    val formula = for (x <- Dom(values); y <- Dom(values)) yield FunApp($(add), (x, y))
    val bools = Dom('bools, Seq(false, true))
    val test = for (x <- bools; y <- bools) yield x
    println(test.built)
    println(test.arguments)
    val f = forall {for (x <- bools; y <- bools) yield x}
    //$(add)($(1),$(2))
    println(f)
    val lt3 = (x: Int) => x < 3
    val lt = (x: Int, y: Int) => x < y
    val dom = Dom('dom, values)
    val test2 = forall {for (x <- dom) yield $(lt3)(x)}
    println(test2)
    println(forall {for (x <- dom; y <- dom) yield $22(lt)(x, y)})
    val persons = Dom('persons, Range(0, 10))
    val smokes = Pred1('smokes, persons, bools)
    val cancer = 'cancer := persons -> bools
    val friends = 'friends := (persons,persons) -> bools
    val test3 = forall {for (x <- persons) yield smokes(x)}
    println(test3)
    println(forall {for (x <- persons) yield And(cancer(x), smokes(x))})
    println(forall {for (x <- persons) yield cancer(x) && smokes(x)})
    println(forall {for (x <- persons) yield forall {for (y <- persons) yield cancer(x) && smokes(y)}})
    println(forall {for (x <- persons; y <- persons) yield friends(x,y)})

  }
}
