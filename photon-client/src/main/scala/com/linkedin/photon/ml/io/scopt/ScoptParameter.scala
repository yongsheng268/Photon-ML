/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.io.scopt

import org.apache.spark.ml.param.{Param, ParamMap}
import scopt.{OptionDef, Read}

/**
 * A Scopt wrapper around a [[Param]] for parsing it from the command line, and writing it out in the same format.
 *
 * @tparam In The type of the command line argument when parsed by Scopt
 * @tparam Out The type of the underlying [[Param]]
 * @param param The base [[Param]] being wrapped
 * @param parse The function to parse the parameter value from a basic Scopt type
 * @param update The function to update the value of the parameter in the [[ParamMap]] if it's specified multiple times
 * @param print The function to print multiple parameter values in Scopt-parseable format
 * @param usageText Usage text for the parameter
 * @param additionalDocs Additional documentation, in addition to the documentation of the base parameter
 * @param isRequired Whether the parameter must be included within the command line args
 * @param isUnbounded Whether the parameter can occur an unlimited number of times
 * @param read An implicit [[scopt.Read]] to parse the command line argument
 */
class ScoptParameter[In, Out] private (
    param: Param[Out],
    parse: (In) => Out,
    update: (Out, Out) => Out,
    print: (Out) => Seq[String],
    usageText: String,
    additionalDocs: Seq[String],
    isRequired: Boolean,
    isUnbounded: Boolean)
    (implicit val read: Read[In]) {

  import ScoptParameter._

  /**
   * Create a new [[OptionDef]] to parse this parameter from the command line using Scopt.
   *
   * @param f A function to create a new [[OptionDef]] (provided by an [[scopt.OptionParser]])
   * @return A new [[OptionDef]], configured using the various input arguments
   */
  def toOptionDef(f: (String) => OptionDef[In, ParamMap]): OptionDef[In, ParamMap] = {

    // Alternative way to specify whether a parameter is required
    val minOccurrences = if (isRequired) 1 else 0
    // Alternative way to specify unbound parameter limit
    val maxOccurrences = if (isUnbounded) Int.MaxValue else 1
    val text = param.doc.concat("\n" + additionalDocs.map(doc => s"$EXAMPLE_BUFFER$doc").mkString("\n"))

    f(param.name.replace(" ", "-"))
      .text(text)
      .valueName(usageText)
      .minOccurs(minOccurrences)
      .maxOccurs(maxOccurrences)
      .action { (x, c) =>
        c.get(param) match {
          case Some(existing) => c.put(param, update(parse(x), existing))
          case None => c.put(param, parse(x))
        }
      }
  }
}

object ScoptParameter {

  // 27 blank spaces: the buffer for multiline documentation in 2-column Scopt rendering
  private val EXAMPLE_BUFFER: String = " " * 27

  /**
   * Helper function to generate a [[ScoptParameter]].
   *
   * @note Having both a 'print' and 'printSeq' is an unfortunate ease-of-use requirement due to how irritable Scala is
   *       about multiple very similar apply functions.
   * @tparam In The type of the command line argument when parsed by Scopt
   * @tparam Out The type of the underlying [[Param]]
   * @param param The base [[Param]] being wrapped
   * @param parse The function to parse the parameter value from a basic Scopt type
   * @param updateOpt The function to update the value of the parameter in the [[ParamMap]] if it's specified multiple
   *                  times
   * @param print The function to print the parameter value in Scopt-parseable format
   * @param printSeq The function to print multiple parameter values in Scopt-parseable format
   * @param usageText Usage text for the parameter
   * @param additionalDocs Additional documentation, in addition to the documentation of the base parameter
   * @param isRequired Whether the parameter must be included within the command line args
   * @param read An implicit [[scopt.Read]] to parse the command line argument
   * @return A new [[ScoptParameter]]
   */
  def apply[In, Out](
      param: Param[Out],
      parse: (In) => Out = identity _,
      updateOpt: Option[(Out, Out) => Out] = None,
      print: (Out) => String = anyToString _,
      printSeq: (Out) => Seq[String] = seqWrapper(anyToString),
      usageText: String = "",
      additionalDocs: Seq[String] = Seq(),
      isRequired: Boolean = false)
      (implicit read: Read[In]): ScoptParameter[In, Out] =
    new ScoptParameter(
      param,
      parse,
      updateOpt.getOrElse(errorOnUpdate),
      if (updateOpt.isDefined) printSeq else seqWrapper(print),
      usageText,
      additionalDocs,
      isRequired,
      updateOpt.isDefined)

  /**
   * Identity function, as a convenience for [[ScoptParameter.parse]]. If the [[ScoptParameter]] In and Out types are
   * identical, no parsing is necessary.
   *
   * @tparam T Some input type (in practice, assumed to be the same as V)
   * @tparam V Some input type (in practice, assumed to be the same as T)
   * @param input Some input
   * @return The given input
   */
  private def identity[T, V](input: T): V = input.asInstanceOf[V]

  /**
   * Erroneous update function, as a convenience for [[ScoptParameter.update]]. If the [[ScoptParameter]] does not have
   * a custom update function, this one will be used and cause an error if update is called.
   *
   * @tparam T Some input type
   * @param parsedInput A newly computed input
   * @param existingInput The previously computed input
   * @return N/A
   * @throws UnsupportedOperationException always, when called
   */
  private def errorOnUpdate[T](parsedInput: T, existingInput: T): T =
    throw new UnsupportedOperationException(s"Cannot parse multiple inputs for parameter")

  /**
   * Convert any object to [[String]].
   *
   * @tparam T Some input type
   * @param output Some input object
   * @return The [[String]] representation of the input object
   */
  private def anyToString[T](output: T): String = output.toString

  /**
   * Wrap the results of a print function in a [[Seq]].
   *
   * @tparam T Some input type
   * @param printFunc Some print function
   * @return A function which uses the provided print function to print a value, then wraps the result in a [[Seq]]
   */
  private def seqWrapper[T](printFunc: (T) => String): (T) => Seq[String] = (input: T) => Seq(printFunc(input))
}
