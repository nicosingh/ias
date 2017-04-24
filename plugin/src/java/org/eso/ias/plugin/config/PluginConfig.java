package org.eso.ias.plugin.config;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * The java pojo with the plugin configuration.
 * <P>
 * This object is used by jakson2 parser to read the JSON file.
 * 
 * @author acaproni
 *
 */
public class PluginConfig {
	
	/**
	 * The ID of the plugin
	 */
	private String id;
	
	/**
	 * The name of the server to send monitor point values
	 * and alarms to
	 */
	private String sinkServer;
	
	/**
	 * The port of the server to send monitor point values
	 * and alarms to
	 */
	private int sinkPort;
	
	/**
	 * The values red from the monitored system
	 */
	private Value[] values;

	/**
	 * @return the id
	 */
	public String getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 */
	public void setId(String id) {
		this.id = id;
	}

	/**
	 * @return the sinkServer
	 */
	public String getSinkServer() {
		return sinkServer;
	}

	/**
	 * @param sinkServer the sinkServer to set
	 */
	public void setSinkServer(String sinkServer) {
		this.sinkServer = sinkServer;
	}

	/**
	 * @return the sinkPort
	 */
	public int getSinkPort() {
		return sinkPort;
	}

	/**
	 * @param sinkPort the sinkPort to set
	 */
	public void setSinkPort(int sinkPort) {
		this.sinkPort = sinkPort;
	}

	/**
	 * @return the values
	 */
	public Value[] getValues() {
		return values;
	}

	/**
	 * @param values the values to set
	 */
	public void setValues(Value[] values) {
		this.values = values;
	}
	
	/**
	 * @return The values as a {@link Collection}
	 */
	public Collection<Value> getValuesAsCollection() {
		Collection<Value> ret = new HashSet<>();
		for (Value v: values) {
			ret.add(v);
		}
		return ret;
	}
	
	/**
	 * @return A map of values whose key is
	 *         the ID of the value
	 */
	public Map<String,Value> getMapOfValues() {
		Map<String,Value> ret = new HashMap<>();
		for (Value v: values) {
			ret.put(v.getId(),v);
		}
		return ret;
	}
	
	/**
	 * 
	 * @param id The non empty identifier of the value to get
	 * @return The value with a give id that is
	 *         empty if the array does not contain
	 *         a value with the passed identifier
	 */
	public Optional<Value> getValue(String id) {
		if (id==null || id.isEmpty()) {
			throw new IllegalArgumentException("Invalid null or empty identifier");
		}
		return Optional.ofNullable(getMapOfValues().get(id));
	}
	
	/**
	 * Check the correctness of the values contained in this objects:
	 * <UL>
	 * 	<LI>Non empty ID
	 * 	<LI>Non empty sink server name
	 * 	<LI>Valid port
	 * 	<LI>Non empty list of values
	 *  <LI>No duplicated ID between the values
	 *  <LI>Each value is valid
	 * </ul>
	 * 
	 * @return <code>true</code> if the data contained in this object 
	 * 			are correct
	 */
	public boolean isValid() {
		if (id==null || id.isEmpty()) {
			return false;
		}
		if (sinkServer==null || sinkServer.isEmpty()) {
			return false;
		}
		if (sinkPort<=0) {
			return false;
		}
		// There must be at least one value!
		if (values==null || values.length<=0) {
			return false;
		}
		// Ensure that all the IDs of the values differ
		if (getMapOfValues().keySet().size()!=values.length) {
			return false;
		}
		// Finally, check the validity of all the values
		long invalidValues=getValuesAsCollection().stream().filter(v -> !v.isValid()).count();
		if (invalidValues!=0) {
			return false;
		}
		// Everything ok
		return true;
		
	}
}