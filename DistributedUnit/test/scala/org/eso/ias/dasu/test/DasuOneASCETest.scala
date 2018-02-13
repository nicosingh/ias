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
import org.eso.ias.types.IasValueJsonSerializer
import org.ias.logging.IASLogger
import org.eso.ias.types.IASValue
import org.eso.ias.types.IasDouble
import org.eso.ias.types.Identifier
import org.eso.ias.types.IdentifierType
import org.eso.ias.types.OperationalMode
import org.eso.ias.types.InOut
import org.eso.ias.types.JavaConverter
import org.eso.ias.dasu.subscriber.InputsListener
import org.eso.ias.dasu.subscriber.InputSubscriber
import scala.util.Success
import scala.util.Try
import scala.collection.mutable.{HashSet => MutableSet}
import org.eso.ias.types.IasValidity._
import org.eso.ias.dasu.DasuImpl
import org.eso.ias.dasu.publisher.DirectInputSubscriber
import org.scalatest.BeforeAndAfter

/**
 * Test the DASU with one ASCE and the MinMaxThreshold TF.
 * 
 * Being a simple case, this test will do some basic tests.
 * 
 * The configurations of DASU, ASCE, TF and IASIOs are all stored 
 * in the CDB folder.
 */
class DasuOneASCETest extends FlatSpec  with BeforeAndAfter {
  
  val f = new DasuOneAsceCommon(1000)
  
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
  
  behavior of "The DASU"
  
  it must "return the correct list of input and ASCE IDs" in {
    assert(f.dasu.get.getInputIds().size==1)
    assert(f.dasu.get.getInputIds().forall(s => s=="Temperature"))
    
    assert(f.dasu.get.getAsceIds().size==1)
    assert(f.dasu.get.getAsceIds().forall(s => s=="ASCE-ID1"))
  }
  
  it must "produce the output when a new set inputs is notified" in {
    // Start the getting of events in the DASU
    val inputs: Set[IASValue[_]] = Set(f.buildValue(0))
    // Sumbit the inputs
    f.inputsProvider.sendInputs(inputs)
  }
  
}
