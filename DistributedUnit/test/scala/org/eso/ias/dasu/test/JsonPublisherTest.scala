package org.eso.ias.dasu.test

import org.scalatest.FlatSpec
import org.eso.ias.prototype.input.java.IasValueJsonSerializer
import org.eso.ias.dasu.Dasu
import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.java.IASValue
import org.eso.ias.cdb.json.CdbJsonFiles
import org.eso.ias.cdb.CdbReader
import java.io.FileWriter
import org.eso.ias.dasu.publisher.JsonWriterPublisher
import org.eso.ias.cdb.json.JsonReader
import org.ias.prototype.logging.IASLogger
import java.nio.file.FileSystems
import org.eso.ias.prototype.input.java.IasDouble
import org.eso.ias.prototype.input.java.IdentifierType
import org.eso.ias.plugin.OperationalMode
import java.io.File
import scala.io.Source
import org.eso.ias.prototype.input.java.IASTypes
import org.eso.ias.plugin.AlarmSample

/** 
 *  Test the writing of the output of the DASU
 *  in a JSON file. 
 */
class JsonPublisherTest extends FlatSpec {
/** The logger */
  private val logger = IASLogger.getLogger(this.getClass);
  
  // Build the CDB reader
  val cdbParentPath =  FileSystems.getDefault().getPath(".");
  val cdbFiles = new CdbJsonFiles(cdbParentPath)
  val cdbReader: CdbReader = new JsonReader(cdbFiles)
  
  val dasuId = "DasuWithOneASCE"
  
  val outputFile = new File("./JsonPublisherTest.json")
  val writer = new FileWriter(outputFile)
  val outputPublisher = new JsonWriterPublisher(writer)
  
  val inputsProvider = new TestInputSubscriber()
  
  // The DASU
  val dasu = new Dasu(dasuId,outputPublisher,inputsProvider,cdbReader)
  
  // The identifer of the monitor system that produces the temperature in input to teh DASU
  val monSysId = new Identifier("MonitoredSystemID",IdentifierType.MONITORED_SOFTWARE_SYSTEM)
  // The identifier of the plugin
  val pluginId = new Identifier("PluginID",IdentifierType.PLUGIN,monSysId)
  // The identifier of the converter
  val converterId = new Identifier("ConverterID",IdentifierType.CONVERTER,pluginId)
  // The ID of the monitor point in unput (it matched the ID in theJSON file)
  val inputID = new Identifier("Temperature", IdentifierType.IASIO,converterId)
  
  def buildValue(d: Double): IASValue[_] = {
    new IasDouble(
        d,
        System.currentTimeMillis(),
        OperationalMode.OPERATIONAL,
        inputID.id,
        inputID.fullRunningID)
  }
  
  behavior of "The DASU"
  
  it must "produce the output when a new set inputs is notified" in {
    // Start the getting of events in the DASU
    dasu.start()
    val inputs: Set[IASValue[_]] = Set(buildValue(0))
    // Sumbit the inputs
    inputsProvider.sendInputs(inputs)
    
    // Read the produced JSON file
    assert(outputFile.exists())
    assert(outputFile.canRead())
    val source = Source.fromFile(outputFile)
    val strBuilder = new StringBuilder()
    source.getLines().foreach(line => strBuilder.append(line))
    source.close()
    val strReadFromFile =  strBuilder.toString().trim()
    val jsonSerializer = new IasValueJsonSerializer()
    val iasValue = jsonSerializer.valueOf(strReadFromFile)
    assert(iasValue.id=="ThresholdAlarm")
    assert(iasValue.valueType==IASTypes.ALARM)
    assert(iasValue.value==AlarmSample.CLEARED)
    outputFile.deleteOnExit()
  }  
}