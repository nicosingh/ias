package org.eso.ias.component.test

import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.InOut
import scala.collection.mutable.{Map => MutableMap }
import org.eso.ias.plugin.OperationalMode
import org.eso.ias.prototype.input.Validity
import org.eso.ias.prototype.input.java.IASTypes
import org.eso.ias.prototype.input.java.IdentifierType

/**
 * A common helper class to build data structures for testing
 *
 * @param dasuId: The ID of the DASU where the ASCE runs (to build the iD)
 * @param asceId: The ID of the ASCE
 * @param outputId: the ID of the output HIO
 * @param outputType: the type of the output
 * @param inputTypes: the type of the inputs used to generate the inputs
 */
class CommonCompBuilder(
    dasuId: String,
    asceId: String,
    outputId: String,
    outputType: IASTypes,
    inputTypes: List[IASTypes]) {
  require(Option[String](dasuId).isDefined)
  require(Option[String](asceId).isDefined)
  require(Option[String](outputId).isDefined)
  require(Option[IASTypes](outputType).isDefined)
  require(Option[List[IASTypes]](inputTypes).isDefined)

  // The thread factory used by the setting to async
  // initialize and shutdown the TF objects
  val threadFactory = new TestThreadFactory()

  // The ID of the DASU where the components runs
  val dasId = new Identifier(Some[String](dasuId), Some[IdentifierType](IdentifierType.DASU),None)

  // The ID of the component running into the DASU
  val compID = new Identifier(Some[String](asceId), Some[IdentifierType](IdentifierType.ASCE),Option[Identifier](dasId))

  // The refresh rate of the component
  val mpRefreshRate = InOut.MinRefreshRate + 500

  // The ID of the output generated by the component
  val outId = new Identifier(Some[String](outputId), Some(IdentifierType.IASIO), None)
  // Build the HIO in output
  val output = InOut(
    outId,
    mpRefreshRate,
    outputType)

  // The Map of inputs
  val inputsMPs = MutableMap[String,InOut[_]]()
  
  // Create the IASIOs in input
  var i = 0
  for (hioType <- inputTypes) {
    val id = new Identifier(Some[String]("INPUT-HIO-ID#"+i), Some(IdentifierType.IASIO),None)
    i = i + 1
    val hio = InOut(id, mpRefreshRate, hioType)
    inputsMPs += (hio.id.id.get -> hio)
  }
     
  val requiredInputIDs: List[String] = inputsMPs.keys.toList
  
  /**
   * Auxiliary constructor to help instantiating a component
   * with the given number of inputs of the same type
   * 
   * @param dasuId: The ID of the DASU where the ASCE runs (to build the iD)
   * @param asceId: The ID of the ASCE
   * @param outputId: the ID of the output HIO
   * @param outputType: the type of the output
   * @param numOfInputs: the number of inputs to generate
   * @param hioType: the type of the inputs
   */
  def this(
    dasuId: String,
    asceId: String,
    outputId: String,
    outputType: IASTypes,
    numOfInputs: Int,
    hioType: IASTypes) {
    this(dasuId,asceId,outputId, outputType,List.fill(numOfInputs)(hioType))
  }
}
