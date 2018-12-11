package org.eso.ias.monitor

import java.io.File
import java.util.concurrent.CountDownLatch

import com.typesafe.scalalogging.Logger
import org.apache.commons.cli.{CommandLine, CommandLineParser, DefaultParser, HelpFormatter, Options}
import org.eso.ias.cdb.CdbReader
import org.eso.ias.cdb.json.{CdbFiles, CdbJsonFiles, JsonReader}
import org.eso.ias.cdb.pojos.LogLevelDao
import org.eso.ias.cdb.rdb.RdbReader
import org.eso.ias.heartbeat.consumer.HbKafkaConsumer
import org.eso.ias.logging.IASLogger
import org.eso.ias.types.{Identifier, IdentifierType}

import scala.collection.JavaConverters
import scala.util.{Failure, Success, Try}

/**
  * Monitor the stat of the IAS and sends alarms
  *
  * In this version, alarms are pushed in the core topic and willl  be read
  * from there by the web server.
  * TODO: send alarms (al least some of them) to the web server even wehn
  *       kafka is down
  *
  * @param hbKafkaConsumer the consumer of HBs from the kafka topic
  * @param identifier The identifier of the monitor tool
  * @param pluginIds The IDs of the plugins to monitor
  * @param converterIds The IDs of the converters to monitor
  * @param clientIds The IDs of the clients to monitor
  * @param sinkIds The IDs of the sink clients to monitor
  * @param supervisorIds The IDs of the supervisors to monitor
  * @param kafkaConenctorConfigs The IDs of the kafka sink connectors to monitor
  * @param threshold The threshold to decide when a HB is too late
  * @param refreshRate the refresh rate (seconds) to send alarms produced by the monitor
  */
class IasMonitor(
                hbKafkaConsumer: HbKafkaConsumer,
                val identifier: Identifier,
                pluginIds: Set[String],
                converterIds: Set[String],
                clientIds: Set[String],
                sinkIds: Set[String],
                supervisorIds: Set[String],
                kafkaConenctorConfigs: Set[KafkaSinkConnectorConfig],
                threshold: Long,
                val refreshRate: Long) {
  require(refreshRate>0,"Invalid negative or zero refresh rate")
  val hbMonitor: HbMonitor = new HbMonitor(hbKafkaConsumer,pluginIds,converterIds,clientIds,sinkIds,supervisorIds,threshold)

  /** Start the monitoring */
  def start(): Unit = {
    hbMonitor.start()
  }

  /** Stop monitoring and free resources */
  def shutdown(): Unit = {
    hbMonitor.shutdown()
  }
}

object IasMonitor {

  /** The logger */
  val logger: Logger = IASLogger.getLogger(IasMonitor.getClass)

  /** Build the usage message */
  val cmdLineSyntax: String = "Monitor Monitor-ID [-h|--help] [-j|-jcdb JSON-CDB-PATH] [-x|--logLevel log level]"

  /**
    * Parse the command line.
    *
    * If help is requested, prints the message and exits.
    *
    * @param args The params read from the command line
    * @return a tuple with the Id of the monitor, the path of the cdb and the log level dao
    */
  def parseCommandLine(args: Array[String]): (Option[String],  Option[String], Option[LogLevelDao], Option[String]) = {
    val options: Options = new Options
    options.addOption("h", "help",false,"Print help and exit")
    options.addOption("j", "jcdb", true, "Use the JSON Cdb at the passed path")
    options.addOption("x", "logLevel", true, "Set the log level (TRACE, DEBUG, INFO, WARN, ERROR)")
    options.addOption("f", "configFile", true, "Config file")

    val parser: CommandLineParser = new DefaultParser
    val cmdLineParseAction = Try(parser.parse(options,args))
    if (cmdLineParseAction.isFailure) {
      val e = cmdLineParseAction.asInstanceOf[Failure[Exception]].exception
      println(e + "\n")
      new HelpFormatter().printHelp(cmdLineSyntax, options)
      System.exit(-1)
    }

    val cmdLine = cmdLineParseAction.asInstanceOf[Success[CommandLine]].value
    val help = cmdLine.hasOption('h')
    val jcdb = Option(cmdLine.getOptionValue('j'))
    val file = Option(cmdLine.getOptionValue('f'))

    val logLvl: Option[LogLevelDao] = {
      val t = Try(Option(cmdLine.getOptionValue('x')).map(level => LogLevelDao.valueOf(level)))
      t match {
        case Success(opt) => opt
        case Failure(f) =>
          println("Unrecognized log level")
          new HelpFormatter().printHelp(cmdLineSyntax, options)
          System.exit(-1)
          None
      }
    }

    val remaingArgs = cmdLine.getArgList

    val monitorId = if (remaingArgs.isEmpty) None else Some(remaingArgs.get(0))

    if (!help && monitorId.isEmpty) {
      println("Missing Monitor ID")
      new HelpFormatter().printHelp(cmdLineSyntax, options)
      System.exit(-1)
    }
    if (help) {
      new HelpFormatter().printHelp(cmdLineSyntax, options)
      System.exit(0)
    }

    val ret = (monitorId, jcdb, logLvl,file)
    IasMonitor.logger.info("Params from command line: jcdb={}, logLevel={} monitor ID={}, config file {}",
      ret._2.getOrElse("Undefined"),
      ret._3.getOrElse("Undefined"),
      ret._1.getOrElse("Undefined"),
      ret._4.getOrElse("Undefined")
    )
    ret

  }

  /**
    * Entry point of the monitor tool
    *
    * @param args command line args
    */
  def main(args: Array[String]): Unit = {
    val parsedArgs = parseCommandLine(args)
    require(parsedArgs._1.nonEmpty, "Missing identifier in command line")

    val monitorId = parsedArgs._1.get

    val reader: CdbReader = {
      if (parsedArgs._2.isDefined) {
        val jsonCdbPath = parsedArgs._2.get
        logger.info("Using JSON CDB @ {}",jsonCdbPath)
        val cdbFiles: CdbFiles = new CdbJsonFiles(jsonCdbPath)
        new JsonReader(cdbFiles)
      } else {
        new RdbReader()
      }
    }

    /**
      *  Get the configuration of the IAS from the CDB
      */
    val (refreshRate, hbFrequency, logLvlvFromCdb, kafkaBrokers) = {

      val iasDaoOpt = reader.getIas
      require(iasDaoOpt.isPresent, "Cannot read IAS config from CDB")

      val refRate: Int = iasDaoOpt.get().getRefreshRate
      val hbRate: Int = iasDaoOpt.get().getHbFrequency
      val logLvl: LogLevelDao = iasDaoOpt.get.getLogLevel
      val brokers: String = iasDaoOpt.get.getBsdbUrl


      (refRate, hbRate, logLvl, brokers)
    }

    // Get the configuration from the CDB or from the passed
    // configuration file
    val config: IasMonitorConfig = {
      if (parsedArgs._4.isDefined) {
        val fName = parsedArgs._4.get
        IasMonitor.logger.info("Reading configuration from file {}",fName)
        IasMonitorConfig.fromFile(new File(fName))
      } else {
        IasMonitor.logger.info("Reading configuration of Monitor {} from CDB",monitorId)
        val configOpt = reader.getClientConfig(monitorId)
        require(configOpt.isPresent,"Config of client "+monitorId+" NOT found in CDB")
        require(configOpt.get().getId==monitorId,"Wrong ID from config file: found "+configOpt.get().getId+" instead of "+monitorId)
        val configStr = configOpt.get().getConfig
        IasMonitorConfig.valueOf(configStr)
      }
    }

    // The IDs of the Supervisor to monitor
    //
    // The IDS of the supervisor are read from the CDB
    // but the ones included in the configration must be discarded
    val supervisorIds = {
      val supervisorIdsFromCdb = reader.getSupervisorIds
      val ids: Set[String] = if (supervisorIdsFromCdb.isPresent) {
        JavaConverters.asScalaSet(supervisorIdsFromCdb.get).toSet
      } else {
        Set.empty[String]
      }
      IasMonitor.logger.info("Found {} supervisors from CDB: {}",ids.size,ids.mkString(","))

      val excludedIds = JavaConverters.asScalaSet(config.excludedSupervisorIds).toSet
      if (excludedIds.nonEmpty) {
        IasMonitor.logger.info("Will not monitor {} supervisors: {}",excludedIds.size,excludedIds.mkString(","))
      }

      ids.filter(id => !excludedIds.contains(id))
    }
    IasMonitor.logger.info("{} supervisors to monitor: {}",supervisorIds.size,supervisorIds.mkString(","))

    val pluginIds = JavaConverters.asScalaSet(config.getPluginIds).toSet
    IasMonitor.logger.info("{} plugins to monitor: {}",pluginIds.size,pluginIds.mkString(","))

    val converterIds = JavaConverters.asScalaSet(config.getConverterIds).toSet
    IasMonitor.logger.info("{} converters to monitor: {}",converterIds.size,converterIds.mkString(","))

    val clientIds = JavaConverters.asScalaSet(config.getClientIds).toSet
    IasMonitor.logger.info("{} clients to monitor: {}",clientIds.size,clientIds.mkString(","))

    val sinkIds = JavaConverters.asScalaSet(config.getSinkIds).toSet
    IasMonitor.logger.info("{} sink clients to monitor: {}",sinkIds.size,sinkIds.mkString(","))

    val kafkaConenctorConfigs = JavaConverters.asScalaSet(config.getKafkaSinkConnectors).toSet
    IasMonitor.logger.info("{} kafka connectors to monitor: {}",
      kafkaConenctorConfigs.size,
      kafkaConenctorConfigs.mkString(","))

    val threshold = config.getThreshold

    reader.shutdown()

     // The identifier of the monitor
    val identifier = new Identifier(monitorId, IdentifierType.CLIENT, None)

    // The consumer of HBs
    val hbConsumer: HbKafkaConsumer = new HbKafkaConsumer(kafkaBrokers,identifier.id)

    val monitor = new IasMonitor(
      hbConsumer,
      identifier,
      pluginIds,
      converterIds,
      clientIds,
      sinkIds,
      supervisorIds,
      kafkaConenctorConfigs,
      threshold,
      refreshRate)

    logger.debug("Starting the monitoring")
    monitor.start()
    logger.info("Monitoring started")

    val latch = new CountDownLatch(1)

    val shutdownHookThread = {
      val r = new Runnable{
        override def run(): Unit = {
          monitor.shutdown()
          latch.countDown()
        }
      }
      new Thread(r,"IasMonitor-ShutdownHook")
    }
    Runtime.getRuntime.addShutdownHook( shutdownHookThread)

    // Wait forever
    latch.await()
    logger.info("Done")

  }
}