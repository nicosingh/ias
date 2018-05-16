package org.eso.ias.types;

/**
 * <code>Alarm</code> represents an alarm generated by a remote monitored system.
 * <P>
 * An alarm can be cleared or in one of the possible 4 priorities.
 * 
 * @author acaproni
 *
 */
public enum Alarm {
	/**
	 *  Critical alarm set
	 */
	SET_CRITICAL(4),
	
	/**
	 *  HIGH alarm set
	 */
	SET_HIGH(3),
	
	/**
	 * Medium alarm set
	 */
	SET_MEDIUM(2),
	
	/**
	 *  Low priority alarm set
	 */
	SET_LOW(1),
	
	/**
	 *  Alarm clear or unset
	 */
	CLEARED(0);
	
	
	/**
	 * The representation of the alarm state as integer
	 * where
	 * <UL>
	 * 	<LI><code>0</code>: means cleared
	 *  <LI><code>1-4</code>: corresponds to an alarm set
	 *      (the greater the number the higher the priority)
	 * </ul>
	 * 
	 * Java enumerated already orders the values and assign an integer but such
	 * value depends on the order the values are written in the java source
	 * and is not explicit: for this reason we explicitly define associate
	 * each alarm state to an integer.
	 */
	private final int asInteger;
	
	/**
	 * Constructor
	 * 
	 * @param intValue The representation of the enumerated as integer
	 */
	private Alarm(int intValue) {
		assert(intValue>=0);
		this.asInteger=intValue;
	}
	
	/**
	 * 
	 * @return <code>true</code> if the alrm is set;
	 *         <code>false</code> otherwise
	 */
	public final boolean isSet() {
		return asInteger>0;
	}
	
	/**
	 * @return The default alarm set
	 */
	public static Alarm getSetDefault() {
		return SET_MEDIUM;
	}
	
	/**
	 * 
	 * @return the alarm cleared
	 */
	public static Alarm cleared() {
		return CLEARED;
	}
	
	/**
	 * 
	 * @param n the integer representation of an alarm
	 * @return the alarm of the given integer representation
	 */
	private Alarm fromInteger(int n) {
		for (Alarm al: Alarm.values()) {
			if (n==al.asInteger) {
				return al;
			}
		}
		throw new IllegalArgumentException("No alarm representation for "+n);
	}
	
	/**
	 * Return an alarm of an increased priority if it exists,
	 * otherwise return the same alarm.
	 * 
	 * Increasing the priority of a {@link #CLEARED} 
	 * alarm is not allowed and the method throws an exception
	 * 
	 * @return the alarm with increased priority
	 */
	public Alarm increasePriority() {
		if (asInteger==0) {
			throw new IllegalStateException("Cannot increase the priority of an alarm that is not set");
		}
		try {
			return fromInteger(asInteger+1);
		} catch (IllegalArgumentException iae) {
			return this;
		}
	}
	
	/**
	 * Return an alarm of a lowered priority if it exists,
	 * otherwise return the same alarm.
	 * 
	 * Lowering the priority of a {@link #CLEARED} 
	 * alarm is not allowed and the method throws an exception
	 * 
	 * @return the alarm with increased priority
	 */
	public Alarm lowerPriority() {
		if (asInteger==0) {
			throw new IllegalStateException("Cannot lower the priority of an alarm that is not set");
		}
		if (asInteger==1) {
			return this;
		} else {
			return fromInteger(asInteger-1);
		}
	}
}