package org.eso.ias.cdb.topology

import com.typesafe.scalalogging.Logger
import org.eso.ias.logging.IASLogger

/**
  * The trait that defines a producer of an output.
  *
  * ASCEs and DASUs are producer: they have a set of inputs and produce
  * the output even if in different ways:
  * - the ASCE by the applying the transfer function
  * - DASU by propagating the inputs to the ASCEs
  *
  * To check if there are cycles, CyclesFinder build all the possible paths
  * from the inputs to the out collecting at each step, the output already produced.
  * A cycle is found if a producer generates an output that has already been produced.
  */
trait OutputProducer {

  /** The ID of the producer i.e. the ID of an ASCE or a DASU */
  val id: String

  /** The IDs of the inputs to produce the output */
  val inputsIds: Set[String]

  /** The ID of the output */
  val outputId: String
}

/**
  * CyclesFinder provides a method to look for cycles generated by producers
  * (i.e. the ASCEs running in a DASU or the DASUs running in the IAS)
  *
  *
  * @author acaproni
  */
object CyclesFinder {
  /** The logger */
  val logger: Logger = IASLogger.getLogger(CyclesFinder.getClass)

  /**
    * Check if the producers create cycles
    *
    *  The method repeats the same test for each input
    *
    * @param inputsIds th eIDs of the inputs of the producer
    * @param producers th eproducer of the outputs
    *
    * @return true if the producer is acyclic and false if contains a cycle
    */
  def isACyclic(inputsIds: Set[String], producers: Set[OutputProducer]): Boolean = {
    require(Option(inputsIds).isDefined && inputsIds.nonEmpty)
    require(Option(producers).isDefined && producers.nonEmpty)

    /**
      * The check is done checking each input and the
      * ASCEs that need it. Then the output produced
      * by a ASCE is checked against the ASCEs that need it
      * and so on until there are no more ASCEs.
      * The ASCEs and input already checked are put in the acc
      * set.
      *
      * For a given output x of an ASCE, acc contains
      * a possible path input, output) of the ASCE that prodiuced it.
      * The acc is a path from the input to the output so there is a cycle if an output of an ASCE
      * is contained in acc.
      *
      * A cycle is resent if the passed input
      * is already present in the acc set.
      *
      * @param in The input to check
      * @param acc The accumulator
      */
    def isAcyclic(in: String, acc: Set[String]): Boolean = {
      // List of ASCEs that wants the passed input
      val prodsThatWantThisInput: Set[OutputProducer] = producers.filter(_.inputsIds.contains(in))

      // The outputs generated by all the producers that want this output
      val outputs: Set[String] = prodsThatWantThisInput.map(_.outputId)
      if (outputs.isEmpty) true
      else {
        outputs.forall(s => {
          val newSet = acc+in

          if (newSet.contains(s)) {
            CyclesFinder.logger.error("Cycle found for output {} and path {}",
              s,newSet.mkString(","))
          }
          !newSet.contains(s) && isAcyclic(s,newSet)
        })
      }
    }
    inputsIds.forall( inputId => isAcyclic(inputId, Set()))
  }
}
