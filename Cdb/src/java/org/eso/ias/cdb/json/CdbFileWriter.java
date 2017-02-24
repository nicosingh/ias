package org.eso.ias.cdb.json;

import java.io.IOException;
import java.util.Set;

import org.eso.ias.cdb.pojos.AsceDao;
import org.eso.ias.cdb.pojos.DasuDao;
import org.eso.ias.cdb.pojos.IasDao;
import org.eso.ias.cdb.pojos.IasioDao;
import org.eso.ias.cdb.pojos.SupervisorDao;

/**
 * Interface to flush the content of the CDB pojos
 * to files.
 * 
 * @author acaproni
 */
public interface CdbFileWriter {
	/**
	 * Write the ias in the passed file.
	 * 
	 * @param ias The IAS configuration to write in the file
	 */
	public void writeIas(IasDao ias) throws IOException;
	
	/**
	 * Write the Supervisor in the passed file.
	 * 
	 * @param superv The Supervisor configuration to write in the file
	 */
	public void writeSupervisor(SupervisorDao superv) throws IOException ;
	
	/**
	 * Write the DASU in the passed file.
	 * 
	 * @param dasu The DASU configuration to write in the file
	 */
	public void writeDasu(DasuDao dasu) throws IOException ;
	
	/**
	 * Write the ASCE in the passed file.
	 * 
	 * @param asce The ASCE configuration to write in the file
	 */
	public void writeAsce(AsceDao asce) throws IOException ;
	
	/**
	 * Write the IASIO in the file.
	 * 
	 * @param iasio The IASIO configuration to write in the file
	 * @param append: if <code>true</code> the passed iasio is appended to the file
	 *                otherwise a new file is created
	 */
	public void writeIasio(IasioDao iasio, boolean append) throws IOException ;
	
	/**
	 * Write the IASIOs in the file.
	 * 
	 * @param iasios The IASIOs to write in the file
	 * @param append: if <code>true</code> the passed iasios are appended to the file
	 *                otherwise a new file is created
	 */
	public void writeIasios(Set<IasioDao> iasios, boolean append) throws IOException ;
}
