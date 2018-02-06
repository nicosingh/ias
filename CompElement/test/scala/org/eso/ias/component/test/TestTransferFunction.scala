package org.eso.ias.component.test

import org.scalatest.FlatSpec
import org.eso.ias.prototype.input.InOut
import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.java.OperationalMode
import org.eso.ias.prototype.input.Validity
import org.eso.ias.prototype.compele.ComputingElement
import org.eso.ias.prototype.input.java.IASTypes
import scala.collection.mutable.{Map => MutableMap }
import org.eso.ias.prototype.transfer.TransferFunctionSetting
import java.util.Properties
import org.eso.ias.prototype.transfer.TransferFunctionLanguage
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.eso.ias.prototype.compele.CompEleThreadFactory
import org.eso.ias.prototype.compele.AsceStates
import org.eso.ias.prototype.transfer.JavaTransfer
import org.eso.ias.prototype.transfer.ScalaTransfer
import org.eso.ias.prototype.input.java.IdentifierType
import org.eso.ias.prototype.input.java.AlarmSample
import org.ias.prototype.logging.IASLogger
import org.eso.ias.prototype.input.java.IasValidity._
import org.eso.ias.prototype.input.java.IASValue
import org.eso.ias.prototype.input.JavaConverter
import org.eso.ias.prototype.input.java.IasValidity

class TestTransferFunction extends FlatSpec {
  
  def convert(iasios: Set[InOut[_]]): Set[IASValue[_]] = {
    iasios.map( io => 
      JavaConverter.inOutToIASValue(io: InOut[_],io.getValidity(None)))
  }
  
  /**
   * Builds a Component with a set of inputs to test the transfer method
   */
  trait CompBuilder {
    
    val numOfInputs = 5
    
    /** The ID of the DASU where the components runs */
    val supervId = new Identifier("SupervId",IdentifierType.SUPERVISOR,None)
    val dasId = new Identifier("DAS-ID",IdentifierType.DASU,supervId)
    
    /** The ID of the component running into the DASU */
    val compID = new Identifier("COMP-ID",IdentifierType.ASCE,Option(dasId))
    
    // The refresh rate of the component
    val mpRefreshRate = InOut.MinRefreshRate+500
    
    // The ID of the output generated by the component
    val outId = new Identifier("OutputId",IdentifierType.IASIO,Option(compID))
    
    // Build the MP in output
    // The inherited validity is undefined 
    val output: InOut[AlarmSample] = new InOut[AlarmSample](
      Option.empty,
      System.currentTimeMillis(),
      outId,
      mpRefreshRate, 
      OperationalMode.OPERATIONAL,
      None, IASTypes.ALARM)
      
    // The IDs of the monitor points in input 
    // to pass when building a Component
    val requiredInputIDs = (for (i <- 1 to numOfInputs)  yield ("ID"+i)).toList
    
    // Create numOfInputs MPs
    // with a inherited validity RELIABLE 
    var i=0 // To create different types of MPs
    val inputsMPs: MutableMap[String, InOut[_]] = MutableMap[String, InOut[_]]()
    for (id <- requiredInputIDs) {
      val mpId = new Identifier(id,IdentifierType.IASIO,Option(compID))
      i=i+1
      val mp = if ((i%2)==0) {
        new InOut[AlarmSample](
          Option.empty,
          System.currentTimeMillis(),
          mpId,
          mpRefreshRate,
          OperationalMode.OPERATIONAL,
          Some(Validity(IasValidity.RELIABLE)), 
          IASTypes.ALARM)
      } else {
        val mpVal = 1L
        new InOut[Long](
          Some(mpVal),
          System.currentTimeMillis(),
          mpId,
          mpRefreshRate,
          OperationalMode.OPERATIONAL,
          Some(Validity(IasValidity.RELIABLE)), 
          IASTypes.LONG)
      }
      inputsMPs+=(mp.id.id -> mp)
    }
    val threadFactory: CompEleThreadFactory = new CompEleThreadFactory("Test-runningId")
    
    // Instantiate on ASCE with a java TF implementation
    val javaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.TransferExecutorImpl",
        TransferFunctionLanguage.java,
        threadFactory)
    val javaComp: ComputingElement[AlarmSample] = new ComputingElement[AlarmSample](
       compID,
       output,
       inputsMPs.values.toSet,
       javaTFSetting,
       new Properties()) with JavaTransfer[AlarmSample]
    
    
    // Instantiate one ASCE with a scala TF implementation
    val scalaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.TransferExample",
        TransferFunctionLanguage.scala,
        threadFactory)
    val scalaComp: ComputingElement[AlarmSample] = new ComputingElement[AlarmSample](
       compID,
       output,
       inputsMPs.values.toSet,
       scalaTFSetting,
       new Properties()) with ScalaTransfer[AlarmSample]
    
     // Instantiate one ASCE with a scala TF implementation
    val brokenScalaTFSetting =new TransferFunctionSetting(
        "org.eso.ias.component.test.transfer.ThrowExceptionTF",
        TransferFunctionLanguage.scala,
        threadFactory)
    val brokenTFScalaComp: ComputingElement[AlarmSample] = new ComputingElement[AlarmSample](
       compID,
       output,
       inputsMPs.values.toSet,
       brokenScalaTFSetting,
       new Properties()) with ScalaTransfer[AlarmSample]
  }
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  behavior of "The Component transfer function"
  
  /**
   * This test checks if the validity is set to Reliable if all the
   * validities have this level.
   */
  it must "set the dependent validity to the lower validity of the inputs" in new CompBuilder {
    logger.info("Dependent validity test started")
    val component: ComputingElement[AlarmSample] = javaComp
    javaComp.initialize()
    assert(component.output.fromIasValueValidity.isEmpty, "The output does not inherit the validity from a IASValue" )
    
    val keys=inputsMPs.keys.toList.sorted
    val newChangedMp = inputsMPs.values.map(inout =>  inout.updatedInheritedValidity(Some(Validity(RELIABLE))))
    
    javaComp.update(convert(newChangedMp.toSet))
    assert(component.output.fromIasValueValidity.isEmpty)
    
    // Wait so that the inputs are not valid an
    Thread.sleep(2*mpRefreshRate)
    javaComp.update(convert(newChangedMp.toSet))
    assert(component.getOutput()._2==Validity(UNRELIABLE))
    assert(component.output.fromIasValueValidity.isEmpty)
    javaComp.shutdown()
  }
  
  it must "run the java TF executor" in new CompBuilder {
    assert(javaComp.initialize()==AsceStates.InputsUndefined)
    // Send all the possible inputs to check if the state changes and the ASCE runs the TF
    inputsMPs.keys.foreach( k => {
      val inout = inputsMPs(k)
      val newIasio = if (inout.iasType==IASTypes.ALARM) inout.updateValue(Option(AlarmSample.SET))
        else inout.updateValue(Option(-5L))
      inputsMPs(k)=newIasio
    })
    
    // Send all the inputs
    val result = javaComp.update(convert(inputsMPs.values.toSet))
    assert(result._3==AsceStates.Healthy)
    assert(result._1.value.isDefined)
    
    javaComp.shutdown()
    val out = javaComp.getOutput()._1
    assert(out.value.isDefined)
    logger.info("Actual value = {}",out.value.toString())
    val alarm = out.value.get.asInstanceOf[AlarmSample]
    assert(alarm==AlarmSample.SET)
  }
  
  it must "run the scala TF executor" in new CompBuilder {
    assert(scalaComp.initialize()==AsceStates.InputsUndefined)
    
    // Send all the possible inputs to check if the state changes and the ASCE runs the TF
    inputsMPs.keys.foreach( k => {
      val inout = inputsMPs(k)
      val newIasio = if (inout.iasType==IASTypes.ALARM) inout.updateValue(Option(AlarmSample.SET))
        else inout.updateValue(Option(-5L))
      inputsMPs(k)=newIasio
    })
    
    // Send the inputs
    val result = scalaComp.update(convert(inputsMPs.values.toSet))
    println("XXXX "+result._2.toString())
    assert(result._3==AsceStates.Healthy)
    
    scalaComp.shutdown()
    
    logger.info("Actual value = {}",scalaComp.output.value.toString())
    val alarm = scalaComp.output.value.get.asInstanceOf[AlarmSample]
    assert(alarm==AlarmSample.SET)
  }
  
  it must "detect a broken scala TF executor" in new CompBuilder {
    brokenTFScalaComp.initialize()
    assert(brokenTFScalaComp.getState()==AsceStates.InputsUndefined)
    // Send all the possible inputs to check if the state changes and the ASCE runs the TF
    inputsMPs.keys.foreach( k => {
      val inout = inputsMPs(k)
      val newIasio = if (inout.iasType==IASTypes.ALARM) inout.updateValue(Option(AlarmSample.SET))
        else inout.updateValue(Option(-5L))
      inputsMPs(k)=newIasio
    })
    
    // Send the inputs and get the result
    val result = brokenTFScalaComp.update(convert(inputsMPs.values.toSet))
    assert(result._3==AsceStates.TFBroken)
    assert(brokenTFScalaComp.getState()==AsceStates.TFBroken)
    
    brokenTFScalaComp.shutdown()
  }
  
}
