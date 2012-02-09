package com.github.riedelcastro.theppl.infer

import com.github.riedelcastro.theppl.util.HasLogger
import com.github.riedelcastro.theppl._

/**
 * An object that can calculate the argmax state for a given model.
 * @author sriedel
 */
trait Argmaxer extends HasModel {

  def argmax(penalties: Messages = Messages.empty): ArgmaxResult
  def predict = argmax(Messages.empty).state

}

/**
 * The result of an argmax call. Has the argmaxing state and its score.
 */
trait ArgmaxResult {
  def state: State
  def score: Double
}


trait ArgmaxRecipe[-ModelType <: Model] {
  def argmaxer(model: ModelType, cookbook: ArgmaxRecipe[Model] = Argmaxer): Argmaxer
}

object Argmaxer extends ArgmaxRecipe[Model] {

  def argmaxer(model: Model, cookbook: ArgmaxRecipe[Model]) = model.defaultArgmaxer(cookbook)
  def apply(model: Model, cookbook: ArgmaxRecipe[Model] = this) =
    cookbook.argmaxer(model, cookbook)
}

trait MinimumBayesRiskArgmaxer extends Argmaxer {

  def marginalizer: Marginalizer
  def threshold: Double

  def argmax(penalties: Messages) = {
    val marginals = marginalizer.marginalize(penalties).logMarginals
    new ArgmaxResult {
      lazy val score = model.score(state)
      lazy val state = new State {
        def get[V](variable: Variable[V]) =
          Some(marginals.message(variable).map(math.exp(_)).offsetDefault(-threshold).argmax)
      }
    }
  }
}

trait BruteForceArgmaxer extends Argmaxer with HasLogger {

  val model: Model

  def argmax(penalties: Messages) = {
    logger.trace("Bruteforce argmaxer used")
    val states = model.allStates
    def scored = states.map(state => Scored(state, model.penalizedScore(penalties, state)))
    val result = scored.maxBy(_.score)
    new ArgmaxResult {
      def state = result.value
      def score = result.score
    }
  }


}