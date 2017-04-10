package org.eso.ias.cdb.test.rdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.eso.ias.cdb.CdbReader;
import org.eso.ias.cdb.CdbWriter;
import org.eso.ias.cdb.pojos.AsceDao;
import org.eso.ias.cdb.pojos.DasuDao;
import org.eso.ias.cdb.pojos.IasDao;
import org.eso.ias.cdb.pojos.IasTypeDao;
import org.eso.ias.cdb.pojos.IasioDao;
import org.eso.ias.cdb.pojos.LogLevelDao;
import org.eso.ias.cdb.pojos.PropertyDao;
import org.eso.ias.cdb.pojos.SupervisorDao;
import org.eso.ias.cdb.rdb.RdbReader;
import org.eso.ias.cdb.rdb.RdbUtils;
import org.eso.ias.cdb.rdb.RdbWriter;
import org.eso.ias.cdb.test.json.TestJsonCdb;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

/**
 * Test reading and writing data from/to 
 * the relational database.
 * <P>
 * Reading and writing is done by {@link CdbReader} and {@link CdbWriter}
 * implementators as it is done in {@link TestJsonCdb} so, in principle the
 * same test can be run for text files and relational database.
 * <BR>The reason to have a separate test is because with hibernate there is 
 * no need to explicitly store objects contained in other objects as needed
 * by the CDB on files.  Actually, this test should be shorter and easier to read.
 * 
 * 
 * <EM>Note</em>: with the current implementation, 
 *                running this test will clear the content of the production
 *                database
 * 
 * @author acaproni
 *
 */
public class TestRdbCdb {
	
	/**
	 * Helper object to read and write the RDB
	 */
	private static final RdbUtils rdbUtils =  RdbUtils.getRdbUtils();
	
	/**
	 * The reader for the CDB RDB
	 */
	private final CdbReader cdbReader = new RdbReader();
	
	/**
	 * The reader for the CDB RDB
	 */
	private final CdbWriter cdbWriter = new RdbWriter();

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		// Clear the content of the DB
		
		// Remove all the tables
		try {
			rdbUtils.dropTables();
		} catch (Throwable t) {
			System.out.println("Failure dropping tables. Was the RDB empty?");
			System.out.println("Error "+t.getMessage()+" ignored");
		}
		
		// The create empty tables
		rdbUtils.createTables();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}
	
	@AfterClass public static void logout() {
        rdbUtils.close();
  }

	/**
	 * Test reading and writing the IAS
	 */
	@Test
	public void testIas() throws Exception {
		IasDao ias = new IasDao();
		ias.setLogLevel(LogLevelDao.DEBUG);
		
		PropertyDao p1 = new PropertyDao();
		p1.setName("P1-name");
		p1.setValue("1000");
		PropertyDao p2 = new PropertyDao();
		p2.setName("P2-name");
		p2.setValue("Name of P2");
		
		ias.getProps().add(p1);
		ias.getProps().add(p2);
		
		// Write the IAS
		cdbWriter.writeIas(ias);
		
		// Get the IAS from the reader
		Optional<IasDao> optIas = cdbReader.getIas();
		assertTrue("Got an empty IAS!", optIas.isPresent());
		assertEquals("The IASs differ!", ias, optIas.get());
		
		// Modify the IAS and save it again
		IasDao ias2 = optIas.get();
		ias2.setLogLevel(LogLevelDao.INFO);
		assertTrue("Error removing a property from the IAS",ias2.getProps().remove(p1));
		
		cdbWriter.writeIas(ias2);
		
		// Get the IAS from the reader
		Optional<IasDao> optIas2 = cdbReader.getIas();
		assertTrue("Got an empty IAS!", optIas2.isPresent());
		assertEquals("The IASs differ!", ias2, optIas2.get());
		assertEquals("Wrong number of properties",1,optIas2.get().getProps().size());
	}
	
	/**
	 * Test reading and writing of Supervisor
	 * @throws Exception
	 */
	@Test
	public void testSupervisor() throws Exception {
		
		SupervisorDao superv = new SupervisorDao();
		superv.setId("Supervisor-ID");
		superv.setHostName("almadev2.alma.cl");
		superv.setLogLevel(LogLevelDao.INFO);
		
		// Adds the DASUs
		DasuDao dasu1 = new DasuDao();
		dasu1.setId("DasuID1");
		dasu1.setSupervisor(superv);
		dasu1.setLogLevel(LogLevelDao.FATAL);
		superv.addDasu(dasu1);
		
		DasuDao dasu2 = new DasuDao();
		dasu2.setId("DasuID2");
		dasu2.setSupervisor(superv);
		dasu2.setLogLevel(LogLevelDao.WARN);
		superv.addDasu(dasu2);
		
		cdbWriter.writeSupervisor(superv);
		
		Optional<SupervisorDao> optSuperv = cdbReader.getSupervisor(superv.getId());
		assertTrue("Got an empty Supervisor!", optSuperv.isPresent());
		assertEquals("The Supervisors differ!", superv, optSuperv.get());
		
		// Modify the supervisor then save it again
		superv.setHostName("almadev.hq.eso.org");
		superv.removeDasu(dasu2.getId());
		
		cdbWriter.writeSupervisor(superv);
		
		// Check if it has been updated
		Optional<SupervisorDao> optSuperv2 = cdbReader.getSupervisor(superv.getId());
		assertTrue("Got an empty Supervisor!", optSuperv2.isPresent());
		assertEquals("The Supervisors differ!", superv, optSuperv2.get());
	}
	
	/**
	 * Test reading and writing of IASIO
	 * 
	 * @throws Exception
	 */
	@Test
	public void testIasio() throws Exception {
		IasioDao io = new IasioDao("IO-ID", "IASIO description", 125, IasTypeDao.INT);
		cdbWriter.writeIasio(io, true);
		
		Optional<IasioDao> iasioFromRdb = cdbReader.getIasio("IO-ID");
		assertTrue("Got an empty IASIO!", iasioFromRdb.isPresent());
		assertEquals("The IASIOs differ!", io, iasioFromRdb.get());
	}
	
	/**
	 * Test reading and writing of a set of IASIOs
	 * 
	 * @throws Exception
	 */
	@Test
	public void testIasios() throws Exception {
		IasioDao io1 = new IasioDao("IO-ID1", "IASIO descr1", 125, IasTypeDao.INT);
		IasioDao io2 = new IasioDao("IO-ID2", "IASIO descr2", 150, IasTypeDao.ALARM);
		IasioDao io3= new IasioDao("IO-ID3", "IASIO descr3", 250, IasTypeDao.BOOLEAN);
		IasioDao io4 = new IasioDao("IO-ID4", "IASIO descr4", 300, IasTypeDao.DOUBLE);
		IasioDao io5 = new IasioDao("IO-ID5", "IASIO descr5", 500, IasTypeDao.STRING);
		Set<IasioDao> iasios = new HashSet<>();
		iasios.add(io1);
		iasios.add(io2);
		iasios.add(io3);
		iasios.add(io4);
		iasios.add(io5);
		
		cdbWriter.writeIasios(iasios, true);
		
		Optional<Set<IasioDao>> iasiosFromRdb = cdbReader.getIasios();
		assertTrue("Got an empty set of IASIOs!", iasiosFromRdb.isPresent());
		assertEquals("The sets of IASIOs differ!", iasios, iasiosFromRdb.get());
	}
	
	/**
	 * Test reading and writing of DASU
	 * 
	 * @throws Exception
	 */
	@Test
	public void testDasu() throws Exception {
		// Test the reading/writing od a DASU with no ASCEs
		SupervisorDao superv = new SupervisorDao();
		superv.setId("SupervID");
		superv.setHostName("almadev.hq.eso.org");
		superv.setLogLevel(LogLevelDao.INFO);
		
		DasuDao dasuNoASCEs = new DasuDao();
		dasuNoASCEs.setId("A-DASU-For-Testing");
		dasuNoASCEs.setLogLevel(LogLevelDao.DEBUG);
		dasuNoASCEs.setSupervisor(superv);
		
		superv.addDasu(dasuNoASCEs);
		
		cdbWriter.writeSupervisor(superv);
		cdbWriter.writeDasu(dasuNoASCEs);
		
		Optional<DasuDao> dasuFromRdb = cdbReader.getDasu("A-DASU-For-Testing");
		assertTrue("Got an empty DASU!", dasuFromRdb.isPresent());
		assertEquals("The DASUs differ!", dasuNoASCEs, dasuFromRdb.get());
		
		// Test the reading/writing of a DASU with some ASCEs
		DasuDao dasuWithASCEs = new DasuDao();
		dasuWithASCEs.setId("A-DASU-With-ASCEs");
		dasuWithASCEs.setLogLevel(LogLevelDao.WARN);
		dasuWithASCEs.setSupervisor(superv);
		
		// Output of ASCE1
		IasioDao ioAsce1Out = new IasioDao("IASIO-OUT-1", "descr", 1500, IasTypeDao.DOUBLE);
		cdbWriter.writeIasio(ioAsce1Out, true);
		
		// ASCE1
		AsceDao asce1 = new AsceDao();
		asce1.setTfClass("org.eso.TfTest1");
		asce1.setId("ASCE1-ID");
		asce1.setDasu(dasuWithASCEs);
		asce1.setOutput(ioAsce1Out);
		dasuWithASCEs.addAsce(asce1);
		
		// Output of ASCE2
		IasioDao ioAsce2Out = new IasioDao("IASIO-OUT-2", "descr", 1050, IasTypeDao.BOOLEAN);
		cdbWriter.writeIasio(ioAsce2Out, true);
		
		// ASCE2
		AsceDao asce2 = new AsceDao();
		asce2.setTfClass("org.eso.TfTest2");
		asce2.setId("ASCE2-ID");
		asce2.setDasu(dasuWithASCEs);
		asce2.setOutput(ioAsce2Out);
		dasuWithASCEs.addAsce(asce2);
		
		cdbWriter.writeDasu(dasuWithASCEs);
		Optional<DasuDao> dasuWithAscesFromRdb = cdbReader.getDasu("A-DASU-With-ASCEs");
		assertTrue("Got an empty DASU!", dasuWithAscesFromRdb.isPresent());
		assertEquals("The DASUs differ!", dasuWithASCEs, dasuWithAscesFromRdb.get());
		assertEquals("The number of ASCEs in the DASU is wrong",2, dasuWithAscesFromRdb.get().getAsces().size());
	}
	/**
	 * Test reading and writing of ASCE
	 * 
	 * @throws Exception
	 */
	@Test
	
	public void testAsce() throws Exception {
		// The supervisor where the DASU containing the ASCE runs
		SupervisorDao superv = new SupervisorDao();
		superv.setId("SuperID");
		superv.setHostName("almaias.hq.eso.org");
		superv.setLogLevel(LogLevelDao.DEBUG);
		
		// The DAUS where the ASCE runs
		DasuDao dasu = new DasuDao();
		dasu.setId("A-DASU-For-Testing");
		dasu.setLogLevel(LogLevelDao.DEBUG);
		dasu.setSupervisor(superv);
		
		superv.addDasu(dasu);
		
		cdbWriter.writeSupervisor(superv);
		cdbWriter.writeDasu(dasu);
		
		Set<IasioDao> iasios = new HashSet<>();
		// The output of the ASCE
		IasioDao ioOut = new IasioDao("IASIO-OUT", "description of output", 1234, IasTypeDao.ALARM);
		cdbWriter.writeIasio(ioOut, true);
		
		// The 5 inputs of the ASCE
		IasioDao ioIn1 = new IasioDao("IASIO-IN1", "input-1", 100, IasTypeDao.DOUBLE);
		iasios.add(ioIn1);
		IasioDao ioIn2 = new IasioDao("IASIO-IN2", "input-2", 200, IasTypeDao.INT);
		iasios.add(ioIn2);
		IasioDao ioIn3 = new IasioDao("IASIO-IN3", "input-3", 300, IasTypeDao.BOOLEAN);
		iasios.add(ioIn3);
		IasioDao ioIn4 = new IasioDao("IASIO-IN4", "input-4", 400, IasTypeDao.ALARM);
		iasios.add(ioIn4);
		IasioDao ioIn5 = new IasioDao("IASIO-IN5", "input-5", 500, IasTypeDao.STRING);
		iasios.add(ioIn5);
		
		cdbWriter.writeIasios(iasios, true);
		
		// The props of the ASCE
		PropertyDao p1 = new PropertyDao();
		p1.setName("Prop1-Name");
		p1.setValue("Prop1-value");
		PropertyDao p2 = new PropertyDao();
		p2.setName("Prop2-Name");
		p2.setValue("Prop2-value");
		
		// The ASCE to test
		AsceDao asce = new AsceDao();
		asce.setTfClass("org.eso.TfTest");
		asce.setId("ASCE-ID");
		asce.setDasu(dasu);
		asce.setOutput(ioOut);
		iasios.stream().forEach(io -> asce.addInput(io, true));
		asce.getProps().add(p1);
		asce.getProps().add(p2);
		dasu.addAsce(asce);
		
		cdbWriter.writeAsce(asce);
		
		Optional<AsceDao> asceFromRdb = cdbReader.getAsce("ASCE-ID");
		assertTrue("Got an empty ASCE!", asceFromRdb.isPresent());
		assertEquals("The ASCEs differ!", asce, asceFromRdb.get());
		assertEquals("The number of inputs of the ASCE differ!", iasios.size(), asceFromRdb.get().getInputs().size());
		assertEquals("The number of properties of the ASCE differ!", 2, asceFromRdb.get().getProps().size());
	}
}
