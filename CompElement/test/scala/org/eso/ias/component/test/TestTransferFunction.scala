package org.eso.ias.component.test

import org.scalatest.FlatSpec
import org.eso.ias.prototype.input.AlarmValue
import org.eso.ias.prototype.input.InOut
import org.eso.ias.prototype.input.Identifier
import org.eso.ias.plugin.OperationalMode
import org.eso.ias.prototype.input.Validity
import org.eso.ias.prototype.compele.ComputingElement
import org.eso.ias.prototype.input.java.IASTypes
import scala.collection.mutable.{Map => MutableMap }
import org.eso.ias.prototype.transfer.TransferFunctionSetting
import java.util.Properties
import org.eso.ias.prototype.transfer.TransferFunctionLanguage
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.eso.ias.prototype.compele.CompEleThreadFactory
import org.eso.ias.prototype.input.AlarmState
import org.eso.ias.prototype.compele.AsceStates
import org.eso.ias.prototype.transfer.JavaTransfer
import org.eso.ias.prototype.transfer.ScalaTransfer
import org.eso.ias.prototype.input.java.IdentifierType

class TestTransferFunction extends FlatSpec {
  
  /**
   * Builds a Component with a set of inputs to test the transfer method
   */
  trait CompBuilder {
    
    val numOfInputs = 5
    
    // The ID of the DAS where the components runs
    val dasId = new Identifier(Some[String]("DAS-ID"),Some(IdentifierType.DASU),None)
    
    // The ID of the component running into the DAS
    val compID = new Identifier(Some[String]("COMP-ID"),Some(IdentifierType.ASCE),Option[Identifier](dasId))
    
    // The refresh rate of the component
    val mpRefreshRate = InOut.MinRefreshRate+500
    
    // The ID of the output generated by the component
    val outId = new Identifier(Some[String]("OutputId"), Some(IdentifierType.IASIO),None)
    // Build the MP in output
    val alarmVal = new AlarmValue()
    val output: InOut[AlarmValue] = InOut[AlarmValue](
      Some(alarmVal),
      outId,
      mpRefreshRate, 
      OperationalMode.OPERATIONAL,
      Validity.Unreliable, IASTypes.ALARM)
      
    // The IDs of the monitor points in input 
    // to pass when building a Component
    val requiredInputIDs = (for (i <- 1 to numOfInputs)  yield ("ID"+i)).toList
    
    // Create numOfInputs MPs
    var i=0 // To create different types of MPs
    val inputsMPs: MutableMap[String, InOut[_]] = MutableMap[String, InOut[_]]()
    for (id <- requiredInputIDs) {
      val mpId = new Identifier(Some[String](id),Some(IdentifierType.IASIO),Option[Identifier](compID))
      i=i+1
      val mp = if ((i%2)==0) {
        val mpVal = new AlarmValue()
        InOut[AlarmValue](
          Some(mpVal),
          mpId,
          mpRefreshRate,
          OperationalMode.OPERATIONAL,
          Validity.Unreliable, IASTypes.ALARM)
      } else {
        val mpVal = 1L
        InOut[Long](
          Some(mpVal),
          mpId,
          mpRefreshRate,
          OperationalMode.OPERATIONAL,
          Validity.Unreliable, IASTypes.LONG)
      }
      inputsMPs+=(mp.id.id.get -> mp)
    }
    val threadFactory: CompEleThreadFactory = new CompEleThreadFactory("Test-runningId")
    
    // Instantiate on ASCE with a java TF implementation
    val javaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.TransferExecutorImpl",
        TransferFunctionLanguage.java,
        threadFactory)
    val javaComp: ComputingElement[AlarmValue] = new ComputingElement[AlarmValue](
       compID,
       output,
       requiredInputIDs,
       inputsMPs,
       javaTFSetting,
       Some[Properties](new Properties())) with JavaTransfer[AlarmValue]
    
    
    // Instantiate one ASCE with a scala TF implementation
    val scalaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.TransferExample",
        TransferFunctionLanguage.scala,
        threadFactory)
    val scalaComp: ComputingElement[AlarmValue] = new ComputingElement[AlarmValue](
       compID,
       output,
       requiredInputIDs,
       inputsMPs,
       scalaTFSetting,
       Some[Properties](new Properties())) with ScalaTransfer[AlarmValue]
    
     // Instantiate one ASCE with a scala TF implementation
    val brokenScalaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.ThrowExceptionTF",
        TransferFunctionLanguage.scala,
        threadFactory)
    val brokenTFScalaComp: ComputingElement[AlarmValue] = new ComputingElement[AlarmValue](
       compID,
       output,
       requiredInputIDs,
       inputsMPs,
       brokenScalaTFSetting,
       Some[Properties](new Properties())) with ScalaTransfer[AlarmValue]
  }
  
  behavior of "The Component transfer function"
  
  /**
   * This test checks if the validity is set to Reliable if all the
   * validities have this level.
   */
  it must "set the validity to the lower value" in new CompBuilder {
    val component: ComputingElement[AlarmValue] = javaComp
    javaComp.initialize(new ScheduledThreadPoolExecutor(2))
    assert(component.output.validity==Validity.Unreliable)
    
    val keys=inputsMPs.keys.toList.sorted
    keys.foreach { key  => {
      val changedMP = inputsMPs(key).updateValidity(Validity.Reliable)
      javaComp.inputChanged(Some(changedMP))
      } 
    }
    // Leave time to run the TF
    System.out.println("Giving time to run the TF...")
    Thread.sleep(3000)
    
    javaComp.shutdown()
    assert(component.output.validity==Validity.Reliable)
  }
  
  it must "run the java TF executor" in new CompBuilder {
    val stpe: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(5)
    javaComp.initialize(stpe)
    Thread.sleep(5000)
    // Change one input to trigger the TF
    val changedMP = inputsMPs(inputsMPs.keys.head).updateValidity(Validity.Reliable)
    javaComp.inputChanged(Some(changedMP))
    Thread.sleep(5000)
    javaComp.shutdown()
    println(javaComp.output.actualValue.toString())
    val alarm = javaComp.output.actualValue.value.get.asInstanceOf[AlarmValue]
    assert(alarm.alarmState==AlarmState.Active)
  }
  
  it must "run the scala TF executor" in new CompBuilder {
    val stpe: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(5)
    scalaComp.initialize(stpe)
    println("Sleeping")
    Thread.sleep(5000)
    // Change one input to trigger the TF
    val changedMP = inputsMPs(inputsMPs.keys.head).updateValidity(Validity.Reliable)
    scalaComp.inputChanged(Some(changedMP))
    Thread.sleep(5000)
    scalaComp.shutdown()
    
    println(scalaComp.output.actualValue.toString())
    val alarm = scalaComp.output.actualValue.value.get.asInstanceOf[AlarmValue]
    assert(alarm.alarmState==AlarmState.Active)
  }
  
  it must "detect a broken scala TF executor" in new CompBuilder {
    val stpe: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(5)
    brokenTFScalaComp.initialize(stpe)
    println("Sleeping")
    Thread.sleep(5000)
    assert(brokenTFScalaComp.getState()==AsceStates.Healthy)
    // Change one input to trigger the TF
    val changedMP = inputsMPs(inputsMPs.keys.head).updateValidity(Validity.Reliable)
    brokenTFScalaComp.inputChanged(Some(changedMP))
    Thread.sleep(5000)
    assert(brokenTFScalaComp.getState()==AsceStates.TFBroken)
    brokenTFScalaComp.shutdown()
  }
  
}
