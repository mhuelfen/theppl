package com.github.riedelcastro.theppl

import org.scalatest.{Ignore, Spec}
import org.scalatest.matchers.MustMatchers

/**
 * @author sriedel
 */
trait ThePPLSpec extends Spec with MustMatchers {

  def eps = 0.0000001
  def epsLarge = 0.001


}