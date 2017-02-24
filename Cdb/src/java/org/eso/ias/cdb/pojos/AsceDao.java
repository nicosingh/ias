package org.eso.ias.cdb.pojos;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.ForeignKey;

/**
 * 
 * The pojo for the ASCE
 * 
 * @author acaproni
 *
 */
@Entity
@Table(name = "ASCE")
public class AsceDao {

	@Id
	@Column(name = "asce_id")
	private String id;
	
	/**
	 * The DASU that runs this ASCE 
	 */
	@ManyToOne
    @JoinColumn(name = "dasu_id", foreignKey = @ForeignKey(name = "dasu_id")
    )
    private DasuDao dasu;
	
	/**
	 * The jvm class of the transfer function
	 */
	@Basic(optional=false)
	private String tfClass;
	
	/**
	 * The IASIOs in input to the ASCE.
	 * 
	 * ManyToMany: the ASCE can have multiple IASIOs in input and the same IASIO can be 
	 * the input of many ASCEs
	 */
	@ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
	@JoinTable(name= "ASCE_IASIO",
	joinColumns = @JoinColumn(name="asce_id"),
	inverseJoinColumns = @JoinColumn(name = "io_id"))
    private Set<IasioDao> inputs = new HashSet<>();
	
	/**
	 * The output generated by the ASCE applying the TF to the inputs
	 */
	@OneToOne
    @JoinColumn(name = "OUTPUT_ID")
	private IasioDao output;
	
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinTable(name= "ASCE_PROPERTY",
		joinColumns = @JoinColumn(name="asce_id"),
		inverseJoinColumns = @JoinColumn(name = "props_id"))
	private Set<PropertyDao> props = new HashSet<>();
	
	public AsceDao() {}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		Objects.requireNonNull(id,"The ASCE ID can't be null");
		String iden = id.trim();
		if (iden.isEmpty()) {
			throw new IllegalArgumentException("The ASCE ID can't be an empty string");
		}
		this.id = iden;
	}

	public String getTfClass() {
		return tfClass;
	}

	public void setTfClass(String tfClass) {
		Objects.requireNonNull(tfClass,"The TF class of a ASCE can't be null");
		String temp = tfClass.trim();
		if (temp.isEmpty()) {
			throw new IllegalArgumentException("The TF class of a ASCE can't be an empty string");
		}
		this.tfClass = temp;
	}

	public IasioDao getOutput() {
		return output;
	}

	public Set<IasioDao> getInputs() {
		return inputs;
	}

	public void setOutput(IasioDao output) {
		Objects.requireNonNull(output,"The output of a ASCE can't be null");
		this.output = output;
	}

	public Set<PropertyDao> getProps() {
		return props;
	}
	
	public void setDasu(DasuDao dasu) {
		this.dasu=dasu;
	}

	public DasuDao getDasu() {
		return dasu;
	}
	
	/**
	 * </code>toString()</code> prints a human readable version of the ASCE
	 * where linked objects (like DASU, IASIOS..) are represented by their
	 * IDs only.
	 */
	@Override
	public String toString() {
		StringBuilder ret = new StringBuilder("ASCE=[ID=");
		ret.append(getId());
		ret.append(", Output=");
		ret.append(getOutput().getId());
		ret.append(", Inputs=");
		for (IasioDao iasio: getInputs()) {
			ret.append(' ');
			ret.append(iasio.getId());
		}
		ret.append(" TF class=");
		ret.append(getTfClass());
		ret.append(", DASU=");
		ret.append(getDasu().getId());
		ret.append(", Props=");
		for (PropertyDao prop: getProps()) {
			ret.append(' ');
			ret.append(prop.toString());
		}
		ret.append(']');
		return ret.toString();
	}
	
}
