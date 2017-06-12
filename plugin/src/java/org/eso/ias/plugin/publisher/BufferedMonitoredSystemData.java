package org.eso.ias.plugin.publisher;

import java.util.Collection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The data structure encapsulating monitor point values
 * and alarm retrieved from a monitored system and to be sent to the IAS core.
 * It is composed of a global data structure (this one) plus a list
 * of monitor points.
 * <P>
 * <code>BufferedMonitoredSystemData</code> is a java POJO that is easily
 * serializable to a string that is what the plugin sends to
 * the IAS core.
 * <P>
 * To improve network performances, whenever possible the plugin 
 * collects and sends the monitor points with one single message 
 * instead of one by one.
 * 
 * @see MonitorPointData
 * @author acaproni
 *
 */
public class BufferedMonitoredSystemData {
	
	/**
	 * The id of the plugin.
	 */
	private String systemID;
	
	/**
	 * ISO-8601 formatted time when the 
	 * data structure has been sent to the IAS core
	 */
	private String publishTime;
	
	/**
	 * The monitor points and alarms collected from the remote
	 * system to be sent to the IAS core;
	 */
	private Collection<MonitorPointData> monitorPoints;
	
	/**
	 * The mapper to convert this pojo in a JSON string and vice-versa
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Empty constructor
	 */
	public BufferedMonitoredSystemData() {}

	/**
	 * @return the systemID
	 */
	public String getSystemID() {
		return systemID;
	}

	/**
	 * @param systemID the systemID to set
	 */
	public void setSystemID(String systemID) {
		this.systemID = systemID;
	}

	/**
	 * @return the publishTime
	 */
	public String getPublishTime() {
		return publishTime;
	}

	/**
	 * @param publishTime the publishTime to set
	 */
	public void setPublishTime(String publishTime) {
		this.publishTime = publishTime;
	}

	/**
	 * @return the monitorPoints
	 */
	public Collection<MonitorPointData> getMonitorPoints() {
		return monitorPoints;
	}

	/**
	 * @param monitorPoints the monitorPoints to set
	 */
	public void setMonitorPoints(Collection<MonitorPointData> monitorPoints) {
		if (this.monitorPoints!=null) {
			this.monitorPoints.clear();
		}
		this.monitorPoints = monitorPoints;
	}
	
	/**
	 * Return a JSON string for this object.
	 * 
	 * @return A Json string representing this object
	 */
	public String toJsonString() throws PublisherException {
		try {
			return MAPPER.writeValueAsString(this);
		} catch (JsonProcessingException jpe) {
			throw new PublisherException("Error creating the JSON string", jpe);
		}
	}
	
	/**
	 * Builds a <code>MonitoredSystemData</code> parsing the passed JSON string
	 * 
	 *  @param jsonStr The JSON string to parse to build the <code>MonitoredSystemData</code>
	 */
	public static BufferedMonitoredSystemData fromJsonString(String jsonStr) throws PublisherException {
		try { 
			return MAPPER.readValue(jsonStr, BufferedMonitoredSystemData.class);
		} catch (Exception e) {
			throw new PublisherException("Error parsing a JSON string",e);
		}
	}

	@Override
	public String toString() {
		StringBuilder ret = new StringBuilder("Monitored System Data packet [ID=");
		ret.append(systemID);
		ret.append(", published at ");
		ret.append(publishTime);
		ret.append(", ");
		ret.append(monitorPoints.size());
		ret.append(" monitor points: ");
		monitorPoints.forEach( mp -> {
			ret.append(' ');
			ret.append(mp.toString());
		});
		ret.append(']');
		return ret.toString();
	}
}
