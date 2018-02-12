package org.eso.ias.basictypes.test

import org.scalatest.FlatSpec
import org.eso.ias.types.Identifier
import org.eso.ias.types.IdentifierType

class TestIdentifier extends FlatSpec {
  behavior of "A Identifier"
  
  it must "forbid to declare IDs with null or empty strings" in {
    assertThrows[IllegalArgumentException] {
      val id1: Identifier = new Identifier(null,null,None)
    }
    assertThrows[IllegalArgumentException] {
      val id2: Identifier = new Identifier("",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    }
  }
  
  it must "forbid to declare IDs containing the separator char '"+Identifier.separator+"'" in {
    val wrongID = "Prefix"+Identifier.separator+"-suffix"
    assertThrows[IllegalArgumentException] {
      val id1: Identifier = new Identifier(wrongID,IdentifierType.ASCE,None)
    }
  }
  
  it must "forbid to instantiate a ID with a parent of the wrong type" in {
    val msId=new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    
    val supervId = new Identifier("SupervId",IdentifierType.SUPERVISOR,None)
    val dasuId = new Identifier("dasuId",IdentifierType.DASU,supervId)
    
    val plId=new Identifier("pluginId",IdentifierType.PLUGIN,Option(msId))
    val convId=new Identifier("converterId",IdentifierType.CONVERTER,Option(plId))
    val ioId=new Identifier("iasioId",IdentifierType.IASIO,Option(convId))
    
    assertThrows[IllegalArgumentException] {
      val ioId2=new Identifier("iasioId",IdentifierType.IASIO,Option(plId))
    }
    
  }
  
  /**
   * Check the construction of the runningID.
   */
  it must "provide a non-empty runningID string" in {
    val id1: Identifier = new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    assert(!id1.runningID.isEmpty())
    val id2: Identifier = new Identifier("pluginId",IdentifierType.PLUGIN,Option(id1))
    assert(!id2.runningID.isEmpty())
    val id3: Identifier = new Identifier("converterId",IdentifierType.CONVERTER,Option(id2))
    assert(!id3.runningID.isEmpty())
    
    assert(id3.runningID.contains(id3.id))
    assert(id3.runningID.contains(id2.id))
    assert(id3.runningID.contains(id1.id))
  }
  
  /**
   * Check the construction of the fullRunningID.
   */
  it must "provide a non-empty fullRunningID string" in {
    val id1: Identifier = new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    assert(!id1.fullRunningID.isEmpty())
    val id2: Identifier = new Identifier("pluginId",IdentifierType.PLUGIN,Option(id1))
    assert(!id2.fullRunningID.isEmpty())
    val id3: Identifier = new Identifier("converterId",IdentifierType.CONVERTER,Option(id2))
    assert(!id3.fullRunningID.isEmpty())
    
    assert(id3.fullRunningID.contains(id3.id))
    assert(id3.fullRunningID.contains(id2.id))
    assert(id3.fullRunningID.contains(id1.id))
  }
  
  /**
   * Check the construction of the runningID.
   */
  it must "must properly order the runnigID" in {
    val id1: Identifier = new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    val id2: Identifier = new Identifier("pluginId",IdentifierType.PLUGIN,Option(id1))
    val id3: Identifier = new Identifier("converterId",IdentifierType.CONVERTER,Option(id2))
    val id4: Identifier = new Identifier("iasioId",IdentifierType.IASIO,Option(id3))
    
    assert(!id4.runningID.isEmpty())
    assert(id4.runningID.endsWith(id4.id))
    assert(id4.runningID.startsWith(id1.id))
    
  }
  
  /**
   * Check the getIdOfType that return id id of the identifier or
   * one of its parent of the given, if any
   */
  it must "Return the id by the passed type" in {
    val monSysId: Identifier = new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    val pluginId: Identifier = new Identifier("pluginId",IdentifierType.PLUGIN,Option(monSysId))
    val convId: Identifier = new Identifier("converterId",IdentifierType.CONVERTER,Option(pluginId))
    val iasioId: Identifier = new Identifier("iasioId",IdentifierType.IASIO,Option(convId))
    
    val i1 = iasioId.getIdOfType(IdentifierType.IASIO)
    assert (i1.isDefined)
    assert(i1.get=="iasioId")
    
    assert(iasioId.getIdOfType(IdentifierType.SUPERVISOR).isEmpty)
    
    val i2 = iasioId.getIdOfType(IdentifierType.PLUGIN)
    assert(i2.isDefined)
    assert(i2.get=="pluginId")
    
    val i3 = monSysId.getIdOfType(IdentifierType.MONITORED_SOFTWARE_SYSTEM)
    assert(i3.isDefined)
    assert(i3.get=="monSysyId")
    
    assert(monSysId.getIdOfType(IdentifierType.ASCE).isEmpty)
    
  }
  
  
  behavior of "The object factory (apply)"
  
  /**
   * Check the factory method with a list of tuples (IDs,types)
   */
  it must "allow to build a chain Identifiers" in {
    val id1: Identifier = new Identifier("monSysyId",IdentifierType.MONITORED_SOFTWARE_SYSTEM,None)
    val id2: Identifier = new Identifier("pluginId",IdentifierType.PLUGIN,Option(id1))
    val id3: Identifier = new Identifier("converterId",IdentifierType.CONVERTER,Option(id2))
    val id4: Identifier = new Identifier("iasioId",IdentifierType.IASIO,Option(id3))
    
    val fullRunId = id4.fullRunningID
    
    val theIdent = Identifier(fullRunId)
    assert(fullRunId==theIdent.fullRunningID)
    assert(fullRunId==Identifier.unapply(theIdent))
  }
}