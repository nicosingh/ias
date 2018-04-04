package org.eso.ias.tranfer

import org.eso.ias.asce.transfer.ScalaTransferExecutor
import java.util.Properties
import java.util.concurrent.TimeUnit

import org.eso.ias.types.AlarmSample
import org.eso.ias.types.InOut
import org.ias.logging.IASLogger
import scala.util.Try
import org.eso.ias.types.IASTypes

/**
 * The exception thrown by this TF in case of 
 * malfunctions
 */
class DelayedAlarmException(msg: String) extends Exception(msg)

/**
 * DelayedAlarm processes the alarm input and produces another alarm
 * if the alarm in input remains steady active for a given amount of time.
 * In the same way, the produced alarm is cleared if the input stays steady 
 * cleared for a given amount of time.
 * 
 * There is no default value for the delays: if they are not provided
 * the TF will throw an exception.
 * 
 * The actual implementation of the TF does not allow a ASCE to run a TF 
 * at a given time rate or after a delay elapsed.
 * This method does its best but will not be precise.
 * Issue #84 of the IAS core is about this problem.
 * The best way to get the alarm close to the required time frame
 * is to tune the refresh rate of the Supervisor where this TF is executed.
 * 
 * 
 * @author acaproni
 */
class DelayedAlarm(cEleId: String, cEleRunningId: String, props: Properties) 
extends ScalaTransferExecutor[AlarmSample](cEleId,cEleRunningId,props) {
  
  /**
   * Get the value of the passed property, if defined
   */
  def getValue(propName: String): Option[Long] = {
    require(Option(propName).isDefined && !propName.isEmpty())
    val propValueStr = Option(props.getProperty(propName))
    propValueStr.flatMap( str => { Try[Long](TimeUnit.MILLISECONDS.convert(str.toInt,TimeUnit.SECONDS)).toOption })
  }
  
  /**
   * The string with the delay (seconds) before setting the output if the input was set 
   */
  val waitTimeToSet: Option[Long] = getValue(DelayedAlarm.delayToSetTimePropName)
  
  /**
   * The  string with the delay (seconds) before setting the output if the input was set 
   */
  val waitTimeToClear: Option[Long] = getValue(DelayedAlarm.delayToClearTimePropName)
  
  /**
   * The point in time when the input changed its state
   * from SET to CLEAR or vice-versa
   */
  private var lastStateChangeTime: Long = 0L
  
  /**
   * The previously received alarm
   */
  private var lastInputValue: Option[AlarmSample]=None
  
  /**
   * Initialization: it basically checks if the 
   * provided delays are valid 
   * 
   * @see TransferExecutor#initialize()
   */
  override def initialize() {
    DelayedAlarm.logger.debug("Initializing TF of [{}]", cEleId)
    
    if (waitTimeToSet.isEmpty || waitTimeToClear.isEmpty) {
      throw new DelayedAlarmException("Time to set and/or time to clear properties not provided")
    }
    if (waitTimeToSet.get<0 || waitTimeToClear.get<0) {
      throw new DelayedAlarmException("Time range below 0: delayToSet="+waitTimeToSet.get+", delayToClear="+waitTimeToClear.get)
    }
    if (waitTimeToSet.get==0 && waitTimeToClear.get==0) {
      throw new DelayedAlarmException("No delays set: delayToSet=0, delayToClear==0")
    }
    DelayedAlarm.logger.debug("TF of [{}] initialized with delayToSet=[{}], delayToClear=[{}]", 
        cEleId, waitTimeToSet.get.toString(), waitTimeToClear.get.toString())
  }
  
  /**
   * @see TransferExecutor#shutdown()
   */
  override def shutdown() {
    DelayedAlarm.logger.debug("TF of [{}] shut down", cEleId)
  }
  
  /**
	 * Produces the output of the component by evaluating the inputs.
	 * 
	 * @return the computed output of the ASCE
	 */
	override def eval(compInputs: Map[String, InOut[_]], actualOutput: InOut[AlarmSample]): InOut[AlarmSample] = {
	  // Here waitTimeToClear and waitTimeToSet must be defined because if they are not
	  // an exception in thrown by initialize() and the execution of the TF never enabled
	  assert(waitTimeToClear.isDefined && waitTimeToSet.isDefined)
	  
	  
	  // This TF expects one and only one input
	  if (compInputs.values.size!=1) {
	    throw new DelayedAlarmException("Expected only 1 input but got "+compInputs.values.size)
	  }
	  
	  // Get the input
	  val iasio = compInputs.values.head
    assert(iasio.value.isDefined)
	  
	  // Is the input an alarm?
    if (iasio.iasType!=IASTypes.ALARM) {
      throw new DelayedAlarmException("Input type is not alarm: "+iasio.iasType)
    }
	  
	  // Is the output an alarm?
    if (actualOutput.iasType!=IASTypes.ALARM) {
      throw new DelayedAlarmException("Output type is not alarm: "+actualOutput.iasType)
    }
	  
	  if (lastInputValue.isEmpty) {
	    // Initialization: ff the output was never activated then 
	    // return a CLEARED alarm because the delay did not elapsed
	    lastStateChangeTime=System.currentTimeMillis()
	    lastInputValue=Some(iasio.value.get.asInstanceOf[AlarmSample])
	    actualOutput.updateValue(Some(AlarmSample.CLEARED))
	  } else {
      
      // Did the input change?
      if (iasio.value.get!=lastInputValue.get) {
        lastInputValue=Some(iasio.value.get.asInstanceOf[AlarmSample])
        lastStateChangeTime = System.currentTimeMillis()
      } 
      
      val delayFromLastChange = System.currentTimeMillis()-lastStateChangeTime
      
      if (
          (actualOutput.value.get==AlarmSample.SET && delayFromLastChange<waitTimeToClear.get) ||
          (actualOutput.value.get==AlarmSample.CLEARED && delayFromLastChange<waitTimeToSet.get)) {
        // Not enough time elapsed from the last time the input changed: 
        // the output remains the same
        actualOutput
      } else {
        // enough time elapsed without changes in the input:
        // shell the output change?
        if (iasio.value.get==actualOutput.value.get) {
          actualOutput
        } else {
          actualOutput.updateValue(Some(iasio.value.get))
        }
      }
	  }
	}
}

object DelayedAlarm {
  
  /**
   * The logger
   */
  val logger = IASLogger.getLogger(DelayedAlarm.getClass)
  
  /**
   * The time to wait (seconds) before setting the alarm
   */
  val delayToSetTimePropName = "org.eso.ias.tf.delaiedthreshold.settime"
  
  /**
   * The time to wait (seconds) before clearing the alarm
   */
  val delayToClearTimePropName = "org.eso.ias.tf.delaiedthreshold.cleartime"
  
}