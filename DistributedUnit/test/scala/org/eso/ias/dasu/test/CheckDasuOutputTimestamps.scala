package org.eso.ias.dasu.test

import org.scalatest.FlatSpec
import org.scalatest.BeforeAndAfter
import org.eso.ias.dasu.DasuImpl
import org.eso.ias.prototype.input.java.IASValue
import org.ias.prototype.logging.IASLogger

/**
 * Checks the timestamps of the output produced by a DASU
 * when inputs change and when the auto-refresh is in place.
 * 
 * The test uses the DasuWithOneASCE DASU defined in the CDB
 * by submitting inputs and checking the fields of output
 * published (or not published by the DASU.
 * 
 * @see  [[https://github.com/IntegratedAlarmSystem-Group/ias/issues/52 Issue #52 on github]]
 */
class CheckDasuOutputTimestamps extends FlatSpec with BeforeAndAfter {
  
  /** The logger */
  val logger = IASLogger.getLogger(this.getClass);
  
  val autoRefreshTime = 1000L
  
  val f = new DasuOneAsceCommon(autoRefreshTime)
  
  before {
    f.outputValuesReceived.clear()
    f.outputValuesReceived.clear()
    f.dasu = f.buildDasu()
    f.dasu.get.start()
  }
  
  after {
    f.dasu.get.cleanUp()
    f.dasu = None
    f.outputValuesReceived.clear()
    f.outputValuesReceived.clear()
  }
  
  behavior of "The auto-resend of the last output of the DASU"
  
  it must "not refresh the output before getting the input" in {
    f.dasu.get.enableAutoRefreshOfOutput(true)
    // Leave the DASU time to send the last computed output
    Thread.sleep(5*autoRefreshTime)
    assert(f.outputStringsReceived.isEmpty)
    assert(f.outputValuesReceived.isEmpty)
  }
  
  it must "refresh the output once it has been generated" in {
    f.dasu.get.enableAutoRefreshOfOutput(true)
    val inputs: Set[IASValue[_]] = Set(f.buildValue(0))
    f.inputsProvider.sendInputs(inputs)
    
    // Leave the DASU time to send the last computed output
    Thread.sleep(5*autoRefreshTime)
    
    assert(f.outputStringsReceived.size>=5)
    assert(f.outputValuesReceived.size>=5)
  }
  
  it must "enable/disable the auto-refresh" in {
    f.dasu.get.enableAutoRefreshOfOutput(true)
    val inputs: Set[IASValue[_]] = Set(f.buildValue(0))
    f.inputsProvider.sendInputs(inputs)
    
    // Leave the DASU time to send the last computed output
    Thread.sleep(5*autoRefreshTime)
    
    assert(f.outputStringsReceived.size>=5)
    assert(f.outputValuesReceived.size>=5)
    
    f.dasu.get.enableAutoRefreshOfOutput(false)
    f.outputStringsReceived.clear()
    f.outputValuesReceived.clear()
    
    // Leave the DASU time to send the last computed output
    Thread.sleep(3*autoRefreshTime)
    assert(f.outputStringsReceived.isEmpty)
    assert(f.outputValuesReceived.isEmpty)
    
    f.dasu.get.enableAutoRefreshOfOutput(true)
    Thread.sleep(3*autoRefreshTime)
    assert(!f.outputStringsReceived.isEmpty)
    assert(!f.outputValuesReceived.isEmpty)
    
    f.dasu.get.enableAutoRefreshOfOutput(false)
    f.outputStringsReceived.clear()
    f.outputValuesReceived.clear()
    
    // Leave the DASU time to send the last computed output
    Thread.sleep(3*autoRefreshTime)
    assert(f.outputStringsReceived.isEmpty)
    assert(f.outputValuesReceived.isEmpty)
  }
  
  it must "re-send the same output" in {
     f.dasu.get.enableAutoRefreshOfOutput(true)
    val inputs: Set[IASValue[_]] = Set(f.buildValue(0))
    f.inputsProvider.sendInputs(inputs)
    
    // Leave the DASU time to send the last computed output
    Thread.sleep(5*autoRefreshTime)
    
    assert(f.outputStringsReceived.size>=5)
    assert(f.outputValuesReceived.size>=5)
    
    f.dasu.get.enableAutoRefreshOfOutput(false)
    
    f.outputStringsReceived.foreach( s =>
      logger.info("String received [{}]",s))
    
    val strOutput = f.outputStringsReceived.head
    assert(f.outputStringsReceived.forall(s => s==strOutput))
     
    val firstValue =  f.outputValuesReceived.head
    assert(f.outputValuesReceived.forall(iasVal => iasVal.value==firstValue.value))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.timestamp==firstValue.timestamp))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.mode==firstValue.mode))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.iasValidity==firstValue.iasValidity))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.id==firstValue.id))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.fullRunningId==firstValue.fullRunningId))
    assert(f.outputValuesReceived.forall(iasVal => iasVal.valueType==firstValue.valueType))
     
  }
}