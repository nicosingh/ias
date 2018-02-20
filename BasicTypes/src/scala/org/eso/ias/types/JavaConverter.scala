
package org.eso.ias.types

import org.eso.ias.types.IASTypes._
import java.util.Optional

/**
 * Helper methods to convert from java to scala and vice-versa.
 * 
 * @author acaproni
 */
object JavaConverter {
  
  /**
   * Convert a scala InOut in a java IASValue.
   * 
   * The validity of the IASValue must be passed as parameter because it
   * depends on factors external to the IASValue, like the value
   * of other IASIOs when generated by a TF
   * 
   * @param hio: the HIO to convert to java IASValue
   * @param validity: the validity of the iASValue
   * @return The java value version of the passed HIO 
   */
  def inOutToIASValue[T](io: InOut[_], validity: Validity): IASValue[_] = {
    require(Option(io).isDefined)
    require(Option(validity).isDefined)
    
    new IASValue(
        io.value.get,
			  io.mode,
			  validity.iasValidity,
			  io.id.fullRunningID,
			  io.iasType,
			  Optional.ofNullable(if (io.pluginProductionTStamp.isDefined) io.pluginProductionTStamp.get else null),
  			Optional.ofNullable(if (io.sentToConverterTStamp.isDefined) io.sentToConverterTStamp.get else null),
  			Optional.ofNullable(if (io.receivedFromPluginTStamp.isDefined) io.receivedFromPluginTStamp.get else null),
  			Optional.ofNullable(if (io.convertedProductionTStamp.isDefined) io.convertedProductionTStamp.get else null),
  			Optional.ofNullable(if (io.sentToBsdbTStamp.isDefined) io.sentToBsdbTStamp.get else null),
  			Optional.ofNullable(if (io.readFromBsdbTStamp.isDefined) io.readFromBsdbTStamp.get else null),
  			Optional.ofNullable(if (io.dasuProductionTStamp.isDefined) io.dasuProductionTStamp.get else null))

  }
  
  /**
   * Update a scala HeteroInOut with the passed java IASValue
   * 
   * @param hio: the HIO to update
   * @param iasValue: the java value to update the passed scala HIO
   * @return The hio updated with the passed java value
   */
  def updateHIOWithIasValue[T](hio: InOut[T], iasValue: IASValue[_]): InOut[T] = {
    assert(Option(hio).isDefined)
    assert(Option(iasValue).isDefined)
    // Some consistency check
    if (hio.iasType!=iasValue.valueType) {
      throw new IllegalStateException("Type mismatch for HIO "+hio.id.runningID+": "+hio.iasType+"!="+iasValue.valueType)
    }
    if (hio.id.id!=iasValue.id) {
      throw new IllegalStateException("ID mismatch for HIO "+hio.id.runningID+": "+hio.id.id+"!="+iasValue.id)
    }
    if (hio.id.fullRunningID!=iasValue.fullRunningId) {
      throw new IllegalStateException("Running ID mismatch for HIO "+hio.id.fullRunningID+": "+hio.id.runningID+"!="+iasValue.fullRunningId)
    }
    // Finally, update the HIO
    val ret = hio.updateMode(iasValue.mode).updateValue(Option(iasValue.value)) 
    if (hio.isOutput()) {
      // Does not updated the inherited validity 
      ret
    } else {
      val inheritVal = Some(Validity(iasValue.iasValidity))
      ret.updatedInheritedValidity(inheritVal)  
    }
    
  }
  
  /**
   * Convert a java Optional to a scala Option
   */
  def toScalaOption[T](jOpt: Optional[T]): Option[T] = if (jOpt.isPresent) Some(jOpt.get()) else None
}