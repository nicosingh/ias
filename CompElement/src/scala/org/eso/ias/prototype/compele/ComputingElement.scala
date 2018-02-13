package org.eso.ias.prototype.compele

import org.eso.ias.types.Identifier
import org.eso.ias.types.InOut
import org.eso.ias.types.Validity
import scala.util.control.NonFatal
import scala.collection.mutable.HashMap
import org.eso.ias.prototype.transfer.JavaTransfer
import scala.collection.mutable.{Map => MutableMap}
import org.eso.ias.types.IASTypes
import java.util.Properties
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import org.eso.ias.prototype.transfer.TransferFunctionSetting
import org.eso.ias.utils.ISO8601Helper
import org.eso.ias.prototype.transfer.TransferFunctionLanguage
import org.eso.ias.types.IdentifierType
import org.eso.ias.cdb.pojos.AsceDao
import scala.collection.JavaConverters
import org.eso.ias.cdb.pojos.IasioDao
import org.eso.ias.types.IASValue
import org.ias.logging.IASLogger
import org.eso.ias.cdb.pojos.TFLanguageDao
import org.eso.ias.prototype.transfer.ScalaTransfer
import org.eso.ias.types.JavaConverter
import org.eso.ias.types.OperationalMode
import org.eso.ias.types.IasValidity

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
 * @param _output: The output generated by this Component after applying the script to the inputs
 *                 It can or cannot be an AlarmValue
 * @param initialInputs: The initial set of all the possible inputs.
 * @param tfSetting: The definition of the implementation of the transfer function
 *                   that manipulates the inputs to produce the new output
 * @param props: the java properties to pass to this component and the TF
 * 
 * @see AlarmSystemComponent
 * @author acaproni
 */
abstract class ComputingElement[T](
    val asceIdentifier: Identifier,
    private var _output: InOut[T],
    initialInputs: Set[InOut[_]],
    val tfSetting: TransferFunctionSetting,
    val props: Properties) {
  require(Option(asceIdentifier)!=None,"Invalid identifier")
  require(asceIdentifier.idType==IdentifierType.ASCE)
  require(Option(initialInputs)!=None && !initialInputs.isEmpty,"Invalid (empty or null) set of required inputs to the component")
  require(Option(props).isDefined,"Invalid null properties")
  require(Option(_output).isDefined,"Initial output cannot be null")
  
  assert(initialInputs.forall(_.fromIasValueValidity.isDefined))
  assert(_output.fromIasValueValidity.isEmpty)
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  /** The ID of the ASCE */
  val id = asceIdentifier.id
  
  logger.info("Building ASCE [{}] with running id {}",id,asceIdentifier.fullRunningID)
  
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
  lazy val isOutputASyntheticParam = output.iasType!=IASTypes.ALARM
  
  /**
   * <code>true</code> if this component generates an alarm
   */
  lazy val isOutputAnAlarm = !isOutputASyntheticParam
  
  /** Getter for the private _output */
  def output: InOut[T] = _output
  
  logger.info("ASCE [{}] built",id)
  
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
      actualState: ComputingElementState): Tuple2[InOut[T], ComputingElementState] = {
    
    assert(actualState.canRunTF(),"Wrong state "+actualState.toString+" to run the TF")
    
    val startedAt=System.currentTimeMillis()
    
    val ret = try {
      transfer(immutableMapOfInputs,asceIdentifier,actualOutput)
    } catch { case e: Exception => Left(e) }
    
    val endedAt=System.currentTimeMillis()
    
    ret match {
      case Left(ex) =>
        logger.error("TF of [{}] inhibited for the time being",asceIdentifier,ex)
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
    state = if (tfSetting.initialize(id, asceIdentifier.runningID, props)) {
      ComputingElementState.transition(state, new Initialized())
    } else { 
      ComputingElementState.transition(state, new Broken())
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
   * trigger the execution of the TF to create a new output.
   * If some of the inputs of the ASCE is not yet initialized, it has a null/empty
   * value and the ASCE cannot run the TF neither produce the output.
   * 
   * @param iasValues the new inputs received from the DASU
   * @return A tuple with 3 fields:
   * 			   1) the new output generated applying the TF to the inputs and the new state of the ASCE
   *            The output is None if at least one of the inputs has not yet been initialized
   *         2) the actual validity
   *         3) the state of the ASCE
   */
  def update(iasValues: Set[IASValue[_]]): Tuple3[InOut[T],Validity, AsceStates.State] = {
    require(Option(iasValues).isDefined,"Set of inputs not defined")
    
    // Check if the passed set of IASIOs contains at least one IASIO that is 
    // not accepted by the ASCE
    assert(
        !iasValues.exists(iasio => !acceptedInputIds.contains(iasio.id)),
        "Received at least one IASIO that does not belong to this ASCE: "+iasValues.map(_.id).mkString(","))
        
    // Does the passed set contains 2 IASIOs with the same ID?
    assert(!iasValues.exists(i => iasValues.count(_.id==i.id)>1),"There cant 2 IASIOs with the same ID in the passed set of inputs!")
    
    // Updates the map with the passed IASIOs
    iasValues.foreach(iasVal => inputs.get(iasVal.id).map( _.update(iasVal)).foreach(i => inputs.put(i.id.id,i)))
    
    // If all the inputs have been initialized, change the state to be able to run the TF
    if (state.actualState==AsceStates.InputsUndefined && inputs.values.forall(i => i.value.isDefined)) {
      state = ComputingElementState.transition(state, new InputsInitialized())
    }
    
    // Apply the transfer function to the inputs
    val (newOut, newState) = if (state.canRunTF()) {
      lastOutputUpdateTStamp=System.currentTimeMillis()
      transfer(Map.empty++inputs,output,state)
    } else {
      ( output,state)
    }
    state=newState
    _output=newOut
    
    val validity = _output.getValidity(Some(inputs.values.toSet))
    
    (output, validity, state.actualState)
  }
  
  /**
   * Return the last computed output and its validity updated
   * with the current validity of the inputs.
   * 
   * @return the output and its validity
   */
  def getOutput(): Tuple2[InOut[_], Validity] = {
    (output, output.getValidity(Some(inputs.values.toSet)))
  }
}
/**
 * The ComputingElement object to build a ComputingElement from the AsceDao red from the CDB
 */
object ComputingElement {
  
  /**
   * build a ComputingElement from the AsceDao red from the CDB
   * 
   * @param asceDao the configuration of the ASCE red from the CDB
   * @param dasuId the identifier of the DASU where the ASCE runs
   * @param properties a optional set of properties
   */
  def apply[T](
      asceDao: AsceDao, 
      dasuId: Identifier, 
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
    val out = InOut[T](
        outputId,
        asceDao.getOutput.getRefreshRate,
        IASTypes.fromIasioDaoType(asceDao.getOutput.getIasType))
    logger.info("ComputingElement {} produces output {}",asceDao.getId,outputId.id)
        
    val inputsIasioDaos: Set[IasioDao] = JavaConverters.collectionAsScalaIterable(asceDao.getInputs).toSet
    val reqInputs: Set[String] = inputsIasioDaos.map(_.getId)
    logger.info("ComputingElement {} inputs are [{}]",asceDao.getId,reqInputs.mkString(", "))
    
    val tfLanguage = if (asceDao.getTransferFunction.getImplLang==TFLanguageDao.JAVA) TransferFunctionLanguage.java else TransferFunctionLanguage.scala
    val tfSettings = new TransferFunctionSetting(
        asceDao.getTransferFunction.getClassName,
        tfLanguage, new CompEleThreadFactory(asceId.fullRunningID))
    
    // Builds the initial set of inputs: all the InOut at the beginning have a null value
    val initialIasios: Set[InOut[_]] = inputsIasioDaos.map(iDao => 
      new InOut(
          None,
          System.currentTimeMillis(),
          new Identifier(iDao.getId,IdentifierType.IASIO,None),
          iDao.getRefreshRate,
          OperationalMode.UNKNOWN,
          Some(Validity(IasValidity.UNRELIABLE)),
          IASTypes.fromIasioDaoType(iDao.getIasType())))
    
    if (tfLanguage==TransferFunctionLanguage.java) new ComputingElement[T](asceId,out, initialIasios, tfSettings, properties) with JavaTransfer[T]
    else new ComputingElement[T](asceId,out, initialIasios, tfSettings, properties) with ScalaTransfer[T]
  }

}
