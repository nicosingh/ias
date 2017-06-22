package org.eso.ias.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eso.ias.plugin.filter.Filter;
import org.eso.ias.plugin.filter.FilterException;
import org.eso.ias.plugin.filter.FilteredValue;
import org.eso.ias.plugin.filter.NoneFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A <code>MonitoredValue</code> is a monitor point value or alarm red from the 
 * controlled system and sent to the IAS core after applying the filter.
 * <P>
 * The history of samples needed to apply the filter is part
 * of the filter itself because its management depends on the filter.
 * <BR>For example a filter that returns only the last received value needs to save 
 * a history with only that sample, but a filter that averages the values acquired 
 * in the last minute needs a longer history even if its refresh rate is
 * much shorter then the averaging period.   
 * <P>
 * The <code>MonitoredValue</code> main tasks are:
 * <UL>
 * 	<LI>receive the values (samples) of the monitor point generated by the monitored system and
 *      apply the filter to generate a new value to send to the IAS (({@link #submitSample(Sample)})
 * 	<LI>if the value has not been sent to the IAS when the refresh time interval elapses,
 *      send the value again to the core
 * </UL>
 * <P>The <code>MonitoredValue</code> sends the value to the IAS core (i.e. to the {@link ChangeValueListener}) :
 * <UL>
 * 	<LI>immediately if the generated value changed
 * 	<LI>after the time interval elapses by a timer task.
 * </UL>
 * A timer task implemented by the {@link #run()} resend to the core the last value sent. 
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
	public final static long minAllowedRefreshRate=50;
	
	/**
	 * The {@link #refreshRate} of the monitored can be dynamically changed
	 * but can never be greater then the allowed maximum 
	 * to avoid the risk that the value is never sent to the core 
	 */
	public final static long maxAllowedRefreshRate=60000;
	
	/**
	 * The actual refresh rate (msec) of this monitored value:
	 * the value must be sent to the IAS core on change or before the
	 * refresh rate expires
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
	 * The last value sent to the IAS core 
	 * (i.e. to the {@link #listener}).
	 */
	private Optional<FilteredValue> lastSentValue = Optional.empty();
	
	/**
	 * The point in time when the last value has been
	 * sent to the IAS core.
	 */
	private long lastSentTimestamp;
	
	/**
	 * The future instantiated by the timer task
	 */
	private ScheduledFuture<?> future=null;
	
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
	 * Build a {@link MonitoredValue} with the passed filter
	 * @param id The identifier of the value
	 * @param refreshRate The refresh time interval in msec
	 * @param filter The filter to apply to the samples
	 * @param executorSvc The executor to schedule the thread
	 * @param listener The listener of updates
	 */
	public MonitoredValue(
			String id, 
			long refreshRate, 
			Filter filter, 
			ScheduledExecutorService executorSvc,
			ChangeValueListener listener) {
		Objects.requireNonNull(id,"The ID can't be null");
		if (id.trim().isEmpty()) {
			throw new IllegalArgumentException("Invalid empty monitored value ID string");
		}
		Objects.requireNonNull(filter,"The filter can't be null");
		Objects.requireNonNull(executorSvc,"The executor service can't be null");
		Objects.requireNonNull(listener,"The listener can't be null");
		this.id=id.trim();
		this.refreshRate=refreshRate;
		this.filter = filter;
		this.schedExecutorSvc=executorSvc;
		this.listener=listener;
		future = schedExecutorSvc.scheduleAtFixedRate(this, refreshRate, refreshRate, TimeUnit.MILLISECONDS);
		logger.debug("Monitor point {} created with a refresh rate of {}ms",this.id,this.refreshRate);
	}

	/**
	 * Build a {@link MonitoredValue} with the default filter, {@link NoneFilter}
	 * 
	 * @param id The identifier of the value
	 * @param refreshRate The refresh time interval
	 * @param executorSvc The executor to schedule the thread
	 */
	public MonitoredValue(
			String id, 
			long refreshRate, 
			ScheduledExecutorService executorSvc,
			ChangeValueListener listener) {
		this(id,refreshRate, new NoneFilter(id),executorSvc,listener);
	}
	
	/**
	 * Get and return the value to send i.e. the value
	 * returned applying the {@link #filter} to the #history of samples.
	 * 
	 * @return The value to send
	 */
	public Optional<FilteredValue> getValueTosend() {
		return filter.apply();
	}
	
	/**
	 * Adds a new sample to this monitor point.
	 * 
	 * @param s The not-null sample to add to the monitored value
	 * @throws FilterException If the submitted sample caused an exception in the filter
	 */
	public void submitSample(Sample s) throws FilterException {
		if(future.getDelay(TimeUnit.MILLISECONDS)<=minAllowedRefreshRate) {
			rescheduleTimer();
		}
		notifyListener(filter.newSample(s));
	}
	
	/**
	 * Send the value to the listener that in turn will forward it to the IAS core.
	 * 
	 * @param value The not <code>null</code> value to send to the IAS
	 */
	private void notifyListener(Optional<FilteredValue> value) {
		assert(value!=null);
		try {
			listener.monitoredValueUpdated(value);
			lastSentTimestamp=System.currentTimeMillis();
			lastSentValue=value;
		} catch (Exception e) {
			// In case of error sending the value, we log the exception
			// but do nothing else as we want to be ready to try to send it again
			// later
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
		future.cancel(false);
		if (isPeriodicNotificationEnabled.get()) {
			future = schedExecutorSvc.scheduleAtFixedRate(this, refreshRate, refreshRate, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * The timer task scheduled when the refresh time interval elapses.
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		notifyListener(lastSentValue);
	}
	
	/**
	 * Set the new refresh rate for the monitored value with the given identifier.
	 * <P>
	 * If the periodic send is disabled, this method sets the new time interval 
	 * but does not reactivate the periodic send that must be done by 
	 * {@link #enablePeriodicNotification(boolean)}.
	 * 
	 * @param newRefreshRate The new refresh rate (msecs), must be greater then {@link #minAllowedRefreshRate}
	 *                       and less then {@link #maxAllowedRefreshRate}
	 * @return The new refresh rate (msec)
	 * 
	 */
	public synchronized long setRefreshRate(long newRefreshRate) {
		if (newRefreshRate<minAllowedRefreshRate) {
			logger.warn("The requested refresh rate {} for {} was too low: {} will be set instead",newRefreshRate,id,minAllowedRefreshRate);
			newRefreshRate=minAllowedRefreshRate;
			
		}
		if (newRefreshRate>maxAllowedRefreshRate) {
			logger.warn("The requested refresh rate {} for {} was too high: {} will be set instead",newRefreshRate,id,maxAllowedRefreshRate);
			newRefreshRate=maxAllowedRefreshRate;
		}
		refreshRate=newRefreshRate;
		rescheduleTimer();
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
	
	
}
