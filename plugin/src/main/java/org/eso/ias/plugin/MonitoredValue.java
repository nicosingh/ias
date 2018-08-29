package org.eso.ias.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eso.ias.plugin.filter.Filter;
import org.eso.ias.plugin.filter.Filter.EnrichedSample;
import org.eso.ias.plugin.filter.FilterException;
import org.eso.ias.plugin.filter.FilteredValue;
import org.eso.ias.plugin.filter.NoneFilter;
import org.eso.ias.types.IasValidity;
import org.eso.ias.types.OperationalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MonitoredValue is a monitor point value or alarm read from the 
 * controlled system.
 * <P>
 * The history of samples needed to apply the filter is part
 * of the filter itself because its management depends on the filter.
 * <BR>For example a filter that returns only the last received value needs to save 
 * a history with only that sample, but a filter that averages the values acquired 
 * in the last minute needs a longer history even if its refresh rate is
 * much shorter then the averaging period.   
 * <P>
 * The MonitoredValue main tasks are:
 * <UL>
 * 	<LI>receive the values (samples, in (({@link #submitSample(Sample)})) of the monitor point 
 *      generated by the monitored control system and
 *      apply the filter to generate the value, a {@link ValueToSend}, to send to the IAS 
 * 	<LI>if the value has not been sent to the IAS when the refresh time interval elapses,
 *      send the value again to the core
 * </UL>
 * <P>The <code>MonitoredValue</code> sends the value to the {@link #listener}, that 
 * will send it to the core of the IAS:
 * <UL>
 * 	<LI>immediately if the generated value changed
 * 	<LI>periodically by a timer task.
 * </UL>
 * A timer task implemented by the {@link #run()} resend to the core the last value sent. 
 * <BR>Note that he periodic sending time interval is a global parameter
 * of the plugin (of the IAS, actually) and must not be confused with the
 * {@link #refreshRate} of the monitor point that is the time interval 
 * that the monitor point is refreshed by the device.
 * The validity depends on the time when the value has been produced by the remote
 * system against the refresh rate and the actual time: the IAS periodic sending
 * of the value does not play a role in the assessment of the validity. 
 * 
 * @author acaproni
 *
 */
public class MonitoredValue implements Runnable {
	
	/**
	 * The logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(MonitoredValue.class);
	
	/**
	 * The ID of the monitored value
	 */
	public final String id;
	
	/**
	 * The {@link #refreshRate} of the monitored can be dynamically changed
	 * but can never be less then the allowed minimum. 
	 */
	public final static long minAllowedSendRate=50;
	
	/**
	 * The name of the property to set the delta time error
	 */
	public final static String validityDeltaPropName = "org.eso.ias.plugin.validity.delta";
	
	/**
	 * The delta time error in msec, to take into account chacking 
	 * if a value is valid.
	 * 
	 * @see #calcValidity()
	 */
	public static final long validityDelta = Long.getLong(validityDeltaPropName, 500);
	
	/**
	 * The actual refresh rate (msec) of this monitored value:
	 * the value depends on the time when the device provides a new value
	 * ansd is used to evaluate the validity
	 */
	public long refreshRate;
	
	/**
	 * The filter to apply to the acquired samples 
	 * before sending the value to the IAS core 
	 */
	private final Filter filter;
	
	/**
	 * The scheduled executor service.
	 * It is needed to get a signal when the refresh rate elapses.
	 */
	private final ScheduledExecutorService schedExecutorSvc;
	
	/**
	 * The listener of updates of the value of this monitored point
	 */
	private final ChangeValueListener listener;
	
	/**
	 * The last value produced by applying the filters
	 * to the samples.
	 * <P>
	 * The FilteredValue will be converted in a ValueToSend before being
	 * sent to the BSDB to add, as a minimum, the validity
	 * at the time of sending.
	 * <P>
	 * Can be <code>null</code> when no value has been set yet.
	 */
	private AtomicReference<FilteredValue> lastProducedValue = new AtomicReference<>(null);
	
	/**
	 * The point in time when the last value has been
	 * sent to the IAS core.
	 */
	private long lastSentTimestamp=Long.MIN_VALUE;
	
	/**
	 * The point in time when the last value has been
	 * submitted
	 */
	private long lastSubmittedTimestamp=Long.MIN_VALUE;
	
	/**
	 * The future instantiated by the timer task
	 */
	private ScheduledFuture<?> future=null;
	
	/**
	 * The periodic time interval to automatically send the
	 * last computed value to the BSDB
	 */
	public final int iasPeriodicSendingTime;
	
	/**
	 * If <code>true</code> the <code>MonitoredValue</code> autonomously resends
	 * the last value to the core of the IAS when the refresh rate elapses.
	 * <P>
	 * It is nabled by default, and can be disabled if the generator of samples
	 * prefers to handle the periodic notification by itself by repeatedly submitting a 
	 * new sample (or the same semple).
	 */
	private final AtomicBoolean isPeriodicNotificationEnabled = new AtomicBoolean(true);
	
	/**
	 * Signal the the value has been started
	 */
	private final AtomicBoolean isStarted= new AtomicBoolean(false);
	
	/**
	 * Signal the the value has been closed
	 */
	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	
	/**
	 * The operational mode of this monitored value.
	 * <P>
	 * Ths mode is not sent to the core of the IAS if
	 * a plugin has a plugin operational mode set.
	 * 
	 * @see Plugin#setPluginOperationalMode(OperationalMode)
	 * @see Plugin#unsetPluginOperationalMode()
	 */
	private OperationalMode operationalMode = OperationalMode.UNKNOWN;
	
	/**
	 * The validity of this monitored value
	 */
	private IasValidity iasValidity = IasValidity.UNRELIABLE;
	
	/**
	 * Build a {@link MonitoredValue} with the passed filter
	 * @param id The identifier of the value
	 * @param refreshRate The refresh time interval  of the device (in msec)
	 * @param filter The filter to apply to the samples
	 * @param executorSvc The executor to schedule the thread
	 * @param listener The listener of updates
	 * @param iasPeriodicSendingTime The time interval (secs) 
	 *           to periodically send the last computed value to the BSDB
	 */
	public MonitoredValue(
			String id, 
			long refreshRate, 
			Filter filter, 
			ScheduledExecutorService executorSvc,
			ChangeValueListener listener,
			int iasPeriodicSendingTime) {
		Objects.requireNonNull(id,"The ID can't be null");
		if (id.trim().isEmpty()) {
			throw new IllegalArgumentException("Invalid empty monitored value ID string");
		}
		Objects.requireNonNull(filter,"The filter can't be null");
		Objects.requireNonNull(executorSvc,"The executor service can't be null");
		Objects.requireNonNull(listener,"The listener can't be null");
		if (iasPeriodicSendingTime<=0) {
			throw new IllegalArgumentException("Invalid periodic time interval "+iasPeriodicSendingTime);
		}
		this.id=id.trim();
		this.refreshRate=refreshRate;
		this.filter = filter;
		this.schedExecutorSvc=executorSvc;
		this.listener=listener;
		this.iasPeriodicSendingTime=iasPeriodicSendingTime;
	}

	/**
	 * Build a {@link MonitoredValue} with the default filter, {@link NoneFilter}
	 * 
	 * @param id The identifier of the value
	 * @param refreshRate The refresh time interval
	 * @param executorSvc The executor to schedule the thread
	 * @param listener The listener
	 * @param iasPeriodicSendingTime The time interval (secs) 
	 *           to periodically send the last computed value to the BSDB
	 */
	public MonitoredValue(
			String id, 
			long refreshRate, 
			ScheduledExecutorService executorSvc,
			ChangeValueListener listener,
			int iasPeriodicSendingTime) {
		this(id,refreshRate, new NoneFilter(null),executorSvc,listener,iasPeriodicSendingTime);
	}
	
	/**
	 * Get and return the value to send i.e. the value
	 * generated applying the {@link #filter} to the #history of samples.
	 * 
	 * @return The value to send
	 */
	public Optional<FilteredValue> getValueTosend() {
		return filter.apply();
	}
	
	/**
	 * Calculate the validity of the value to send to the BSDB.
	 * <P>
	 * The heuristic is very simple as the validity at this stage 
	 * is only based on timing.
	 * A newly produced value is always valid but a value that is sent
	 * after being produced can be valid or invalid depending on how
	 * long after production it is efefctively sent.
	 *  
	 * @return the validity
	 */
	private IasValidity calcValidity(FilteredValue fValue) {
		Objects.requireNonNull(fValue,"Cannot evaluate the validity of a null value");
		long now = System.currentTimeMillis();
		long productionTime = fValue.producedTimestamp;
		if (now-productionTime<=refreshRate+validityDelta) return IasValidity.RELIABLE;
		else return IasValidity.UNRELIABLE;
	}
	
	/**
	 * Adds a new sample to this monitor point.
	 * 
	 * @param s The not-null sample to add to the monitored value
	 * @throws FilterException If the submitted sample caused an exception in the filter
	 */
	public void submitSample(Sample s) throws FilterException {
		Objects.requireNonNull(s);
		if (isStarted.get() && !isClosed.get()) {
			lastSubmittedTimestamp=System.currentTimeMillis();
			
			// Check if the new sample arrived before the refreshRate elapsed.
			//
			// The refresh rate depends on the rate at which the monitored software system
			// or a device produces a new value.
			//
			// A newly received sample is by definition always valid but it can be
			// useful to record if it arrived too late because there can be a problem 
			// in the device that is too slow responding or in the algorithm 
			// that read the value from the monitored system/device and notify the plugin
			boolean generatedInTime = System.currentTimeMillis()-lastSubmittedTimestamp<=refreshRate+validityDelta;
			
			EnrichedSample validatedSample = new EnrichedSample(s, generatedInTime);
			
			filter.newSample(validatedSample).ifPresent(filteredValue -> {
				FilteredValue oldfilteredValue = lastProducedValue.getAndSet(filteredValue);
				if (oldfilteredValue==null || !filteredValue.value.equals(oldfilteredValue.value)) {
					// The value changed so a immediate sending is triggered
					if(future.getDelay(TimeUnit.MILLISECONDS)<=minAllowedSendRate) {
						rescheduleTimer();
					}
					notifyListener();
				}
			});
		}
	}
	
	/**
	 * Send the last produced value to the listener
	 * that in turn will forward it to the BSDB.
	 */
	private void notifyListener() {
		FilteredValue filteredValueToSend = lastProducedValue.get();
		IasValidity actualValidity = calcValidity(filteredValueToSend);
		ValueToSend valueToSend = new ValueToSend(
				id, 
				filteredValueToSend, 
				operationalMode,
				actualValidity);
		try {
			listener.monitoredValueUpdated(valueToSend);
			lastSentTimestamp=System.currentTimeMillis();
		} catch (Exception e) {
			// In case of error sending the value, we log the exception
			// but do nothing else because we want to be ready to send it again later
			logger.error("Error notifying the listener of the {} monitor point change", id, e);
		}
	}
	
	/**
	 * Reschedule the timer task after sending a value to the listener.
	 * <P>
	 * The timer must be scheduled to send the value to the listener 
	 * at the latest when the refresh rate elapse. 
	 */
	private synchronized void rescheduleTimer() {
		if (isClosed.get()) {
			return;
		}
		assert(isStarted.get());
		future.cancel(false);
		if (isPeriodicNotificationEnabled.get()) {
			future = schedExecutorSvc.scheduleAtFixedRate(
					this, 
					iasPeriodicSendingTime, 
					iasPeriodicSendingTime, 
					TimeUnit.SECONDS);
		}
	}

	/**
	 * The timer task scheduled when the refresh time interval elapses.
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (!isClosed.get()) {
			notifyListener();
		}
	}
	
	/**
	 * Set the new refresh rate for the monitored value with the given identifier.
	 * <P>
	 * This method sets the refresh rate at which the monitored value is retrieved from
	 * the remote monitored system and not the time the value is periodically
	 * sent to the BSDB if its value did not change.
	 * <P>
	 * Setting this value affects the evaluation of the validity.
	 * 
	 * @param newRefreshRate The new refresh rate (msecs), must be greater 
	 *                       then {@link #minAllowedSendRate}
	 * @return The new refresh rate (msec)
	 * 
	 */
	public synchronized long setRefreshRate(long newRefreshRate) {
		if (newRefreshRate<minAllowedSendRate) {
			logger.warn("The requested refresh rate {} for {} was too low: {} will be set instead",newRefreshRate,id,minAllowedSendRate);
			newRefreshRate=minAllowedSendRate;
			
		}
		refreshRate=newRefreshRate;
		return newRefreshRate;
	}
	
	/**
	 * Enable or disable the periodic sending of notifications.
	 * 
	 * @param enable if <code>true</code> enables the periodic sending;
	 *                 
	 */
	public void enablePeriodicNotification(boolean enable) {
		isPeriodicNotificationEnabled.set(enable);
		rescheduleTimer();
	}
	
	/**
	 * Set the operational mode of this monitor point value.
	 * <P>
	 * Note that this value is effectively sent to the core of the IAS only if
	 * not overridden by the plugin operational mode 

	 * @param opMode The not <code>null</code> operational mode to set
	 * @return The old operational mode of the monitor point
	 */
	public OperationalMode setOperationalMode(OperationalMode opMode) {
		Objects.requireNonNull(opMode, "Invalid operational mode");
		OperationalMode ret = operationalMode;
		operationalMode= opMode;
		return ret;
	}
	
	/**
	 * Start the MonitorValue basically activating
	 * the timer thread 
	 */
	public synchronized void start() {
		boolean alreadyStarted = isStarted.getAndSet(true);
		assert(!alreadyStarted);
		future = schedExecutorSvc.scheduleAtFixedRate(
				this, 
				iasPeriodicSendingTime, 
				iasPeriodicSendingTime, 
				TimeUnit.SECONDS);
		logger.debug("Monitor point {} initialized with a refresh rate of {} secs",id,iasPeriodicSendingTime);
	}
	
	/**
	 * Stops the periodic thread and release all the resources
	 */
	public synchronized void shutdown() {
		boolean alreadyClosed = isClosed.getAndSet(true);
		if (alreadyClosed) {
			logger.warn("Monitor point {} already closed",id);
		} else {
			if (isStarted.get()) {
				future.cancel(false);
			}
		}
	}
}