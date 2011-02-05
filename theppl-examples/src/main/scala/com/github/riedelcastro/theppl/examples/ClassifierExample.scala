package com.github.riedelcastro.theppl.examples

import com.github.riedelcastro.theppl._
import com.github.riedelcastro.theppl.util.Util
import io.Source
import ParameterVector._

/**
 * @author sriedel
 */
object ClassifierExample {

  def main(args: Array[String]) {

    val chunks = Seq("O") ++ (for (bi <- Seq("B-", "I-"); t <- Seq("VP", "NP", "PP")) yield bi + t)
    case class Token(index: Int, word: String, tag: String, chunk: String) {
      val chunkVar = new Atom[Token, String]('chunk, this)
    }
    val stream = Util.getStreamFromClassPathOrFile("com/github/riedelcastro/theppl/datasets/conll2000/train.txt")
    val indexedLines = Source.fromInputStream(stream).getLines.take(10).filter(_ != "").zipWithIndex
    val tokens = for ((line, index) <- indexedLines.toSeq; Array(word, tag, chunk) = line.split("\\s+")) yield
      Token(index, word, tag, chunk)
    val lifted = tokens.lift
    val training = for (token <- tokens) yield new Instance(token, State.singleton(token.chunkVar, token.chunk))

    val classifier = new Classifier[String, Token] with OnlineLearner {
      def labelFeatures(label: String) = fromFeats(Seq(Feat(label)) ++ label.split("-").map(Feat("split", _)))
      def contextFeatures(token: Token) = fromPairs("t" -> token.tag, "t-1" -> lifted(token.index - 1).map(_.tag))
      def variable(context: Token) = context.chunkVar
      val domain = chunks
    }
    println(Evaluator.evaluate(classifier, training))
    classifier.train(training)
    println(classifier.weights)
    println(Evaluator.evaluate(classifier, training))

  }

}

object Evaluator {

  def evaluate[C](module: Module {type Context = C}, instances: Seq[Instance[C]]) = {
    var totalLoss = 0.0
    var count = 0
    for (instance <- instances) {
      val gold = instance.gold
      val model = module.model(instance.context, instance.observation)
      val guess = model.predict
      for (hidden <- model.hidden) {
        if (gold(hidden) != guess(hidden)) totalLoss += 1.0
        count += 1
      }
    }
    totalLoss / count
  }

}