package org.eso.ias.asce

import org.eso.ias.types.Identifier
import org.eso.ias.types.InOut
import org.eso.ias.types.Validity

import org.eso.ias.asce.transfer._

import scala.collection.mutable.{Map => MutableMap}
import org.eso.ias.types.IASTypes
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.Try
import scala.util.Failure
import scala.util.Success
import org.eso.ias.utils.ISO8601Helper
import org.eso.ias.types.IdentifierType
import org.eso.ias.cdb.pojos.AsceDao

import scala.collection.JavaConverters
import org.eso.ias.cdb.pojos.IasioDao
import org.eso.ias.types.IASValue
import org.eso.ias.logging.IASLogger
import org.eso.ias.cdb.pojos.TFLanguageDao
import org.eso.ias.asce.exceptions.ValidityConstraintsMismatchException

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
 * The inputs of the ASCE comes either from the BSDB (i.e. monitored systems or 
 * other DASUs) or from other ASCEs runing in the same DASU.
 * The initial set of inputs must contain all the possible inputs accepted by
 * a ASCE. Such inputs will likely have a empty value at the beginning but
 * they are needed for updating when the new input arrives.
 * Note that inputs not provided in the constructor are rejected as unknown
 * so it is mandatory to pass in the constructor all the possible inputs.  
 * 
 * The user provides a JVM class in one of the supported languages
 * to digest the inputs and produce the output. 
 * The transfer function of the object is invoked
 * when the state of the inputs change. 
 * Which programming language the user wrote the object with
 * is stored in the configuration database; programmatically it is 
 * visible in the TransferFunctionSetting object.
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
  * The ASCE monitors when the execution time of the TF is too slow for a given amount of time.
  * The slowness and the duration are specified in the TransferFunctionSetting class.
  * The ASCE logs a messasge if the execution of the TF is slower than the max allowed time;
  * if the slowness persists over the duration then the TF is marked as broken and will
  * not be executed anymore.
 * 
 * @param asceIdentifier: The identifier of this Component
 * @param _output: The output generated by this Component after applying the script to the inputs
 *                 It can or cannot be an AlarmValue
 * @param initialInputs: The initial set of all the possible inputs.
 * @param tfSetting: The definition of the implementation of the transfer function
 *                   that manipulates the inputs to produce the new output
 * @param validityThresholdSecs the threshold (seconds), including toelerance,
 *                              to set the validity of the output
 *                              taking into account when the timestamps of the inputs
 *                              and the constraint possibly set in the TF
 * @param props: the java properties to pass to this component and the TF
 * 
 * @author acaproni
 */
abstract class ComputingElement[T](
    val asceIdentifier: Identifier,
    private var _output: InOut[T],
    initialInputs: Set[InOut[_]],
    val tfSetting: TransferFunctionSetting,
    validityThresholdSecs: Int,
    val props: Properties) {
  require(Option(asceIdentifier).isDefined,"Invalid empty identifier")
  require(asceIdentifier.idType==IdentifierType.ASCE)
  require(Option(initialInputs).isDefined && initialInputs.nonEmpty,"Invalid (empty or null) set of required inputs to the component")
  require(Option(validityThreshold).isDefined,"Invalid empty validity threshold")
  require(validityThreshold>0, "Validity threshold must be greater then 0")
  require(Option(props).isDefined,"Invalid null properties")
  require(Option(_output).isDefined,"Initial output cannot be null")
  
  assert(initialInputs.forall(_.fromIasValueValidity.isDefined))
  assert(_output.fromIasValueValidity.isEmpty)

  /** The ID of the ASCE */
  val id: String = asceIdentifier.id
  
  /** 
   *  True if the DASU has been generated from a template,
   *  False otherwise 
   */
  lazy val fromTemplate: Boolean = asceIdentifier.fromTemplate
  
  /**
   * The number of the instance if the DASU has been generated
   * from a template; empty otherwise
   */
  lazy val templateInstance: Option[Int] = asceIdentifier.templateInstance
  
  /** The threshold (msecs) to evaluate the validity */
  lazy val validityThreshold: Long = TimeUnit.MILLISECONDS.convert(validityThresholdSecs, TimeUnit.SECONDS)

  ComputingElement.logger.info("Building ASCE [{}] with running id {}",id,asceIdentifier.fullRunningID)
  
  /**
   * The programming language of this TF is abstract
   * because it depends on the of the transfer mixed in
   * when building objects of this class
   */
  val tfLanguage: TransferFunctionLanguage.Value
  
  /**
   * The point in time when this object has been created.
   */
  protected[asce] val creationTStamp: Long = System.currentTimeMillis()
  
  /**
   * The point in time when the output of this computing element has been
   * updated for the last time.
   */
  protected[asce] var lastOutputUpdateTStamp: Long = creationTStamp
  
  /**
   * The state of the ASCE
   */
  @volatile protected[asce] var state: ComputingElementState =  new ComputingElementState
  
  /**
   * The inputs that produced the output.
   * This map is initially populated with IASIOs not yet initialized
   * that have a empty value.
   * 
   * When the DASU sends the last received IASIOs, this map is
   * updated with the new values and a new output is produced
   */
  val inputs: MutableMap[String, InOut[_]] = MutableMap()++initialInputs.map(iasio => iasio.id.id -> iasio)
  // Are there duplicate IDs?
  require(initialInputs.size==inputs.keySet.size,"The initial set of inputs contain duplicates!")
  
  /** The IDs of all and only the possible inputs of the ASCE */
  val acceptedInputIds: Set[String] = Set()++inputs.keySet
  
  /**
   * <code>true</code> if this component produces 
   * a synthetic parameter instead of an alarm
   */
  lazy val isOutputASyntheticParam: Boolean = output.iasType!=IASTypes.ALARM
  
  /**
   * <code>true</code> if this component generates an alarm
   */
  lazy val isOutputAnAlarm: Boolean = !isOutputASyntheticParam

  /** It is true when the execution tie of the TF is too slow */
  val isTooSlow = new AtomicBoolean(false)

  /**
    * The point in time when the TF begins to be slow calculating the output.
    */
  val tooSlowStartTime = new AtomicLong(0)

  ComputingElement.logger.info("ASCE [{}] built",id)
  
  /** 
   * Getter for the private _output
   * 
   * This method return the last calculated output.
   * 
   * Use getOutputWithUpdatedValidity() if you want the updated with an
   * updated validity.
   * 
   * @return the last calculated output  
   */
  def output: InOut[T] = synchronized { _output }
  
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
    
    val ret = transfer(immutableMapOfInputs,asceIdentifier,actualOutput)
    
    val endedAt=System.currentTimeMillis()
    
    ret match {
      case Success(v) => 
        val newState =
          if (endedAt-startedAt>TransferFunctionSetting.MaxTolerableTFTime) {
            // The TF has been too slow producing the output
            ComputingElement.logger.warn("TF of ASCE [{}] took too long to terminate: {} max allowed {}",
              id,
              endedAt-startedAt,
              TransferFunctionSetting.MaxTolerableTFTime)

            // Was it slow before?
            val wasSlow = isTooSlow.getAndSet(true)
            // Is slow since too long?
            val longerThanDuration = endedAt-tooSlowStartTime.get()>TransferFunctionSetting.MaxAcceptableSlowDurationMillis

            (wasSlow, longerThanDuration) match {
              case (false, _) =>
                // The exec. time was ok before: it remember when the TF started to be slow
                tooSlowStartTime.set(endedAt)
                ComputingElementState.transition(actualState, Slow())
              case (true, false) =>
                // Slow but still for not enough time to be stopped
                ComputingElementState.transition(actualState, Slow())
              case (true, true) =>
                // The TF has been too slow producing the output for a time interval
                // longer than the max allowed: teh TF is marked as broken
                ComputingElement.logger.error("TF of ASCE [{}] too slow for too long: marked ar broken!",id)
                ComputingElementState.transition(actualState, Broken())
            }
          } else {
            isTooSlow.getAndSet(false)
            ComputingElementState.transition(actualState, Normal())
          }

        ComputingElement.logger.debug("ASCE [{}] produced a new output [{}]: {} {} {}"
          ,id,
          v.id.id,
          v.mode,
          v.getValidity.iasValidity,
          v.value.getOrElse("NONE").toString)

        ComputingElement.logger.debug("ASCE [{}]: The inputs that produced the output are {}",id,immutableMapOfInputs.keySet.mkString(","))
          immutableMapOfInputs.values.foreach( input => {
            ComputingElement.logger.debug("ASCE [{}]: Input [{}]  {} {} {}",
              id,
              input.id.id,
              input.mode,
              input.getValidity.iasValidity,
              input.value.getOrElse("NONE").toString) })

        (v,newState)
      case Failure(ex) =>
        ComputingElement.logger.error("TF of [{}] inhibited for the time being due to failure",id,ex)
        // Change the state so that the TF is never executed again
        (actualOutput,ComputingElementState.transition(actualState, Broken()))
    }

  }
  
  /**
   * Update the output by running the user provided script/class (TF) against the inputs
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
      actualOutput: InOut[T]) : Try[InOut[T]]

  /**
    * Initialize the scala transfer function
    *
    * @param inputsInfo The IDs and types of the inputs
    * @param outputInfo The Id and type of thr output
    * @param instance the instance
    * @return
    */
  def initTransferFunction(inputsInfo: Set[IasioInfo],
                           outputInfo: IasioInfo,
                           instance: Option[Int]): Try[Unit]
  
  override def toString() = {
    val outStr: StringBuilder = new StringBuilder("State of ASCE [")
    outStr.append(id.toString)
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
    outStr.append(acceptedInputIds.mkString(", "))
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
  def initialize(): AsceStates.State = {
    assert(state.actualState==AsceStates.Initializing)

    val inputsInfo = acceptedInputIds.map(inId => new IasioInfo(inId,inputs(inId).iasType))
    val outputInfo = new IasioInfo(output.id.id,output.iasType)

    state = if (tfSetting.initialize(id, asceIdentifier.runningID, validityThreshold, props) &&
      initTransferFunction(inputsInfo,outputInfo,templateInstance).isSuccess) {
      ComputingElementState.transition(state, Initialized())
    } else { 
      ComputingElementState.transition(state, Broken())
    }
    state.actualState
  }
  
  /**
   * <code>shutdown>/code> must be executed to free the resources of a <code>ComputingElement</code>
   * that later on can be cleanly destroyed.
   * 
   * One of the tasks of this method is to stop the timer thread
   * to update the output i.e. to execute the transfer function.
   */
  def shutdown(): Unit = synchronized {
    state = ComputingElementState.transition(state, Shutdown())
    tfSetting.shutdown()
    state = ComputingElementState.transition(state, Close())
  }
  
  /**
   * Expose the actual state of this ASCE
   * 
   * @return The state of the ASCE
   */
  def getState(): AsceStates.State = synchronized {state.actualState }
  
  /**
   * Get the min validity of the passed InOut,
   * taking into account the constraints that it can have in
   * InOut.validityConstraint.
   * 
   * The method fails if at least one ID of the constraints does not
   * match with any of the inputs.
   * The constraints are set by the TF provided by the user and whose implementation 
   * is not known so it can potentially contain errors like a typo in one of the IDs
   * of constraints.
   * 
   * @param iasio the IASIO to calculate the validity of the input
   * @param inputs the inputs of the ASCE 
   * @return the validity from the inputs of the IASIO  
   */
  private def getMinValidityOfInputs(iasio: InOut[T], inputs: Iterable[InOut[_]]): Try[Validity] = {
    require(Option(iasio).isDefined)
    require(Option(inputs).isDefined)
    
    // Inputs must all have fromIasValueValidity defined
    assert(!inputs.exists(_.isOutput))
    
    // If there are constraints, discard the inputs whose ID 
    // is not contained in the constraints
    val selectedInputsByConstraint = 
      iasio.validityConstraint.map( set => inputs.filter(input => set.contains(input.id.id)))
      .getOrElse(inputs)
    
    if (iasio.validityConstraint.isDefined && selectedInputsByConstraint.size!=iasio.validityConstraint.get.size) {
      // There are constraints but the at least one ID of the constraints does not belong 
      // to any of the IDs of the IASIO in input
      Failure(new ValidityConstraintsMismatchException(
          id,
          iasio.validityConstraint.get,
          inputs.map(input => input.id.id)))
    } else {
      
      Success(Validity.minValidity(selectedInputsByConstraint.map(_.getValidityOfInputByTime(validityThreshold)).toSet))
    }
  }
  
  /**
   * The DASU sent a new set of inputs: they replace the old inputs and
   * trigger the execution of the TF to create a new output.
   * If some of the inputs of the ASCE is not yet initialized, it has a null/empty
   * value and the ASCE cannot run the TF neither produce the output.
   * 
   * @param iasValues the new inputs received from the DASU
   * @return A tuple with 2 fields:
   * 			   1) the new output generated applying the TF to the inputs and the new state of the ASCE
   *            It is None if at least one of the inputs has not yet been initialized
   *         2) the state of the ASCE
   */
  def update(iasValues: Set[IASValue[_]]): (Option[InOut[T]], AsceStates.State) = synchronized {
    require(Option(iasValues).isDefined,"Set of inputs not defined")
    
    // Check if the passed set of IASIOs contains at least one IASIO that is 
    // not accepted by the ASCE
    assert(
        iasValues.forall(iasio => acceptedInputIds.contains(iasio.id)),
        "Received at least one IASIO that does not belong to this ASCE: "+iasValues.map(_.id).mkString(","))
        
    // Does the passed set contains 2 IASIOs with the same ID?
    assert(!iasValues.exists(i => iasValues.count(_.id==i.id)>1),"There can't be 2 IASIOs with the same ID in the passed set of inputs!")
    
    // Updates the map with the passed IASIOs
    iasValues.foreach(iasVal => inputs.get(iasVal.id).map( _.update(iasVal)).foreach(i => inputs.put(i.id.id,i)))
    
    // If all the inputs have been initialized, change the state to be able to run the TF
    if (state.actualState==AsceStates.InputsUndefined && inputs.values.forall(i => i.value.isDefined)) {
      state = ComputingElementState.transition(state, InputsInitialized())
    }
    
    // Apply the transfer function to the inputs
    if (state.canRunTF()) {
      lastOutputUpdateTStamp=System.currentTimeMillis()
      val (newOut, newState) = transfer(Map.empty++inputs,output,state)
      state=newState
    
      // The validity of the output must be set to the min validity of its inputs
      //
      // Note that this validity *does* take into account the current
      // timestamp against the timestamp of the IASValues in inputs
      val minValidityOfInputs = getMinValidityOfInputs(newOut, inputs.values)
      minValidityOfInputs match {
        case Failure(cause) =>
          ComputingElement.logger.error("TF of [{}] inhibited for the time being",asceIdentifier,cause)
          state=ComputingElementState.transition(state, Broken())
        case Success(validitity) => 
          _output=newOut.updateFromIinputsValidity(validitity).updateDasuProdTStamp(System.currentTimeMillis())    
      }
      ( Some(output),state.actualState)
    } else {
      ( None,state.actualState)
    }
  }

}
/**
 * The ComputingElement object to build a ComputingElement from the AsceDao red from the CDB
 */
object ComputingElement {

  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  /**
   * build a ComputingElement from the AsceDao red from the CDB
   * 
   * @param asceDao the configuration of the ASCE red from the CDB
   * @param dasuId the identifier of the DASU where the ASCE runs
   * @param validityThresholdInSecs the time interval (secs) to check the validity
    *                               It includes the tolerance
   * @param properties a optional set of properties
   */
  def apply[T](
      asceDao: AsceDao, 
      dasuId: Identifier,
      validityThresholdInSecs: Int,
      properties: Properties): ComputingElement[T] = {
    require(Option(dasuId).isDefined,"Invalid null DASU identifier")
    require(dasuId.idType==IdentifierType.DASU,"The ASCE runs into a DASU: wrong owner identifer passed "+dasuId.id)
    require(Option(asceDao).isDefined,"Invalid ASCE configuration")
    require(Option(properties).isDefined,"Invalid properties")
    
    val logger = IASLogger.getLogger(ComputingElement.getClass)
    
    logger.info("ComputingElement factory setting up parameters for {}",asceDao.getId)
    
    // Build the ID of the ASCE
    val asceId = new Identifier(asceDao.getId,IdentifierType.ASCE,Option(dasuId))
    
    // Build the output
    val outputId = new Identifier(asceDao.getOutput.getId,IdentifierType.IASIO,Option(asceId))
    val out: InOut[T] = InOut.asOutput(outputId, IASTypes.fromIasioDaoType(asceDao.getOutput.getIasType))
    logger.info("ComputingElement {} produces output {}",asceDao.getId,outputId.id)
        
    val inputsIasioDaos: Set[IasioDao] = JavaConverters.collectionAsScalaIterable(asceDao.getInputs).toSet
    val reqInputs: Set[String] = inputsIasioDaos.map(_.getId)
    logger.info("ComputingElement {} inputs are [{}]",asceDao.getId,reqInputs.mkString(", "))
    
    val tfLanguage = if (asceDao.getTransferFunction.getImplLang==TFLanguageDao.JAVA) TransferFunctionLanguage.java else TransferFunctionLanguage.scala
    val tfSettings = new TransferFunctionSetting(
        asceDao.getTransferFunction.getClassName,
        tfLanguage, 
        dasuId.templateInstance,
        new CompEleThreadFactory(asceId.fullRunningID))
    
    // Builds the initial set of inputs: all the InOut at the beginning have a null value
    val initialIasios: Set[InOut[_]] = inputsIasioDaos.map(iDao => 
      InOut.asInput(
          new Identifier(iDao.getId,IdentifierType.IASIO,None),
          IASTypes.fromIasioDaoType(iDao.getIasType)))
    
    if (tfLanguage==TransferFunctionLanguage.java) new ComputingElement[T](
        asceId,out, 
        initialIasios, 
        tfSettings, 
        validityThresholdInSecs,
        properties) with JavaTransfer[T]
    else new ComputingElement[T](
        asceId,out, 
        initialIasios, 
        tfSettings, 
        validityThresholdInSecs,
        properties) with ScalaTransfer[T]
  }

}
