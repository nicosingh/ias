/**
 * 
 */
package org.eso.ias.cdb.rdb;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.eso.ias.cdb.CdbWriter;
import org.eso.ias.cdb.pojos.AsceDao;
import org.eso.ias.cdb.pojos.DasuDao;
import org.eso.ias.cdb.pojos.IasDao;
import org.eso.ias.cdb.pojos.IasioDao;
import org.eso.ias.cdb.pojos.SupervisorDao;
import org.hibernate.Session;
import org.hibernate.Transaction;

/**
 * Writes IAS configuration into RDB.
 * 
 * @see CdbWriter
 * @author acaproni
 *
 */
public class RdbWriter implements CdbWriter {

	/**
	 * Write the ias in the passed file.
	 * 
	 * @param ias The IAS configuration to write in the file
	 * @see org.eso.ias.cdb.CdbWriter#writeIas(org.eso.ias.cdb.pojos.IasDao)
	 */
	@Override
	public void writeIas(IasDao ias) throws IOException {
		Objects.requireNonNull(ias, "The DAO object to persist can't be null");
		Session s=RdbUtils.getSession();
		Transaction t =s.beginTransaction();
		s.persist(ias);
		t.commit();
		s.flush();
	}

	/**
	 * Write the Supervisor in the passed file.
	 * 
	 * @param superv The Supervisor configuration to write in the file
	 * @see org.eso.ias.cdb.CdbWriter#writeSupervisor(org.eso.ias.cdb.pojos.SupervisorDao)
	 */
	@Override
	public void writeSupervisor(SupervisorDao superv) throws IOException {
		Objects.requireNonNull(superv, "The DAO object to persist can't be null");
		Session s=RdbUtils.getSession();
		Transaction t =s.beginTransaction();
		s.persist(superv);
		t.commit();
		s.flush();
	}

	/**
	 *  Write the DASU in the passed file.
	 * 
	 * @param dasu The DASU configuration to write in the file
	 * @see org.eso.ias.cdb.CdbWriter#writeDasu(org.eso.ias.cdb.pojos.DasuDao)
	 */
	@Override
	public void writeDasu(DasuDao dasu) throws IOException {
		Objects.requireNonNull(dasu, "The DAO object to persist can't be null");
		Session s=RdbUtils.getSession();
		Transaction t =s.beginTransaction();
		s.persist(dasu);
		t.commit();
		s.flush();
	}

	/**
	 * Write the ASCE in the passed file.
	 * 
	 * @param asce The ASCE configuration to write in the file
	 * @see org.eso.ias.cdb.CdbWriter#writeAsce(org.eso.ias.cdb.pojos.AsceDao)
	 */
	@Override
	public void writeAsce(AsceDao asce) throws IOException {
		Objects.requireNonNull(asce, "The DAO object to persist can't be null");
		Session s=RdbUtils.getSession();
		Transaction t =s.beginTransaction();
		s.persist(asce);
		t.commit();
		s.flush();
	}

	/**
	 * Write the IASIO in the file.
	 * <P>
	 * This method delegates to {@link #writeIasios(Set, boolean)}
	 * 
	 * @param iasio The IASIO configuration to write in the file
	 * @param append: if <code>true</code> the passed iasio is appended to the file
	 *                otherwise a new file is created
	 * @see org.eso.ias.cdb.CdbWriter#writeIasio(org.eso.ias.cdb.pojos.IasioDao, boolean)
	 * @see {@link #writeIasios(Set, boolean)}
	 */
	@Override
	public void writeIasio(IasioDao iasio, boolean append) throws IOException {
		Objects.requireNonNull(iasio, "The DAO object to persist can't be null");
		Set<IasioDao> set = new HashSet<IasioDao>(Arrays.asList(iasio));
		writeIasios(set,append);
	}

	/**
	 * Write the IASIOs in the file.
	 * 
	 * @param iasios The IASIOs to write in the file
	 * @param append: if <code>true</code> the passed iasios are appended to the file
	 *                otherwise a new file is created
	 * @see org.eso.ias.cdb.CdbWriter#writeIasios(java.util.Set, boolean)
	 */
	@Override
	public void writeIasios(Set<IasioDao> iasios, boolean append) throws IOException {
		Objects.requireNonNull(iasios, "The DAO object to persist can't be null");
		Session s=RdbUtils.getSession();
		Transaction t =s.beginTransaction();
		iasios.stream().forEach(io -> s.persist(io));
		t.commit();
		s.flush();
	}

}