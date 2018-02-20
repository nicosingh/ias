package org.eso.ias.component.test

import org.scalatest.FlatSpec
import org.eso.ias.types.Identifier
import org.eso.ias.types.OperationalMode
import org.eso.ias.types.Validity
import scala.collection.mutable.HashMap
import org.eso.ias.types.IASTypes
import org.eso.ias.types.InOut
import scala.collection.mutable.{Map => MutableMap }
import org.eso.ias.asce.transfer.TransferFunctionSetting
import org.eso.ias.asce.transfer.TransferFunctionLanguage
import java.util.Properties
import org.eso.ias.asce.CompEleThreadFactory
import org.eso.ias.asce.ComputingElement
import org.eso.ias.asce.transfer.ScalaTransfer
import org.eso.ias.asce.transfer.JavaTransfer
import org.eso.ias.types.IdentifierType
import org.eso.ias.types.AlarmSample
import org.eso.ias.types.IASValue
import org.eso.ias.asce.AsceStates
import org.eso.ias.types.IasValidity._
import org.eso.ias.types.IasValidity

/**
 * Test the basic functionalities of the IAS Component,
 * while the functioning of the transfer function
 * is checked elsewhere.
 */
class TestComponent extends FlatSpec {
  
  // The ID of the DASU where the components runs
  val supervId = new Identifier("SupervId",IdentifierType.SUPERVISOR,None)
  val dasId = new Identifier("DAS-ID",IdentifierType.DASU,supervId)
  
  // The ID of the component to test
  val compId = new Identifier("ComponentId",IdentifierType.ASCE,Option[Identifier](dasId))
  
  // The ID of the output generated by the component
  val outId = new Identifier("OutputId",IdentifierType.IASIO,Option(compId))
  
  // The IDs of the monitor points in input 
  // to pass when building a Component
  val requiredInputIDs = List("ID1", "ID2")
  
  // The ID of the first MP
  val mpI1Identifier = new Identifier(requiredInputIDs(0),IdentifierType.IASIO,Option(compId))
  val mp1 = InOut[AlarmSample](mpI1Identifier,IASTypes.ALARM)
  
  // The ID of the second MP
  val mpI2Identifier = new Identifier(requiredInputIDs(1),IdentifierType.IASIO,Option(compId))
  val mp2 = InOut[AlarmSample](mpI2Identifier, IASTypes.ALARM)
  val actualInputs: Set[InOut[_]] = Set(mp1,mp2)
  
  behavior of "A Component"
  
  it must "catch an error instantiating a wrong TF class" in {
    val output = InOut[AlarmSample](outId,IASTypes.ALARM)
    
    val threadaFactory = new CompEleThreadFactory("Test-runninId")
    // A transfer function that does not exist
    val tfSetting =new TransferFunctionSetting(
        "org.eso.ias.asce.transfer.TransferExecutorImpl",
        TransferFunctionLanguage.java,
        threadaFactory)
    val comp: ComputingElement[AlarmSample] = new ComputingElement[AlarmSample](
       compId,
       output,
       actualInputs,
       tfSetting,
       new Properties()) with JavaTransfer[AlarmSample]
    
    assert(comp.asceIdentifier==compId)
    assert(comp.output.id==outId)
    
    // A newly created ASCE haa a state equal to Initializing
    assert(comp.getState()==AsceStates.Initializing)
    
    // After an error in the initialization, the state changes to TFBroken
    comp.initialize()
    assert(comp.getState()==AsceStates.TFBroken)
  }
  
  it must "correctly instantiate the TF" in {
    val output = InOut[AlarmSample](outId,IASTypes.ALARM)
    
    val threadaFactory = new CompEleThreadFactory("Test-runninId")
    // A transfer function that does not exist
    val tfSetting =new TransferFunctionSetting(
        "org.eso.ias.asce.transfer.impls.MultiplicityTF",
        TransferFunctionLanguage.java,
        threadaFactory)
    val comp: ComputingElement[AlarmSample] = new ComputingElement[AlarmSample](
       compId,
       output,
       actualInputs,
       tfSetting,
       new Properties()) with JavaTransfer[AlarmSample]
    
    assert(comp.asceIdentifier==compId)
    assert(comp.output.id==outId)
    
    // A newly created ASCE haa a state equal to Initializing
    assert(comp.getState()==AsceStates.Initializing)
    
    // After a correct initialization, the state changes to InputsUndefined
    comp.initialize()
    assert(comp.getState()==AsceStates.InputsUndefined)
  }
  
}
