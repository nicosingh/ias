package org.eso.ias.cdb.pojos;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.ForeignKey;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;

/**
 * 
 * The pojo for the ASCE
 * 
 * @author acaproni
 *
 */
@Entity(name = "ASCE")
public class AsceDao {

	@Id
	private String id;
	
	/**
	 * The DASU that runs this ASCE 
	 */
	@ManyToOne
    @JoinColumn(name = "dasu_id",
        foreignKey = @ForeignKey(name = "DASU_ID_FK")
    )
    private DasuDao dasu;
	
	/**
	 * The jvm class of the transfer function
	 */
	private String tfClass;
	
	/**
	 * The IASIOs in input to the ASCE.
	 * 
	 * ManyToMany: the ASCE can have multiple IASIOs in input and the same IASIO can be 
	 * the input of more ASCEs
	 */
	@ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<IasioDao> inputs = new ArrayList<>();
	
	/**
	 * The output generated by the ASCE applying the TF to the inputs
	 */
	@OneToOne
    @JoinColumn(name = "OUTPUT_ID_FK")
	private IasioDao output;
	
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	private List<PropertyDao> props = new ArrayList<>();
	
}
