package com.github.riedelcastro.theppl.apps.distant

import com.github.riedelcastro.theppl._
import infer._
import util.Util
import Util._
import math._


/**
 * @author sriedel
 */
trait DistantSupervisionTemplate[EntityType] extends LinearTemplate[EntityType] {
  self =>
  type MentionType
  type EntityVariableType <: Variable[Boolean]
  type PotentialType <: DistantSupervisionPotential

  val weights = new ParameterVector

  case class BoolVar[T](id: T) extends BoolVariable

  def entityVariable(entity: EntityType): EntityVariableType
  def mentions(entity: EntityType): Seq[MentionType]

  def entityFeatures(entity: EntityType): ParameterVector
  def mentionFeatures(mention: MentionType, entity: EntityType): ParameterVector

  trait DistantSupervisionPotential extends super.LinearPotential {

    def entity: EntityType

    lazy val hiddenEntity = entityVariable(entity)

  }


}


/**
 * @author sriedel
 */
trait DistantSupervisionClassifier[EntityType] extends DistantSupervisionTemplate[EntityType] {

  type PotentialType = ClassifierPotential

  trait ClassifierPotential extends DistantSupervisionPotential {

    lazy val hidden = Seq(hiddenEntity)
    def features(state: State) = {
      val result = new ParameterVector()
      if (state(hiddenEntity)) {
        result.add(entityFeatures(entity), 1.0)
        for (mention <- mentions(entity)) {
          result.add(mentionFeatures(mention, entity), 1.0)
        }
      }
      result
    }
  }

  def potential(c: EntityType) = new ClassifierPotential {
    def entity = c
  }

}

trait EntityMentionTemplate[EntityType] extends DistantSupervisionTemplate[EntityType] {
  template =>
  type MentionVariableType <: Variable[Boolean]

  def mentionVariable(mention: MentionType): MentionVariableType

  type PotentialType <: EntityMentionPotential

  trait EntityMentionPotential extends DistantSupervisionPotential {
    latentPotential =>

    lazy val mentions = template.mentions(entity).toIndexedSeq
    lazy val hiddenMentions = mentions.map(mentionVariable(_))
    lazy val hidden = (hiddenMentions :+ hiddenEntity)
    lazy val entFeats = entityFeatures(entity)
    lazy val feats = mentions.map(m => mentionFeatures(m, entity)).toArray

    def features(state: State) = {
      val result = new ParameterVector()
      if (state(hiddenEntity)) result.add(entFeats, 1.0)
      forIndex(mentions.size) {
        i => if (state(hiddenMentions(i))) result.add(feats(i), 1.0)
      }
      result
    }
  }

}
trait LatentDistantSupervisionTemplate[EntityType] extends EntityMentionTemplate[EntityType] {
  template =>


  type PotentialType = LatentPotential

  def mentionVariable(mention: MentionType): MentionVariableType

  def potential(context: EntityType) = new LatentPotential {def entity = context}

  def bidirectional = false

  trait LatentPotential extends EntityMentionPotential {
    latentPotential =>

    def check(state: State) = {
      if (bidirectional)
        hiddenMentions.exists(state(_)) == state(hiddenEntity)
      else
        !hiddenMentions.exists(state(_)) || state(hiddenEntity)
    }

    override def score(state: State) = {
      if (!check(state)) Double.NegativeInfinity else super.score(state)
    }

    override def defaultArgmaxer(cookbook: ArgmaxRecipe[Potential]) = new Argmaxer {
      val potential = latentPotential
      def argmax(penalties: Messages) = {
        val entScore = entFeats dot weights
        val scores = feats.map(_ dot weights)
        val n = mentions.size
        val mentionPenalties = hiddenMentions.map(m => penalties(m, true) - penalties(m, false)).toArray
        val entityPenalty = penalties(hiddenEntity, true) - penalties(hiddenEntity, false)
        val finalEntScore = entScore + entityPenalty
        val penalizedMentionScores = scores.zip(mentionPenalties).map(pair => pair._1 + pair._2)
        val activeMentions = penalizedMentionScores.map(_ > 0.0)


        //check whether the score of all active mentions is higher than the negative entity score
        //if so, the entity is active and we allow positive mentions, otherwise everything has to be inactive
        val activeMentionScore = penalizedMentionScores.view.filter(_ > 0.0).sum
        if (activeMentionScore > -finalEntScore) {
          new ArgmaxResult {
            def score = activeMentionScore + finalEntScore
            def state = State(potential.hiddenEntity +: potential.hiddenMentions, true +: activeMentions)
          }
        } else {
          new ArgmaxResult {
            def score = math.max(finalEntScore, 0.0)
            def state = State(potential.hiddenEntity +: potential.hiddenMentions, (finalEntScore > 0) +: Array.fill(n)(false))
          }
        }
      }
    }

    def atLeastOnceArgmax(gold: Boolean): State = {
      val scores = feats.map(_ dot weights)
      if (gold) {
        //we choose at least one mention to be active
        val mentionStates = scores.map(_ > 0.0)
        val maxMention = scores.indices.maxBy(scores(_))
        mentionStates(maxMention) = true
        State(this.hiddenEntity +: this.hiddenMentions, true +: mentionStates)
      } else {
        State(this.hiddenEntity +: this.hiddenMentions, false +: Array.fill(scores.size)(false))
      }
    }


    override def defaultExpectator(cookbook: ExpectatorRecipe[Potential]) = new Expectator {
      val potential = latentPotential

      def expectations(penalties: Messages) = {
        val entScore = entFeats dot weights
        val scores = feats.map(_ dot weights)
        val n = mentions.size
        val logZs = Array.ofDim[Double](n)
        val logMentionMargs = Array.ofDim[Double](n)

        //convert penalties into array
        val mentionPenaltiesTrue = hiddenMentions.map(m => penalties(m, true)).toArray
        val mentionPenaltiesFalse = hiddenMentions.map(m => penalties(m, false)).toArray
        val entityPenaltyTrue = penalties(hiddenEntity, true)
        val entityPenaltyFalse = penalties(hiddenEntity, false)

        var tmpZ = entScore + entityPenaltyTrue

        //log partition function and local log partition functions
        forIndex(n) {
          i =>
            logZs(i) = log1p(exp(scores(i) + mentionPenaltiesTrue(i)) + exp(mentionPenaltiesFalse(i)) - 1)
            tmpZ += logZs(i)
        }
        var lZ = log1p(exp(tmpZ) + exp(entityPenaltyFalse) - 1)

        //entity marginal
        var logEntMarg = tmpZ - lZ

        if (bidirectional) {
          //substract score of active entity but no active mentions
          val impossible = entScore + entityPenaltyTrue + mentionPenaltiesFalse.sum
          val oldLz = lZ
          lZ = log1p(exp(lZ) - exp(impossible) - 1)
          assert(!lZ.isInfinity)
          logEntMarg = log(exp(tmpZ) - exp(impossible)) - lZ
          assert(!logEntMarg.isNaN)
        }

        //mention marginals
        forIndex(n) {
          i =>
            logMentionMargs(i) = tmpZ - logZs(i) + scores(i) + mentionPenaltiesTrue(i) - lZ
        }

        //prepare messages---UGLY
        val mentionMsgs: Map[Variable[Any], Message[Boolean]] =
          Range(0, n).view.map(i => hiddenMentions(i) ->
            Message.binary(hiddenMentions(i), logMentionMargs(i), log1p(-exp(logMentionMargs(i))))).toMap
        val entityMsg = Message.binary(hiddenEntity, logEntMarg, log1p(-exp(logEntMarg)))
        val result = new Messages {
          def message[V](variable: Variable[V]) = variable match {
            case x if (x == hiddenEntity) => entityMsg.asInstanceOf[Message[V]]
            case m => mentionMsgs(m).asInstanceOf[Message[V]]
          }
          def variables =  mentionMsgs.keySet //todo: this should also contain the hiddenEntity
        }

        //calculate feature expectations
        def featExp() = {
          val result = new ParameterVector()
          result.add(entFeats, exp(logEntMarg))
          forIndex(n) {
            i =>
              result.add(feats(i), exp(logMentionMargs(i)))
          }
          result
        }

        new Expectations {
          lazy val featureExpectations = featExp()
          lazy val logMarginals = result
          def logZ = lZ
        }
      }
    }
  }

}

trait SomeFractionActive[EntityType] extends LatentDistantSupervisionTemplate[EntityType] {
  def fractionActive = 0.3

  def reliability(entity: EntityType): Double

  def howManyActive(entity: EntityType): Double = mentions(entity).size * fractionActive

  def entityFeatures(entity: EntityType) =
    ParameterVector.fromMap(Map(entity -> howManyActive(entity), ('target, entity) -> reliability(entity)))

  def mentionFeatures(mention: MentionType, entity: EntityType) =
    ParameterVector.fromMap(Map(entity -> -1.0))

}





