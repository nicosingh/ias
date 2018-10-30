package org.eso.ias.sink.ltdb;

import org.apache.commons.cli.*;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.eso.ias.cdb.CdbReader;
import org.eso.ias.cdb.IasCdbException;
import org.eso.ias.cdb.json.CdbFiles;
import org.eso.ias.cdb.json.CdbJsonFiles;
import org.eso.ias.cdb.json.JsonReader;
import org.eso.ias.cdb.pojos.IasDao;
import org.eso.ias.cdb.pojos.LogLevelDao;
import org.eso.ias.cdb.rdb.RdbReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * The Kafka connector for the LTDB
 *
 * @author acaproni
 */
public class LtdbKafkaConnector extends SinkConnector {

    /**
     * The logger
     */
    private static final Logger logger = LoggerFactory.getLogger(LtdbKafkaConnector.class);

    /**
     * The string shown by the help
     */
    private static String cmdLineSyntax = "Usage: LtdbKafkaConnector Connector-ID [-j|-jcdb JSON-CDB-PATH] [-h|--help] [-x|--logLevel] level";

    /**
     * Parse the command line.
     *
     * If help is requested, prints the message and exits.
     *
     * @param args The params read from the command line
     * @param params the map of values read from the command line
     */
    private static void parseCommandLine(String[] args, Map<String, Optional<?>> params) {
        Options options = new Options();
        options.addOption("h", "help", false, "Print help and exit");
        options.addOption("j", "jcdb", true, "Use the JSON Cdb at the passed path");
        options.addOption("x", "logLevel", true, "Set the log level (TRACE, DEBUG, INFO, WARN, ERROR)");

        CommandLineParser parser = new DefaultParser();

        CommandLine cmdLine = null;
        try {
            cmdLine = parser.parse(options, args);
        } catch (Exception e) {
            HelpFormatter helpFormatter = new HelpFormatter();
            System.err.println("Exception parsing the command line: " + e.getMessage());
            e.printStackTrace(System.err);
            helpFormatter.printHelp(cmdLineSyntax, options);
            System.exit(-1);
        }
        boolean help = cmdLine.hasOption('h');

        Optional<String> jcdb = Optional.ofNullable(cmdLine.getOptionValue('j'));
        Optional<String> logLevelName = Optional.ofNullable(cmdLine.getOptionValue('x'));
        Optional<LogLevelDao> logLvl=Optional.empty();
        try {
            logLvl = logLevelName.map(LogLevelDao::valueOf);
        } catch (Exception e) {
            System.err.println("Unrecognized log level");
            HelpFormatter helpFormatter = new HelpFormatter();
            helpFormatter.printHelp(cmdLineSyntax, options);
            System.exit(-1);
        }


        List<String> remaingArgs = cmdLine.getArgList();

        Optional<String> supervId;
        if (remaingArgs.isEmpty()) {
            supervId = Optional.empty();
        } else {
            supervId = Optional.of(remaingArgs.get(0));
        }

        if (help) {
            new HelpFormatter().printHelp(cmdLineSyntax, options);
            System.exit(0);
        }
        if (!supervId.isPresent()) {
            System.err.println("Missing connector ID");
            new HelpFormatter().printHelp(cmdLineSyntax, options);
            System.exit(-1);
        }

        params.put("ID",supervId);
        params.put("jcdb",jcdb);
        params.put("log",logLvl);

        LtdbKafkaConnector.logger.info("Params from command line: jcdb={}, logLevel={} kafka connector ID={}",
                jcdb.orElse("Undefined"),
                logLvl.map(Enum::name).orElse("Undefined"),
                supervId.orElse("Undefined"));
    }

    public static void main(String[] args) {
        Map<String,Optional<?>> params = new HashMap<>();
        parseCommandLine(args,params);

        CdbReader cdbReader;

        Optional<?> jcdbOpt = params.get("jcdb");
        if (jcdbOpt.isPresent()) {
            String cdbPath = (String)jcdbOpt.get();
            File f = new File(cdbPath);
            if (!f.isDirectory() || !f.canRead()) {
                System.err.println("Invalid file path "+cdbPath);
                System.exit(-3);
            }

            CdbFiles cdbFiles=null;
            try {
                cdbFiles= new CdbJsonFiles(f);
            } catch (Exception e) {
                System.err.println("Error initializing JSON CDB "+e.getMessage());
                System.exit(-4);
            }
            cdbReader = new JsonReader(cdbFiles);
            LtdbKafkaConnector.logger.info("Using JSON CDB at {}",cdbPath);
        } else {
            // Get the RDB CDB
            cdbReader = new RdbReader();
        }

        IasDao iasDao = null;
        try {
            Optional<IasDao> iasDaoOpt = cdbReader.getIas();
            if (!iasDaoOpt.isPresent()) {
                logger.error("Got an empty IAS from CDB");
                System.exit(-1);
            }
            iasDao=iasDaoOpt.get();
        } catch (IasCdbException cdbEx) {
            logger.error("Error getting IAS configuration from CDB",cdbEx);
            System.exit(-1);
        }

        // Set the log level
        Optional<LogLevelDao> logLevelFromIasOpt = Optional.ofNullable(iasDao.getLogLevel());
        Optional<LogLevelDao> logLevelFromCmdLineOpt = (Optional<LogLevelDao>)params.get("log");
        org.eso.ias.logging.IASLogger.setLogLevel(
                logLevelFromCmdLineOpt.map(LogLevelDao::toLoggerLogLevel).orElse(null),
                logLevelFromIasOpt.map(LogLevelDao::toLoggerLogLevel).orElse(null),
                null);

        try {
            cdbReader.shutdown();
        } catch (Exception e) {
            LtdbKafkaConnector.logger.warn("Ignored exception while closing the RDB reader",e);
        }
    }

    @Override
    public void start(Map<String, String> map) {
        LtdbKafkaConnector.logger.info("Started");
    }

    @Override
    public Class<? extends Task> taskClass() {
        return LtdbKafkaTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int i) {
        ArrayList<Map<String, String>> configs = new ArrayList<>();
        // Only one input stream makes sense.
        Map<String, String> config = new HashMap<>();
        configs.add(config);
        return configs;
    }

    @Override
    public void stop() {
        LtdbKafkaConnector.logger.info("Stopped");
    }

    @Override
    public ConfigDef config() {
        ConfigDef ret =  new ConfigDef();

        ret.define("cassandra.contact.points", ConfigDef.Type.STRING, ConfigDef.Importance.HIGH,"Cassandra contact points");
        ret.define("ltdb.keyspace", ConfigDef.Type.STRING, ConfigDef.Importance.HIGH,"LTDB keyspace");
        return ret;
    }

    @Override
    public String version() {
        return getClass().getSimpleName();
    }
}