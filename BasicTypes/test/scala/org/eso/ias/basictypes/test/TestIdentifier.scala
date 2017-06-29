package org.eso.ias.basictypes.test

import org.scalatest.FlatSpec
import org.eso.ias.prototype.input.Identifier
import org.eso.ias.prototype.input.java.IdentifierType

class TestIdentifier extends FlatSpec {
  behavior of "A Identifier"
  
  it must "forbid to declare IDs with nullor empty strings" in {
    assertThrows[NullPointerException] {
      val id1: Identifier = new Identifier(null,null,None)
    }
    assertThrows[IllegalArgumentException] {
      val id2: Identifier = new Identifier(Some[String](""),Some(IdentifierType.MONITORED_SOFTWARE_SYSTEM),None)
    }
  }
  
  it must "forbid to declare IDs containing the separator char '"+Identifier.separator+"'" in {
    val wrongID = "Prefix"+Identifier.separator+"-suffix"
    assertThrows[IllegalArgumentException] {
      val id1: Identifier = new Identifier(Some[String](wrongID),Some(IdentifierType.ASCE),None)
    }
  }
  
  /**
   * Check the construction of the runningID i.e. that the Identifier
   * return a non-empty string but does not check the format
   * as it can change in different versions of the software.
   */
  it must "provide a non-empty runningID string" in {
    val id1: Identifier = new Identifier(Some[String]("P1"),Some(IdentifierType.ASCE),None)
    assert(!id1.runningID.isEmpty())
    val id2: Identifier = new Identifier(Some[String]("P2"),Some(IdentifierType.DASU),Option[Identifier](id1))
    assert(!id2.runningID.isEmpty())
    val id3: Identifier = new Identifier(Some[String]("ID"),Some(IdentifierType.PLUGIN),Option[Identifier](id2))
    assert(!id3.runningID.isEmpty())
  }
}