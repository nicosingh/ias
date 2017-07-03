package org.eso.ias.prototype.input.java;

import org.eso.ias.plugin.OperationalMode;

public class IasBool extends IASValue<Boolean> {
	
	public IasBool(Boolean value,
			long tStamp,
			OperationalMode mode,
			String id,
			String runningId) {
		super(value,tStamp,mode,id,runningId,IASTypes.BOOLEAN);
	}
	
	/**
	 * Build a new IasAlarm with the passed value
	 * 
	 * @param newValue The value to set in the new IASValue
	 * @return The new IASValue with the updated value
	 * @see IASValue#updateValue(Object)
	 */
	@Override
	public IasBool updateValue(Boolean newValue) {
		if (newValue==null) {
			throw new NullPointerException("The value can't be null");
		}
		return new IasBool(newValue,System.currentTimeMillis(),mode,id,runningId);
	}
	
	/**
	 * Build a new IasAlarm with the passed mode
	 * 
	 * @param newMode The mode to set in the new IASValue
	 * @return The new IASValue with the updated mode
	 */
	public IasBool updateMode(OperationalMode newMode) {
		if (newMode==null) {
			throw new NullPointerException("The mode can't be null");
		}
		return new IasBool(value,System.currentTimeMillis(),newMode,id,runningId);
	}

}
