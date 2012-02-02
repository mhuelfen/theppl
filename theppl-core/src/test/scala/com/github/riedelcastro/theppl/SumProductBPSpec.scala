package com.github.riedelcastro.theppl

/**
 * @author sriedel
 */
class SumProductBPSpec extends ThePPLSpec {

  describe("A Sum Product BP implementation") {
    it("should match results of exact inference on a tree") {
      val values = IndexedSeq('x1, 'x2)
      case class Var(name: Symbol) extends Variable[Symbol] {
        def domain = values
      }
      val A = Var('A)
      val B = Var('B)
      val C = Var('C)
      val D = Var('D)
      
      val vars = Seq(A,B,C,D)

      class EdgePotential(x: Var, y: Var) extends LinearModel  {
        def features(state: State) = new ParameterVector(Feat(state(x), state(y)))
        def hidden = IndexedSeq(x, y)
        val weights = new ParameterVector()
        override def toString = (x,y).toString()
      }
      val AB = new EdgePotential(A, B)
      val BC = new EdgePotential(B, C)
      val BD = new EdgePotential(B, D)
      AB.weights(Feat('x1, 'x1)) = 1.0
      BC.weights(Feat('x1, 'x1)) = 1.0
      BD.weights(Feat('x1, 'x1)) = 1.0

      val sum = new FeatureSumModel {

        def featureArgs = IndexedSeq(AB, BC, BD)
        def otherArgs = IndexedSeq.empty
      }

      val brute = BruteForceExpectator.expectator(sum)
      val bp = SumProductBPRecipe.expectator(sum)

      val bfExp = brute.expectations()
      val bpExp = bp.expectations()

      (bfExp.featureExpectations - bpExp.featureExpectations).norm1 must be (0.0 plusOrMinus eps)

      for (v <- vars)
        (bfExp.logMarginals.message(v) - bpExp.logMarginals.message(v)).norm1 must be (0.0 plusOrMinus eps)

      bfExp.logZ must be (bpExp.logZ plusOrMinus eps)






    }
  }

}