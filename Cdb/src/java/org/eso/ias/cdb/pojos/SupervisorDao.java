package org.eso.ias.cdb.pojos;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * The Supervisor is the container of DASUs
 * needed to run more then one DASU in the same java process.
 * 
 * If we do not want this feature we can get rid of the Supervisor
 * by moving its properties into the DASU pojo.
 *  
 * @author acaproni
 */
@Entity
@Table(name = "SUPERVISOR")
public class SupervisorDao {
	
	@Id
	@Column(name = "supervisor_id")
	private String id;
	
	/**
	 * The host where the Supervisor runs
	 */
	@Basic(optional=false)
	private String hostName;
	
	/**
	 * The log level
	 * 
	 * A Supervisor inherits the IAS log level if undefined in the CDB.
	 */
	@Enumerated(EnumType.STRING)
	@Basic(optional=true)
	private LogLevelDao logLevel;
	
	/**
	 * This one-to-many annotation matches with the many-to-one
	 * annotation in the {@link DasuDao} 
	 */
	@OneToMany(mappedBy = "supervisor", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<DasuDao> dasus = new HashSet<DasuDao>();
	
	public SupervisorDao() {}
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		Objects.requireNonNull(id,"The DASU ID can't be null");
		String iden = id.trim();
		if (iden.isEmpty()) {
			throw new IllegalArgumentException("The DASU ID can't be an empty string");
		}
		this.id = iden;
	}

	public String getHostName() {
		return hostName;
	}

	public void setHostName(String host) {
		Objects.requireNonNull(host,"The host name can't be null");
		String temp = host.trim();
		if (temp.isEmpty()) {
			throw new IllegalArgumentException("The DASU host name can't be an empty string");
		}
		this.hostName = temp;
	}

	public LogLevelDao getLogLevel() {
		return logLevel;
	}

	public void setLogLevel(LogLevelDao logLevel) {
		this.logLevel = logLevel;
	}

	public void addDasu(DasuDao dasu) {
		Objects.requireNonNull(dasu,"The DASU can't be null");
		dasus.add(dasu);
		dasu.setSupervisor(this);
	}
	
	public void removeDasu(DasuDao dasu) {
		Objects.requireNonNull(dasu,"Cannot remove a null DASU");
		dasus.remove(dasu);
		dasu.setSupervisor(null); // This won't work
	}
	
	 public Set<DasuDao> getDasus() {
		 return dasus;
	 }
	 
	/**
	 * </code>toString()</code> prints a human readable version of the DASU
	 * where linked objects (like ASCES) are represented by their IDs only.
	 */
	@Override
	public String toString() {
		StringBuilder ret = new StringBuilder("Supervisor=[ID=");
		ret.append(getId());
		ret.append(", logLevel=");
		ret.append(getLogLevel());
		ret.append(", hostName=");
		ret.append(getHostName());
		ret.append(", DASUs");
		for (DasuDao dasu : getDasus()) {
			ret.append(" ");
			ret.append(dasu.getId());
		}
		ret.append(']');
		return ret.toString();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this==obj) {
			return true;
		}
		if (obj==null || !(obj instanceof SupervisorDao)) {
			return false;
		}
		SupervisorDao superv =(SupervisorDao)obj;
		return this.id.equals(superv.getId()) &&
				Objects.equals(this.hostName, superv.getHostName()) &&
				Objects.equals(this.logLevel, superv.getLogLevel()) &&
				Objects.equals(this.getDasus(), superv.getDasus());
	}
}
