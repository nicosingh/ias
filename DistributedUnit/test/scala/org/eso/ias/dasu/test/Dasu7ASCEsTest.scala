package org.eso.ias.dasu.test

import org.scalatest.FlatSpec
import java.nio.file.FileSystems
import org.eso.ias.cdb.CdbReader
import org.eso.ias.cdb.json.CdbJsonFiles
import org.eso.ias.cdb.json.JsonReader
import org.eso.ias.dasu.Dasu
import org.eso.ias.dasu.publisher.OutputListener
import org.eso.ias.dasu.publisher.ListenerOutputPublisherImpl
import org.eso.ias.dasu.publisher.OutputPublisher
import org.eso.ias.prototype.input.java.IasValueJsonSerializer
import org.ias.prototype.logging.IASLogger
import org.eso.ias.prototype.input.java.IASValue
import org.eso.ias.prototype.input.java.IasDouble
import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.java.IdentifierType
import org.eso.ias.prototype.input.java.OperationalMode
import org.eso.ias.prototype.input.InOut
import org.eso.ias.prototype.input.JavaConverter
import org.eso.ias.dasu.subscriber.InputsListener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import org.eso.ias.prototype.input.java.IASTypes
import org.eso.ias.prototype.input.java.IasAlarm
import org.eso.ias.prototype.input.java.AlarmSample
import org.eso.ias.prototype.input.java.IasValidity._

/**
 * Test the DASU with 7 ASCEs (in 3 levels).
 * There i sno special meaning between connections as this is a test 
 * to check if the flow of inputs and outputs is hendled correctly.
 * However this also shows a way to reuse transfer functions (multiplicity
 * and threshold are part of the ComputingElemnt) plus a user defined one,
 * the AverageTempsTF.
 * In a real case we probably do not want to have so many ASCEs but
 * it is perfectly allowed.
 * 
 * The logs of the test show the topology of the ASCEs of this test.
 * At the bottom level, there is one ASCE, that takes all the for temperatures and returns their
 * average values. This is just to provide an example of a synthetic parameter.
 * At the middle there are 5 ASCEs that get a temperature and 
 * check it against the a threshold to generate alarms. One of such temperatures
 * is the average produced at level .
 * All 5 alarms produced by the ASCEs are sent in input to the last ASCE_AlarmsThreshold,
 * in the second and last level: this ASCE applies the multiplicity with a threshold of 3
 * 
 * The DASU takes in input 4 temperatures, calculates their average and produces an alarm
 * if at least three of them are out of the nominal range.
 * 
 * The configurations of DASU, ASCE, TF and IASIOs are all stored 
 * in the CDB folder.
 */
class Dasu7ASCEsTest extends FlatSpec with OutputListener {
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass);
  
  // Build the CDB reader
  val cdbParentPath =  FileSystems.getDefault().getPath(".");
  val cdbFiles = new CdbJsonFiles(cdbParentPath)
  val cdbReader: CdbReader = new JsonReader(cdbFiles)
  
  val dasuId = "DasuWith7ASCEs"
  
  val stringSerializer = Option(new IasValueJsonSerializer)
  val outputPublisher: OutputPublisher = new ListenerOutputPublisherImpl(this,stringSerializer)
  
  val inputsProvider = new TestInputSubscriber()
  
  // The DASU to test
  val dasu = new Dasu(dasuId,outputPublisher,inputsProvider,cdbReader)
  
  // The identifer of the monitor system that produces the temperature in input to teh DASU
  val monSysId = new Identifier("MonitoredSystemID",IdentifierType.MONITORED_SOFTWARE_SYSTEM)
  // The identifier of the plugin
  val pluginId = new Identifier("PluginID",IdentifierType.PLUGIN,monSysId)
  // The identifier of the converter
  val converterId = new Identifier("ConverterID",IdentifierType.CONVERTER,pluginId)
  
  // The ID of the temperature 1 monitor point in unput to ASCE-Temp1
  val inputTemperature1ID = new Identifier("Temperature1", IdentifierType.IASIO,converterId)
  // The ID of the temperature 1 monitor point in unput to ASCE-Temp1
  val inputTemperature2ID = new Identifier("Temperature2", IdentifierType.IASIO,converterId)
  // The ID of the temperature 1 monitor point in unput to ASCE-Temp1
  val inputTemperature3ID = new Identifier("Temperature3", IdentifierType.IASIO,converterId)
  // The ID of the temperature 1 monitor point in unput to ASCE-Temp1
  val inputTemperature4ID = new Identifier("Temperature4", IdentifierType.IASIO,converterId)
  
  /** The number of events receieved
   *  This is the number of output generated by the DASU
   *  after running the TF on all its ASCEs
   */
  val eventsReceived = new AtomicInteger(0)
  
  /** The number of JSON strings received */
  val strsReceived = new AtomicInteger(0)
  
  val iasValuesReceived = new ListBuffer[IASValue[_]]
  
  /** Notifies about a new output produced by the DASU */
  override def outputEvent(output: IASValue[_]) {
    logger.info("Event received from DASU: ",output.id)
    eventsReceived.incrementAndGet();
    iasValuesReceived.append(output)
  }
  
  /** Notifies about a new output produced by the DASU 
   *  formatted as String
   */
  override def outputStringifiedEvent(outputStr: String) = {
    logger.info("JSON output received: [{}]", outputStr)
    strsReceived.incrementAndGet();
  }
  
  def buildValue(id: String, fullRunningID: String, d: Double): IASValue[_] = {
    new IasDouble(
        d,
        System.currentTimeMillis(),
        OperationalMode.OPERATIONAL,
        UNRELIABLE,
        id,
        fullRunningID)
  }
  
  behavior of "The DASU"
  
  it must "produce outputs when receives sets of inputs" in {
    // Start the getting of events in the DASU
    dasu.start()
    logger.info("Submitting a set with only one temp {} in nominal state",inputTemperature1ID.id)
    val inputs: Set[IASValue[_]] = Set(buildValue(inputTemperature1ID.id, inputTemperature1ID.fullRunningID,0))
    // Submit the inputs but we do not expect any output before
    // the DASU receives all the inputs
    inputsProvider.sendInputs(inputs)
    println("Set submitted")
    assert(iasValuesReceived.size==0)
    
    // Submit a set with Temperature 1 in a non nominal state
    logger.info("Submitting a set with only one temp {} in NON nominal state",inputTemperature1ID.id)
    inputsProvider.sendInputs(Set(buildValue(inputTemperature1ID.id, inputTemperature1ID.fullRunningID,100)))
    println("Another empty set submitted")
    assert(iasValuesReceived.size==0)
    
    // Submit the other inputs and then we expect the DASU to
    // produce the output
    val setOfInputs: Set[IASValue[_]] = {
      val v1=buildValue(inputTemperature1ID.id, inputTemperature1ID.fullRunningID,5)
      val v2=buildValue(inputTemperature2ID.id, inputTemperature2ID.fullRunningID,6)
      val v3=buildValue(inputTemperature3ID.id, inputTemperature3ID.fullRunningID,7)
      val v4=buildValue(inputTemperature4ID.id, inputTemperature4ID.fullRunningID,8)
      Set(v1,v2,v3,v4)
    }
    inputsProvider.sendInputs(setOfInputs)
    assert(iasValuesReceived.size==1)
    val outputProducedByDasu = iasValuesReceived.last
    assert(outputProducedByDasu.valueType==IASTypes.ALARM)
    assert(outputProducedByDasu.value.asInstanceOf[AlarmSample]== AlarmSample.CLEARED)
    
    //Submit a new set of inputs to trigger the alarm in the output of the DASU
    val setOfInputs2: Set[IASValue[_]] = {
      val v1=buildValue(inputTemperature1ID.id, inputTemperature1ID.fullRunningID,100)
      val v2=buildValue(inputTemperature2ID.id, inputTemperature2ID.fullRunningID,100)
      val v3=buildValue(inputTemperature3ID.id, inputTemperature3ID.fullRunningID,100)
      val v4=buildValue(inputTemperature4ID.id, inputTemperature4ID.fullRunningID,8)
      Set(v1,v2,v3,v4)
    }
    inputsProvider.sendInputs(setOfInputs2)
    assert(iasValuesReceived.size==2)
    val outputProducedByDasu2 = iasValuesReceived.last
    assert(outputProducedByDasu2.valueType==IASTypes.ALARM)
    assert(outputProducedByDasu2.value.asInstanceOf[AlarmSample]== AlarmSample.SET)
  }
  
}