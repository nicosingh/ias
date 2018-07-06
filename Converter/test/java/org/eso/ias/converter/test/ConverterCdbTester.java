package org.eso.ias.converter.test;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.eso.ias.cdb.CdbReader;
import org.eso.ias.cdb.CdbWriter;
import org.eso.ias.cdb.json.CdbFiles;
import org.eso.ias.cdb.json.CdbFolders;
import org.eso.ias.cdb.json.CdbJsonFiles;
import org.eso.ias.cdb.json.JsonReader;
import org.eso.ias.cdb.json.JsonWriter;
import org.eso.ias.cdb.pojos.IasTypeDao;
import org.eso.ias.cdb.pojos.IasioDao;
import org.eso.ias.cdb.pojos.TemplateDao;
import org.eso.ias.converter.config.IasioConfigurationDaoImpl;
import org.eso.ias.converter.config.MonitorPointConfiguration;
import org.eso.ias.types.IASTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the CDB DAO of the converter,
 * i,e, the {@link IasioConfigurationDaoImpl}.
 * 
 * @author acaproni
 *
 */
class ConverterCdbTester {

    /**
     * The logger
     */
    private static final Logger logger = LoggerFactory.getLogger(ConverterCdbTester.class);
	
	/**
	 * The parent folder of the CDB is the actual folder
	 */
    public static final Path cdbParentPath =  FileSystems.getDefault().getPath(".");
	
	/**
	 * The prefix of the IDs of the IASIOs written in the config file
	 */
	public static final String IasioIdPrefix="IoID-";

    /**
     * The ID of the template
     */
	public static String templateId = "TemplateId";

	/** The ID of the tmeplated IASIO */
	public static String idOfTemplatedIasio = "TempaltedIasioId";
	
	/**
	 * The folder struct of the CDB
	 */
	private CdbFiles cdbFiles;
	
	private IasioConfigurationDaoImpl configDao;

	/**
	 * Create a Iasio ID from the given index
	 * 
	 * @param n The index
	 * @return The ID
	 */
    private String buildIasId(int n) {
		return IasioIdPrefix+n;
	}
	
	/**
	 * Create a IasTypeDao from the given index
	 * 
	 * @param n The index
	 * @return The IasTypeDao
	 */
    private static IasTypeDao buildIasType(int n) {
		return IasTypeDao.values()[n%IasTypeDao.values().length];
	}
	
	/**
	 * Populate the CDB with the passed number of IASIO
	 * 
	 * @param numOfIasios the number of IASIOs to write in the configurations
	 * @param cdbFiles The folder struct of the CDB
	 * @throws Exception
	 */
    private void populateCDB(int numOfIasios,CdbFiles cdbFiles) throws Exception {
		Objects.requireNonNull(cdbFiles);
		if (numOfIasios<=0) {
			throw new IllegalArgumentException("Invalid number of IASIOs to write in the CDB");
		}
		logger.info("Populating JSON CDB in {}",cdbParentPath.toAbsolutePath().toString());
		CdbWriter cdbWriter = new JsonWriter(cdbFiles);
		createTmplate(cdbWriter);
		populateCDB(numOfIasios, cdbWriter);
		logger.info("CDB created");
	}
	
	/**
	 * Populate the CDB with the passed number of IASIO
	 * 
	 * @param numOfIasios the number of IASIOs to write in the configurations
	 * @param cdbWriter The writer of the CDB
	 * @throws Exception
	 */
    private void populateCDB(int numOfIasios,CdbWriter cdbWriter) throws Exception {
		Objects.requireNonNull(cdbWriter);
		if (numOfIasios<=0) {
			throw new IllegalArgumentException("Invalid number of IASIOs to write in the CDB");
		}
		logger.info("Adding {} IasioDao to the CDB",numOfIasios);
		Set<IasioDao> iasios = buildIasios(numOfIasios);
		// Adds one templated IASIO
        IasTypeDao iasType = buildIasType(2);;
        IasioDao iasio = new IasioDao(idOfTemplatedIasio, "IASIO description", iasType,"http://www.eso.org/almm/alarms");
        iasio.setTemplateId(templateId);
        iasios.add(iasio);

        iasios.forEach( i -> logger.debug("IASIO to write {}",i.getId()));

		cdbWriter.writeIasios(iasios, false);
	}
	
	/**
	 * Build the set of IASIOs configuration to write in the CDB
	 * 
	 * @param numOfIasios the number of IASIOs to write in the configurations
	 * @return the set of IASIOs configuration to write in the CDB
	 */
    private Set<IasioDao> buildIasios(int numOfIasios) {
		if (numOfIasios<=0) {
			throw new IllegalArgumentException("Invalid number of IASIOs to write in the CDB");
		}
		Set<IasioDao> iasios = new HashSet<>(numOfIasios);
		for (int t=0; t<numOfIasios; t++) {
			IasTypeDao iasType = buildIasType(t);
			IasioDao iasio = new IasioDao(buildIasId(t), "IASIO description", iasType,"http://www.eso.org/almm/alarms");
			iasios.add(iasio);
		}
		return iasios;
	}

    /**
     * Write a template in the CDB
     *
     * @param cdbWriter the CDB writer
     * @throws Exception
     */
    private void createTmplate(CdbWriter cdbWriter) throws Exception {
	    logger.info("Adding template");
        TemplateDao tDao = new TemplateDao(templateId, 5,15);
        cdbWriter.writeTemplate(tDao);
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Remove any CDB folder if present
        CdbFolders.ROOT.delete(cdbParentPath);
        assertFalse(CdbFolders.ROOT.exists(cdbParentPath));
        cdbFiles = new CdbJsonFiles(cdbParentPath);
        CdbReader cdbReader = new JsonReader(cdbFiles);
        configDao = new IasioConfigurationDaoImpl(cdbReader);

    }
	
	@AfterEach
	public void tearDown() throws Exception{
		configDao.close();
		CdbFolders.ROOT.delete(cdbParentPath);
		assertFalse(CdbFolders.ROOT.exists(cdbParentPath));
	}
	
	/**
	 * Check if the DAO stores all the IASIOs.
	 */
	@Test
	public void testNumberOfIasios() throws Exception {
		int mpPointsToCreate=1500;
		populateCDB(mpPointsToCreate,cdbFiles);
		configDao.initialize();
		int found=0;
		for (int t=0; t<mpPointsToCreate; t++) {
			if (configDao.getConfiguration(buildIasId(t))!=null) {
				found++;
			}
		}
		assertEquals(mpPointsToCreate,found);
	}


	/**
	 * Check if the DAO correctly associates the configuration
	 * to a IASIO id
	 */
	@Test
	public void testIasiosDataIntegrity() throws Exception {
		int mpPointsToCreate=2000;
		populateCDB(mpPointsToCreate,cdbFiles);
		configDao.initialize();
		
		for (int t=0; t<mpPointsToCreate; t++) {
			IasTypeDao iasType = buildIasType(t);
			MonitorPointConfiguration mpConf=configDao.getConfiguration(buildIasId(t));
			assertNotNull(mpConf);
			assertEquals(IASTypes.valueOf(iasType.toString()), mpConf.mpType);
		}
	}

	@Test
    public void testTemplatesIasio() throws  Exception {
       int mpPointsToCreate=5;
       populateCDB(mpPointsToCreate,cdbFiles);
       populateCDB(mpPointsToCreate,cdbFiles);
       configDao.initialize();
       MonitorPointConfiguration mpConf=configDao.getConfiguration(idOfTemplatedIasio);
       assertNotNull(mpConf);
       assertTrue(mpConf.minTemplateIndex.isPresent());
       assertTrue(mpConf.maxTemplateIndex.isPresent());
       assertTrue(5==mpConf.minTemplateIndex.get());
       assertTrue(15==mpConf.maxTemplateIndex.get());
    }
}


