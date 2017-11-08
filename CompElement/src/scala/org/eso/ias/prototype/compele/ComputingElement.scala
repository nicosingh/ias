package org.eso.ias.prototype.compele

import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.InOut
import org.eso.ias.prototype.input.Validity
import scala.util.control.NonFatal
import scala.collection.mutable.HashMap
import org.eso.ias.prototype.transfer.JavaTransfer
import scala.collection.mutable.{Map => MutableMap}
import org.eso.ias.prototype.input.java.IASTypes
import java.util.Properties
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import org.eso.ias.prototype.transfer.TransferFunctionSetting
import org.eso.ias.prototype.utils.ISO8601Helper
import org.eso.ias.prototype.transfer.TransferFunctionLanguage
import org.eso.ias.prototype.input.java.IdentifierType
import org.eso.ias.cdb.pojos.AsceDao
import scala.collection.JavaConverters
import org.eso.ias.cdb.pojos.IasioDao
import org.eso.ias.prototype.input.java.IASValue
import org.ias.prototype.logging.IASLogger

/**
 * The Integrated Alarm System Computing Element (ASCE) 
 * is the basic unit of the IAS. 
 * 
 * The ASCE is composed of an output, generated by the user provided
 * script applied to the inputs (i.e. monitor points and/or
 * other alarms): the ComputingElement changes its output when 
 * the input changes (for example the value of a monitor point changes).
 * 
 * The output of a ASCE is normally an alarm generated by digesting the values
 * of the inputs of the component itself. But sometimes, 
 * the output is a value of a given type (for example an integer) 
 * to implement what we called synthetic parameters. 
 * 
 * The user provides a JVM class in one of the supported languages
 * to digest the inputs and produce the output. 
 * The transfer function of the object is invoked at regular time intervals 
 * when the state of the inputs change. 
 * Which programming language the user wrote the object with
 * is stored in the configuration database; programmatically it is 
 * visible in the {@link TransferFunctionSetting} object.
 * Depending on the programming language, there might be some 
 * preparatory step before running the TF.
 * 
 * The class is abstract because the implementation of the transfer function 
 * depends on the programming language and must be mixed when instantiating the 
 * object.
 * 
 * The ASCE is a state machine (@see ComputingElementState) whose
 * state changes during the life time of the ASCE for example
 * after initialization or shutdown but also if the TF executor
 * reports errors or is too slow.
 * 
 * Objects of this class are mutable.
 * 
 * The id of the ASCE does not change over time unless the ASCE is relocated.
 * In such case a new ASCE must be built to correctly initialize
 * the classes implementing the transfer function. For the same reason,  
 * if the transfer function, implemented by the user changes, 
 * then a new ASCE must be built.
 * 
 * The update of the output is triggered by the DASU: when it sends the inputs,
 * the ASCE runs the TF and produces the new output.
 * The refresh rate associate to the IASIO in output of the ASCE is ignored by the
 * ASCE: it is the DASU that is in charge or producing its output 
 * respecting its refresh rate.
 * 
 * @param id: The unique ID of this Component
 * @param output: The output generated by this Component after applying the script to the inputs
 *                It can or cannot be an AlarmValue
 * @param requiredInputs: The IDs of the inputs that this component
 *                        needs to generate the output. The list does not change
 *                        during the life time of the component.
 * @param tfSetting: The definition of the implementation of the transfer function
 *                   that manipulates the inputs to produce the new output
 * @param props: the properties to pass to this component and the TF
 * 
 * @see AlarmSystemComponent
 * @author acaproni
 */
abstract class ComputingElement[T](
    val id: Identifier,
    var output: InOut[T],
    val requiredInputs: List[String],
    val tfSetting: TransferFunctionSetting,
    val props: Option[Map[String,String]]) 
    extends Runnable {
  require(Option(id)!=None,"Invalid identifier")
  require(id.idType==IdentifierType.ASCE)
  require(Option(requiredInputs)!=None && !requiredInputs.isEmpty,"Invalid (empty or null) list of required inputs to the component")
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  logger.info("ASCE [{}] built with running id {}",id.id,id.fullRunningID)
  
  /**
   * The programming language of this TF is abstract
   * because it depends on the of the transfer mixed in
   * when building objects of this class
   */
  val tfLanguage: TransferFunctionLanguage.Value
  
  /**
   * The point in time when this object has been created.
   */
  protected[compele] val creationTStamp = System.currentTimeMillis()
  
  /**
   * The point in time when the output of this computing element has been
   * updated for the last time.
   */
  protected[compele] var lastOutputUpdateTStamp = creationTStamp
  
  /**
   * The state of the ASCE
   */
  @volatile protected[compele] var state: ComputingElementState =  new ComputingElementState
  
  /**
   * The inputs that produced the output
   * 
   * When the DASU sends the last received IASIOs, this map is
   * updated with the new values and a new output is produced
   */
  val inputs: MutableMap[String, InOut[_]] = MutableMap()
  
  /**
   * <code>true</code> if this component produces 
   * a synthetic parameter instead of an alarm
   */
  lazy val isOutputASyntheticParam = output.iasType!=IASTypes.ALARM
  
  /**
   * <code>true</code> if this component generates an alarm
   */
  lazy val isOutputAnAlarm = !isOutputASyntheticParam
  
  /**
   * Update the output by running the user provided script/class against the inputs.
   * This is actually the core of the ASCE.
   * 
   * A change of the inputs means a change in at least one of
   * the inputs of the list. 
   * A change, in turn, can be a change  of 
   * - the value (or alarm) 
   * - validity
   * - mode 
   * 
   * The number of possible inputs of a ASCE does not change during the
   * life span of a component, what changes are the values,
   * validity or mode of the inputs.
   * 
   * The calculation of the input is ultimately delegated to the abstract 
   * #transfer(...) method whose implementation is provided by the user.
   * 
   * @param immutableMapOfInputs the immmutable map of inputs to run the TF
   * @param actualOutput the actual output of the ASCE
   * @return the new output and the state of the ASCE after running the TF
   * @see transfer(...)
   */
  private def transfer(
      immutableMapOfInputs: Map[String, InOut[_]], 
      actualOutput: InOut[T],
      actualState: ComputingElementState): (InOut[T], ComputingElementState) = {
    
    assert(actualState.canRunTF(),"Wrong state "+actualState.toString+" to run the TF")
    
    val startedAt=System.currentTimeMillis()
    
    val ret = try {
      transfer(immutableMapOfInputs,id,actualOutput)
    } catch { case e: Exception => Left(e) }
    
    val endedAt=System.currentTimeMillis()
    
    ret match {
      case Left(ex) =>
        logger.error("TF of [{}] inhibited for the time being",id,ex)
        // Change the state so that the TF is never executed again
        (actualOutput,ComputingElementState.transition(actualState, new Broken()))
      case Right(v) => 
          val newState = if (endedAt-startedAt>TransferFunctionSetting.MaxTolerableTFTime) {
            ComputingElementState.transition(actualState, new Slow())
          } else {
            ComputingElementState.transition(actualState, new Normal())
          }
          (v,newState)
    }
  }
  
  /**
   * Update the output by running the user provided script/class against the inputs
   * by stackable modifications (@see the classes mixed in the {@link AlarmSystemComponent}
   * class)
   * 
   * This method sets the validity of the output from the validity of its inputs.
   * 
   * @param inputs: The map of inputs 
   * @param id: the ID of this computing element
   * @param actualOutput: the actual output
   * @return The new output
   */
  def transfer(
      inputs: Map[String, InOut[_]], 
      id: Identifier,
      actualOutput: InOut[T]) : Either[Exception,InOut[T]]
  
  /**
   * Update the validity of the passed actualOutput  from the validity
   * of its inputs.
   * 
   * @param theInputs: The inputs, sorted by their IDs 
   * @param actualOutput: the actual output
   * @return The new output with the validity updated
   */
  private[this] def updateTheValidity(
      theInputs: MutableMap[String, InOut[_]], 
      actualOutput: InOut[T]) : InOut[T] = {
    System.out.println("ComputingElementBase.updateOutputWithValidity(...)")
    val newValidity = Validity.min(theInputs.values.map(_.validity).toList)
    actualOutput.updateValidity(newValidity)
  }
  
  override def toString() = {
    val outStr: StringBuilder = new StringBuilder("State of ASCE [")
    outStr.append(id.toString())
    outStr.append("] built at ")
    outStr.append(ISO8601Helper.getTimestamp(creationTStamp))
    outStr.append("\n>Output: ")
    outStr.append(output.toString())
    outStr.append("\n>Inputs: ")
    for (mp <- inputs) outStr.append(mp.toString())
    outStr.append("\n>Script: ")
    outStr.append(tfSetting)
    outStr.append("\n>Health: ")
    outStr.append(state)
    outStr.append("\n>ID of inputs: \n")
    outStr.append(requiredInputs.mkString(", "))
    outStr.toString()
  }
  
  /**
   * Initialize the object.
   * 
   * This method must be executed only once before running the TF.
   * 
   * If the initialization fails, the state of the object changes to
   * broken.
   * 
   * @return The state of the ASCE after the initialization
   */
  def initialize(): ComputingElementState = {
    assert(state.actualState==AsceStates.Initializing)
    try {
      tfSetting.initialize(id.id, id.runningID, props)
      state = ComputingElementState.transition(state, new Initialized())
    } catch { 
      case e: Exception => state = ComputingElementState.transition(state, new Broken())
    }
    state
  }
  
  /**
   * <code>shutdown>/code> must be executed to free the resources of a <code>ComputingElement</code>
   * that later on can be cleanly destroyed.
   * 
   * One of the tasks of this method is to stop the timer thread
   * to update the output i.e. to execute the transfer function.
   */
  def shutdown(): Unit = {
    state = ComputingElementState.transition(state, new Shutdown())
    tfSetting.shutdown()
    state = ComputingElementState.transition(state, new Close())
  }
  
  /**
   * Expose the actual state of this ASCE
   * 
   * @return The state of the ASCE
   */
  def getState(): AsceStates.State = state.actualState
  
  /**
   * The DASU sent a new set of inputs: they replace the old inputs and
   * trigger the execution of the TF to create a new output 
   * 
   * @param iasios: The new inputs received from the DASU
   * @return the new output generated applying the TF to the inputs
   */
  def update(iasios: List[InOut[_]]): (InOut[T], AsceStates.State) = {
    require(Option(iasios).isDefined)
    
    assert(
        !iasios.exists(iasio => !requiredInputs.contains(iasio.id.id)),
        "Received at least one IASIO that does not belong to this ASCE: "+iasios.map(_.id.id).mkString(","))
        
    // Updates the map with the passed IASIOs
    iasios.foreach(iasio => inputs.put(iasio.id.id, iasio))
    
    val (newOut, newState) = if (state.canRunTF()) {
      lastOutputUpdateTStamp=System.currentTimeMillis()
      transfer(Map.empty++inputs,output,state)
    } else {
      (output,state)
    }
    state=newState
    // Always update the validity
    output=updateTheValidity(inputs, newOut)  
    (output,state.actualState)
  }
  
}

//object ComputingElement {
//  
//  def apply[T](
//      asceDao: AsceDao, 
//      dasuId: Identifier, 
//      properties: Option[Properties]): ComputingElement[T] = {
//    require(Option(dasuId).isDefined,"Invalid null DASU identifier")
//    require(dasuId.idType==IdentifierType.DASU,"The ASCE runs into a DASU: wrong owner identifer passed "+dasuId.id)
//    require(Option(asceDao).isDefined,"Invalid ASCE configuration")
//    
//    // Build the ID of the ASCE
//    val asceId = new Identifier(asceDao.getId,IdentifierType.ASCE,Option(dasuId))
//    
//    // Build the output
//    val outputId = new Identifier(asceDao.getOutput.getId,IdentifierType.IASIO,Option(asceId))
//    val out = new InOut[T](
//        outputId,
//        asceDao.getOutput.getRefreshRate,
//        IASTypes.fromIasioDaoType(asceDao.getOutput.getIasType))
//        
//    val inputsIasioDaos: List[IasioDao] = JavaConverters.collectionAsScalaIterable(asceDao.getInputs).toList
//    val reqInputs: List[String] = inputsIasioDaos.map(_.getId)
//    
//  }
//  
//}
