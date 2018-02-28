package org.eso.ias.types

import java.util.Optional

/**
 * A  <code>InOut</code> holds the value of an input or output 
 * of the IAS.
 * Objects of this type constitutes both the input of ASCEs and the output 
 * they produce. They are supposed to live into a ASCE only: their representation
 * in the BSDB is the IASValue[_].
 * 
 * The type of a InOut can be a double, an integer, an
 * array of integers and many other customized types.
 * 
 * The actual value is an Option because there is no
 * value associated before it comes for example from the HW. 
 * Nevertheless the <code>InOut</code> exists.
 * 
 * If the InOut is the input of a ASCE, it has the validity received from the IASValue
 * in the fromIasValueValidity. Otherwise the validity depends on the validity of the
 * inputs to the ASCE and is stored in fromInputsValidity.
 * At any time, only one Option between fromIasValueValidity and fromInputsValidity
 * must be defined: this invariant, can also used to recognize if a InoOut is an output
 * of the ACSE.
 * @see isOutput()
 * 
 * A IASIO can only be produced by a plugin or by a DASU i.e.
 * only one between pluginProductionTStamp and dasuProductionTStamp
 * can be defined (another invariant)
 * 
 * 
 * <code>InOut</code> is immutable.
 * 
 * @param value: the value of this InOut (can be empty) 
 * @param id: The unique ID of the monitor point
 * @param mode: The operational mode
 * @param fromIasValueValidity: The validity received from the BSDB (i.e. from a IASValue)
 *                           It is None if and only if the value is generated by ASCE
 *                           and in that case fromInputsValidity is defined
 * @param fromInputsValidity the validity inherited by the inputs
 *                           It is defined only for the ouputs of an ASCE
 *                           and in that case  fromIasValueValidity must be None     
 * @param iasType: is the IAS type of this InOut
 * @param pluginProductionTStamp The point in time when the plugin produced this value
 * @param sentToConverterTStamp The point in time when the plugin sent the value to the converter
 * @param receivedFromPluginTStamp The point in time when the converter received the value from the plugin
 * @param convertedProductionTStamp The point in time when the converter generated
 *                                  the value from the data structure rceived by the plugin
 * @param sentToBsdbTStamp The point in time when the value has been sent to the BSDB
 * @param readFromBsdbTStamp The point in time when the value has been read from the BSDB
 * @param dasuProductionTStamp The point in time when the value has been generated by the DASU
 * 
 * @see IASType
 * 
 * @author acaproni
 */
case class InOut[A](
    value: Option[_ >: A],
    id: Identifier,
    mode: OperationalMode,
    fromIasValueValidity: Option[Validity],
    fromInputsValidity: Option[Validity],
    iasType: IASTypes,
    pluginProductionTStamp: Option[Long],
	  sentToConverterTStamp: Option[Long],
	  receivedFromPluginTStamp: Option[Long],
	  convertedProductionTStamp: Option[Long],
	  sentToBsdbTStamp: Option[Long],
	  readFromBsdbTStamp: Option[Long],
	  dasuProductionTStamp: Option[Long]) {
  require(Option[Identifier](id).isDefined,"The identifier must be defined")
  require(Option[IASTypes](iasType).isDefined,"The type must be defined")
  
  // Check that one and only one validity (from inputs or from IASValue)
  // is defined
  require(
      fromIasValueValidity.size+fromInputsValidity.size==1,
      "Inconsistent validity")
      
  // Check that no more then one between the pugin and the DASU production
  // timestamps is defined
  require(
      pluginProductionTStamp.size+dasuProductionTStamp.size<=1,
      "Inconsistent production timestamps")
  
  value.foreach(v => assert(InOut.checkType(v,iasType),"Type mismatch: ["+v+"] is not "+iasType))
  
  override def toString(): String = {
    val ret = new StringBuilder("Monitor point [")
    ret.append(id.toString())
    ret.append("] of type ")
    ret.append(iasType)
    ret.append(", mode=")
    ret.append(mode.toString())
    fromIasValueValidity.foreach(v => {
      ret.append(", from BSDB validity=")
      ret.append(v.toString())
    })
    fromInputsValidity.foreach(v => {
      ret.append(", from inputs validity=")
      ret.append(v.toString())
    })
    ret.append(", ")
    if (value.isEmpty) {
      ret.append("No value")
    } else {
       ret.append("Value: "+value.get.toString())
    }
    ret.toString()
  }
  
  /**
   * Update the mode of the monitor point
   * 
   * @param newMode: The new mode of the monitor point
   */
  def updateMode(newMode: OperationalMode): InOut[A] = {
    this.copy(mode=newMode, dasuProductionTStamp=Some(System.currentTimeMillis()))
  }
  
  /**
   * Update the value of a IASIO
   * 
   * @param newValue: The new value of the IASIO
   * @return A new InOut with updated value
   */
  def updateValue[B >: A](newValue: Some[B]): InOut[A] = {
    assert(InOut.checkType(newValue.get,iasType))
    
    this.copy(value=newValue, dasuProductionTStamp=Some(System.currentTimeMillis()))
  }
  
  /**
   * Update the value and validity of the monitor point.
   * 
   * Which validity to updated between fromIasValueValidity and fromInputsValidity
   * depends if the InOut is an input or a output of a ASCE.
   * 
   * @param newValue: The new value of the monitor point
   * @param newValidity the new validity (either fromIasValueValidity or fromInputsValidity)
   * @return A new InOut with updated value and validity
   */
  def updateValueValidity[B >: A](newValue: Some[B], newValidity: Some[Validity]): InOut[A] = {
    assert(InOut.checkType(newValue.get,iasType))
    if (isOutput()) {
      this.copy(value=newValue,fromInputsValidity=newValidity, dasuProductionTStamp=Some(System.currentTimeMillis()))
    } else {
      this.copy(value=newValue,fromIasValueValidity=newValidity, dasuProductionTStamp=Some(System.currentTimeMillis()))
    }
  }
  
  /**
   * Update the validity received from a IASValue
   */
  def updateFromIasValueValidity(validity: Validity):InOut[A] = {
    val validityOpt = Option(validity)
    require(validityOpt.isDefined)
    assert(!isOutput() && fromIasValueValidity.isDefined, "Cannot update the IASValue validity of an output")
    this.copy(fromIasValueValidity=validityOpt, dasuProductionTStamp=Some(System.currentTimeMillis()))
  }
  
  /**
   * Update the validity Inherited from the inputs
   */
  def updateFromIinputsValidity(validity: Validity):InOut[A] = {
    val validityOpt = Option(validity)
    require(validityOpt.isDefined)
    assert(isOutput() && fromInputsValidity.isDefined, "Cannot update the validities of inputs of an input")
    this.copy(fromInputsValidity=validityOpt, dasuProductionTStamp=Some(System.currentTimeMillis()))
  }
  
  /**
   * @return true if this IASIO is the generated by the ASCE,
   *         false otherwise (i.e. the input of the ASCE)
   */
  def isOutput() = fromIasValueValidity.isEmpty
  
  /**
   * The validity that comes either from the IASValue (input of a ASCE)
   * or inherited from the inputs (output of the ASCE)
   * 
   * The validity returned by this method does not take into account 
   * the actual time against the timestamps of the IASIO
   * 
   * @return the validity 
   */
  def getValidity: Validity = {
    assert(
      fromIasValueValidity.isDefined && fromInputsValidity.isEmpty ||
      fromIasValueValidity.isEmpty && fromInputsValidity.isDefined,
      "Inconsistent validity")
      
      fromIasValueValidity.getOrElse(fromInputsValidity.get)
  }
  
  /**
   * Update the value of this IASIO with the IASValue.
   * 
   * The validity received from the IASValus will be stored in fromIasValueValidity
   * if this INOut is an input of a ASCE or in fromInputsValidity if it the the output 
   * of a ASCE.
   */
  def update(iasValue: IASValue[_]): InOut[_] = {
    require(Option(iasValue).isDefined,"Cannot update from a undefined IASValue")
    require(Option(iasValue.value).isDefined,"Cannot update when the IASValue has no value")
    assert(iasValue.id==this.id.id,"Identifier mismatch: received "+iasValue.id+", expected "+this.id.id)
    assert(iasValue.valueType==this.iasType)
    assert(InOut.checkType(iasValue.value,iasType))
    val validity = Some(Validity(iasValue.iasValidity))
    
    new InOut(
        Some(iasValue.value),
        Identifier(iasValue.fullRunningId),
        iasValue.mode,
        if (isOutput()) None else validity,
        if (isOutput()) validity else None,
        iasValue.valueType,
        if (iasValue.pluginProductionTStamp.isPresent()) Some(iasValue.pluginProductionTStamp.get()) else None,
	      if (iasValue.sentToConverterTStamp.isPresent()) Some(iasValue.sentToConverterTStamp.get()) else None,
	      if (iasValue.receivedFromPluginTStamp.isPresent()) Some(iasValue.receivedFromPluginTStamp.get()) else None,
	      if (iasValue.convertedProductionTStamp.isPresent()) Some(iasValue.convertedProductionTStamp.get()) else None,
	      if (iasValue.sentToBsdbTStamp.isPresent()) Some(iasValue.sentToBsdbTStamp.get()) else None,
	      if (iasValue.readFromBsdbTStamp.isPresent()) Some(iasValue.readFromBsdbTStamp.get()) else None,
	      if (iasValue.dasuProductionTStamp.isPresent()) Some(iasValue.dasuProductionTStamp.get()) else None)
  }
  
  def updateSentToBsdbTStamp(timestamp: Long) = {
    val newTimestamp = Option(timestamp)
    require(newTimestamp.isDefined)
    
    this.copy(sentToBsdbTStamp=newTimestamp)
  }
  
  /**
   * Build and return the IASValue representation of this IASIO
   * 
   * @return The IASValue representation of this IASIO
   */
  def toIASValue(): IASValue[_] = {
    
    new IASValue(
        value.getOrElse(null),
			  mode,
			  getValidity.iasValidity,
			  id.fullRunningID,
			  iasType,
			  Optional.ofNullable(if (pluginProductionTStamp.isDefined) pluginProductionTStamp.get else null),
  			Optional.ofNullable(if (sentToConverterTStamp.isDefined) sentToConverterTStamp.get else null),
  			Optional.ofNullable(if (receivedFromPluginTStamp.isDefined) receivedFromPluginTStamp.get else null),
  			Optional.ofNullable(if (convertedProductionTStamp.isDefined) convertedProductionTStamp.get else null),
  			Optional.ofNullable(if (sentToBsdbTStamp.isDefined) sentToBsdbTStamp.get else null),
  			Optional.ofNullable(if (readFromBsdbTStamp.isDefined) readFromBsdbTStamp.get else null),
  			Optional.ofNullable(if (dasuProductionTStamp.isDefined) dasuProductionTStamp.get else null))

  }
  
  
}

/** 
 *  InOut companion object
 */
object InOut {
  
  /**
   * Check if the passed value is of the proper type
   * 
   * @param value: The value to check they type against the iasType
   * @param iasType: The IAS type
   */
  def checkType[T](value: T, iasType: IASTypes): Boolean = {
    if (value==None) true
    else iasType match {
      case IASTypes.LONG => value.isInstanceOf[Long]
      case IASTypes.INT => value.isInstanceOf[Int]
      case IASTypes.SHORT => value.isInstanceOf[Short]
      case IASTypes.BYTE => value.isInstanceOf[Byte]
      case IASTypes.DOUBLE => value.isInstanceOf[Double]
      case IASTypes.FLOAT => value.isInstanceOf[Float]
      case IASTypes.BOOLEAN =>value.isInstanceOf[Boolean]
      case IASTypes.CHAR => value.isInstanceOf[Char]
      case IASTypes.STRING => value.isInstanceOf[String]
      case IASTypes.ALARM =>value.isInstanceOf[AlarmSample]
      case _ => false
    }
  }
  
  /**
   * Build a InOut that is an input of a ASCE.
   * This InOut has the validity inherited from the IASValue initially set to INVALID
   * 
   * Such a IASIO is useful when it is expected but has not yet been sent
   * by the BSDB or a ASCE: we know that it exists but we do not know yet its 
   * initial value.
   * 
   * @param id the identifier
   * @param iasType the type of the value of the IASIO
   * @return a InOut initially empty
   */
  def asOutput[T](id: Identifier, iasType: IASTypes): InOut[T] = {
    new InOut[T](
        None,
        id,
        OperationalMode.UNKNOWN,
        None,
        Some(Validity(IasValidity.UNRELIABLE)),
        iasType,
        None,
        None,
        None,
        None,
        None,
        None,
        None)
  }
  
  /**
   * Build a InOut that is the input of a ASCE.
   * This InOut has the validity inherited from the validities of
   * the inputs of the ASCE  initially set to INVALID
   * 
   * Such a IASIO is useful when it is expected but has not yet been sent
   * by the BSDB or a ASCE: we know that it exists but we do not know yet its 
   * initial value.
   * 
   * @param id the identifier
   * @param iasType the type of the value of the IASIO
   * @return a InOut initially empty
   */
  def asInput[T](id: Identifier, iasType: IASTypes): InOut[T] = {
    new InOut[T](
        None,
        id,
        OperationalMode.UNKNOWN,
        Some(Validity(IasValidity.UNRELIABLE)),
        None,
        iasType,
        None,
        None,
        None,
        None,
        None,
        None,
        None)
  }
}