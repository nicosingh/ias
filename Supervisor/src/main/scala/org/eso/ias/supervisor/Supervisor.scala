package org.eso.ias.supervisor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import ch.qos.logback.classic.Level
import com.typesafe.scalalogging.Logger
import org.apache.commons.cli.{CommandLine, CommandLineParser, DefaultParser, HelpFormatter, Options}
import org.eso.ias.cdb.CdbReader
import org.eso.ias.cdb.json.{CdbFiles, CdbJsonFiles, JsonReader}
import org.eso.ias.cdb.pojos._
import org.eso.ias.cdb.rdb.RdbReader
import org.eso.ias.dasu.publisher.{KafkaPublisher, OutputPublisher}
import org.eso.ias.dasu.subscriber.{InputSubscriber, InputsListener, KafkaSubscriber}
import org.eso.ias.dasu.{Dasu, DasuImpl}
import org.eso.ias.heartbeat.publisher.HbKafkaProducer
import org.eso.ias.heartbeat.serializer.HbJsonSerializer
import org.eso.ias.heartbeat.{HbEngine, HbProducer, HeartbeatStatus}
import org.eso.ias.kafkautils.KafkaHelper
import org.eso.ias.logging.IASLogger
import org.eso.ias.types.{IASValue, Identifier, IdentifierType}
import org.eso.ias.utils.ISO8601Helper

import scala.collection.JavaConverters
import scala.util.{Failure, Success, Try}

/**
 * A Supervisor is the container to run several DASUs into the same JVM.
 * 
 * The Supervisor blindly forward inputs to each DASU and sends the outpts to the BSDB
 * without adding any other heuristic: things like updating validities when an input
 * is not refreshed are not part of the Supervisor.
 * 
 * The Supervisor gets IASIOs from a InputSubscriber and publishes
 * IASValues to the BSDB by means of a OutputPublisher.
 * The Supervisor itself is the publisher and subscriber for the DASUs i.e.
 * the Supervisor acts as a bridge:
 *  * IASIOs read from the BSDB are forwarded to the DASUs that need them as input:
 *    the Supervisor has its own subscriber to receive values from the BSDB that 
 *    are then forwarded to each DASU for processing
 *  * values produced by the DASUs are forwarded to the BSDB: the DASUs publishes the output 
 *    they produce to the supervisor that, in turn, forward each of them to its own publisher.
 * 
 * The same interfaces, InputSubscriber and OutputPublisher, 
 * are used by DASUs and Supervisors in this way a DASU can be easily tested
 * directly connected to Kafka (for example) without the need to have
 * it running into a Supervisor.
 * 
 * DASUs are built by invoking the dasufactory passed in the constructor: 
 * test can let the Supervisor run with their mockup implementation of a DASU.
 * 
 * @param supervisorIdentifier the identifier of the Supervisor
 * @param outputPublisher the publisher to send the output
 * @param inputSubscriber the subscriber getting events to be processed
 * @param hbProducer the subscriber to send heartbeats 
 * @param cdbReader the CDB reader to get the configuration of the DASU from the CDB
 * @param dasuFactory: factory to build DASU
  * @param logLevelFromCommandLine The log level from the command line;
  *                                None if the parameter was not set in the command line
  *
 */

class Supervisor(
    val supervisorIdentifier: Identifier,
    private val outputPublisher: OutputPublisher,
    private val inputSubscriber: InputSubscriber,
    private val hbProducer: HbProducer,
    cdbReader: CdbReader,
    dasuFactory: (DasuDao, Identifier, OutputPublisher, InputSubscriber) => Dasu,
    logLevelFromCommandLine: Option[LogLevelDao])
    extends InputsListener with InputSubscriber with  OutputPublisher {
  require(Option(supervisorIdentifier).isDefined,"Invalid Supervisor identifier")
  require(Option(outputPublisher).isDefined,"Invalid output publisher")
  require(Option(inputSubscriber).isDefined,"Invalid input subscriber")
  require(Option(cdbReader).isDefined,"Invalid CDB reader")
  require(Option(logLevelFromCommandLine).isDefined,"Invalid log level")
  
  /** The ID of the Supervisor */
  val id: String = supervisorIdentifier.id
  
  Supervisor.logger.info("Building Supervisor [{}] with fullRunningId [{}]",id,supervisorIdentifier.fullRunningID)

  val iasDao: IasDao =
    Try (cdbReader.getIas ) match {
      case Success(value) => value.orElseThrow(() => new Exception("IasDao not found"))
      case Failure(exception) => throw new Exception("Failure reading IAS from CDB",exception)
    }
  
  /** The heartbeat Engine */
  val hbEngine: HbEngine = HbEngine(supervisorIdentifier.fullRunningID,iasDao.getHbFrequency,hbProducer)

  /**
    * The refresh rate in mseconds
    *
    * The refresh rate is used only to detetct if the Supervisor is too slow
    * processing values.
    * Auto refresh is, in fact, implemented by DASUs
    */
  val refreshRate: Long = TimeUnit.MILLISECONDS.convert(iasDao.getRefreshRate, TimeUnit.SECONDS)

  
  // Get the configuration of the supervisor from the CDB
  val supervDao : SupervisorDao = {
    val supervDaoOpt = cdbReader.getSupervisor(id)
    require(supervDaoOpt.isPresent,"Supervisor ["+id+"] configuration not found on cdb")
    supervDaoOpt.get
  }
  Supervisor.logger.info("Supervisor [{}] configuration retrieved from CDB",id)

  // Set the log level
  {
    val iasLogLevel: Option[Level] = Option(iasDao.getLogLevel).map(_.toLoggerLogLevel)
    val supervLogLevel: Option[Level] =  Option(supervDao.getLogLevel).map(_.toLoggerLogLevel)
    val cmdLogLevel: Option[Level] = logLevelFromCommandLine.map(_.toLoggerLogLevel)
    val level: Option[Level] = IASLogger.setLogLevel(cmdLogLevel,iasLogLevel,supervLogLevel)
    // Log a message that, depending on the log level can be discarded
    level.foreach(l => Supervisor.logger.info("Log level set to {}",l.toString))
  }
  
  /**
   * Gets the definitions of the DASUs to run in the Supervisor from the CDB
   */
  val dasusToDelpoy: Set[DasuToDeployDao] = JavaConverters.asScalaSet(cdbReader.getDasusToDeployInSupervisor((id))).toSet
  require(dasusToDelpoy.nonEmpty,"No DASUs to run in Supervisor "+id)
  Supervisor.logger.info("Supervisor [{}], {} DASUs to run: {}",
      id,
      dasusToDelpoy.size.toString,
      dasusToDelpoy.map(d => d.getDasu().getId()).mkString(", "))
  
  // Initialize the consumer and exit in case of error 
  val inputSubscriberInitialized = inputSubscriber.initializeSubscriber()
  inputSubscriberInitialized match {
    case Failure(f) => Supervisor.logger.error("Supervisor [{}] failed to initialize the consumer", id,f);
                       System.exit(-1)
    case Success(s) => Supervisor.logger.info("Supervisor [{}] subscriber successfully initialized",id)
  }
  
  // Initialize the producer and exit in case of error 
  val outputProducerInitialized: Try[Unit] = outputPublisher.initializePublisher()
  outputProducerInitialized match {
    case Failure(f) => Supervisor.logger.error("Supervisor [{}] failed to initialize the producer", id,f);
                       System.exit(-2)
    case Success(s) => Supervisor.logger.info("Supervisor [{}] producer successfully initialized",id)
  }
  
  // Get the DasuDaos from the set of DASUs to deploy:
  // the helper transform the templated DASUS into normal ones
  val dasuDaos: Set[DasuDao] = {
    val helper = new TemplateHelper(dasusToDelpoy)
    helper.normalize()
  }
  assert(dasuDaos.size==dasusToDelpoy.size)
  
  dasuDaos.foreach(d => Supervisor.logger.info("Supervisor [{}]: building DASU from DasuDao {}",id,d.toString))
  
  // Build all the DASUs
  val dasus: Map[String, Dasu] = dasuDaos.foldLeft(Map.empty[String,Dasu])((m, dasuDao) => 
    m + (dasuDao.getId -> dasuFactory(dasuDao,supervisorIdentifier,this,this)))
  
  /**
   * The IDs of the DASUs instantiated in the Supervisor
   */
  val dasuIds = dasuDaos.map(_.getId)
  Supervisor.logger.info("Supervisor [{}] built {} DASUs: {}",id, dasus.size.toString,dasuIds.mkString(", "))
  
  /**
   * Associate each DASU with the Set of inputs it needs.
   * 
   * the key is the ID of the DASU, the value is 
   * the set of inputs to send to the DASU
   */
  val iasiosToDasusMap: Map[String, Set[String]] = startDasus()
  Supervisor.logger.info("Supervisor [{}] associated IASIOs IDs to DASUs", id)
  
  val cleanedUp: AtomicBoolean = new AtomicBoolean(false) // Avoid cleaning up twice
  val shutDownThread: Thread =addsShutDownHook()
  
  /** Flag to know if the Supervisor has been started */
  val started = new AtomicBoolean(false)

  val statsLogger: SupervisorStatistics = new SupervisorStatistics(id,dasuIds)
  
  Supervisor.logger.info("Supervisor [{}] built",id)
  
  /**
   * Start each DASU and gets the list of inputs it needs to forward to the ASCEs
   * 
   * Invoking start to a DASU triggers the initialization of its input subscriber
   * that it is implemented by this Supervisor so, ultimately, 
   * each DASU calls Supervisor#startSubscriber.
   * This method, calls Dasu#start() just to be and independent of the
   * implementation of Dasu#start() itself.
   */
  private def startDasus(): Map[String, Set[String]] = {
    dasus.values.foreach(_.start())
    
    val fun = (m: Map[String, Set[String]], d: Dasu) => m + (d.id -> d.getInputIds())
    dasus.values.foldLeft(Map.empty[String, Set[String]])(fun)
  }
  
  /**
   * Enable or diable the auto-refresh of the outputs in the DASUs
   * 
   * @param enable if true enable the autorefresh, otherwise disable the autorefresh
   */
  def enableAutoRefreshOfOutput(enable: Boolean) {
      dasus.values.foreach(dasu => dasu.enableAutoRefreshOfOutput(enable))
  }
  
  /**
   * Start the loop:
   * - get events from the BSDB
   * - forward events to the DASUs
   * 
   * @return Success if the there were no errors starting the supervisor, 
   *         Failure otherwise 
   */
  def start(): Try[Unit] = {
    val alreadyStarted = started.getAndSet(true) 
    if (!alreadyStarted) {
      Supervisor.logger.debug("Starting Supervisor [{}]",id)
      statsLogger.start()
      hbEngine.start()
      dasus.values.foreach(dasu => dasu.enableAutoRefreshOfOutput(true))
      val inputsOfSupervisor = dasus.values.foldLeft(Set.empty[String])( (s, dasu) => s ++ dasu.getInputIds())
      inputSubscriber.startSubscriber(this, inputsOfSupervisor).flatMap(s => {
        Try{
          Supervisor.logger.debug("Supervisor [{}] started",id)
          hbEngine.updateHbState(HeartbeatStatus.RUNNING)}})
    } else {
      Supervisor.logger.warn("Supervisor [{}] already started",id)
      Failure(new Exception("Supervisor already started"))
    }
  }

  /**
   * Release all the resources
   */
  def cleanUp(): Unit = synchronized {

    val alreadyCleaned = cleanedUp.getAndSet(true)
    if (!alreadyCleaned) {
      Supervisor.logger.debug("Cleaning up supervisor [{}]", id)
      statsLogger.cleanUp()
      hbEngine.updateHbState(HeartbeatStatus.EXITING)
      Supervisor.logger.debug("Releasing DASUs running in the supervisor [{}]", id)
      dasus.values.foreach(_.cleanUp())

      Supervisor.logger.debug("Supervisor [{}]: releasing the subscriber", id)
      Try(inputSubscriber.cleanUpSubscriber())
      Supervisor.logger.debug("Supervisor [{}]: releasing the publisher", id)
      Try(outputPublisher.cleanUpPublisher())
      hbEngine.shutdown()
      Supervisor.logger.info("Supervisor [{}]: cleaned up", id)
    }
  }
  
    /** Adds a shutdown hook to cleanup resources before exiting */
  private def addsShutDownHook(): Thread = {
    val t = new Thread() {
        override def run(): Unit = {
          cleanUp()
        }
    }
    Runtime.getRuntime.addShutdownHook(t)
    t
  }
  
  /** 
   *  Notify the DASUs of new inputs received from the consumer
   *  
   *  @param iasios the inputs received
   */
  override def inputsReceived(iasios: Set[IASValue[_]]) {
    
    val receivedIds = iasios.map(i => i.id)
    statsLogger.numberOfInputsReceived(receivedIds.size)

    Supervisor.logger.debug("New inputs to send to DASUs: {}", receivedIds.mkString(","))

    // Check if the Supervisor is too slow to cope with the flow of values published in the BSDB.
    //
    // The check is done by comparing the current time with the moment the value has been
    // pushed in the BSDB.
    // Normally a new value arrives after the refresh time (plus a tolerance): the test assumes that
    // there is a problem if the the point in time when an input has been published in the kafka
    // topic is greater than 2 times the refresh rate
    //
    // The Supervisor does not start any action if a delay is detected: it only emits a waring.
    val now = System.currentTimeMillis()
    val oldIasValue = iasios.find(iasValue => now-iasValue.sentToBsdbTStamp.get()>2*refreshRate)
    if (oldIasValue.isDefined) {
      Supervisor.logger.warn("Supervisor too slow: input [{}] sent to BSDB at {} but scheduled for processing only now!",
        oldIasValue.get.id,ISO8601Helper.getTimestamp(oldIasValue.get.sentToBsdbTStamp.get()))
    }

    
    dasus.values.foreach(dasu => {
      val iasiosToSend = iasios.filter(iasio => iasiosToDasusMap(dasu.id).contains(iasio.id))

      statsLogger.numOfInputsOfDasu(dasu.id,iasiosToSend.size)
      if (iasiosToSend.nonEmpty) {
        dasu.inputsReceived(iasiosToSend)

        Supervisor.logger.debug("Inputs sent to DASU [{}] for processing: {}",
          dasu.id,
          iasiosToSend.map(_.id).mkString(","))
      } else {
        Supervisor.logger.debug("No inputs for DASU [{}]",dasu.id)
      }
    })
    statsLogger.supervisorPropagationTime(System.currentTimeMillis()-now)

  }
  
  /** 
   *  The Supervisor acts as publisher for the DASU
   *  by forwarding IASIOs to its own publisher.
   *  The initialization has already been made by the supervisor 
   *  so this method, invoke by each DASU,
   *  does nothing and always return success. 
   *  
   *  @return Success or Failure if the initialization went well 
   *          or encountered a problem  
   */
  def initializePublisher(): Try[Unit] = Success(())
  
  /**
   * The Supervisor acts as publisher for the DASU
   * by forwarding IASIOs to its own publisher.
   * The clean up will be done by by the supervisor on its own publisher 
   * so this method, invoked by each DASU, 
   * does nothing and always return success. 
   *  
   *  @return Success or Failure if the clean up went well 
   *          or encountered a problem  
   */
  def cleanUpPublisher(): Try[Unit] = Success(())
  
  /**
   * The Supervisor acts as publisher for the DASU
   * by forwarding IASIOs to its own publisher.
   * 
   * @param iasio the not IASIO to publish
   * @return a try to let the caller aware of errors publishing
   */
  def publish(iasio: IASValue[_]): Try[Unit] = outputPublisher.publish(iasio)
  
  /** 
   *  The Supervisor has its own subscriber so this initialization,
   *  invoked by each DASU, does nothing but returning Success.
   */
  def initializeSubscriber(): Try[Unit] = Success(())
  
  /** 
   *  The Supervisor has its own subscriber so this  clean up 
   *  invoked by each DASU, does nothing but returning Success. 
   */
  def cleanUpSubscriber(): Try[Unit] = Success(())
  
  /**
   * The Supervisor has its own subscriber to get events from: the list of
   * IDs to be accepted is composed of the IDs accepted by each DASUs.
   * 
   * Each DASU calls this method when ready to accept IASIOs; the Supervisor
   * - uses the passedInputs to tune its list of accepted IDs.
   * - uses the passed listener to forward to each DAUS the IASIOs it receives  
   * 
   * 
   * @param listener the listener of events
   * @param acceptedInputs the IDs of the inputs accepted by the listener
   */
  def startSubscriber(listener: InputsListener, acceptedInputs: Set[String]): Try[Unit] = {
    Success(())
  }
}

object Supervisor {
  
  /** The logger */
  val logger: Logger = IASLogger.getLogger(Supervisor.getClass)

  /** Build the usage message */
  val cmdLineSyntax: String = "Supervisor Supervisor-ID [-h|--help] [-j|-jcdb JSON-CDB-PATH] [-x|--logLevel log level]"

  /**
    * Parse the command line.
    *
    * If help is requested, prints the message and exits.
    *
    * @param args The params read from the command line
    * @return a tuple with the Id of the supervisor, the path of the cdb and the log level dao
    */
  def parseCommandLine(args: Array[String]): (Option[String],  Option[String], Option[LogLevelDao]) = {
    val options: Options = new Options
    options.addOption("h", "help",false,"Print help and exit")
    options.addOption("j", "jcdb", true, "Use the JSON Cdb at the passed path")
    options.addOption("x", "logLevel", true, "Set the log level (TRACE, DEBUG, INFO, WARN, ERROR)")

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

    val supervId = if (remaingArgs.isEmpty) None else Some(remaingArgs.get(0))

    if (!help && supervId.isEmpty) {
      println("Missing Supervisor ID")
      new HelpFormatter().printHelp(cmdLineSyntax, options)
      System.exit(-1)
    }
    if (help) {
      new HelpFormatter().printHelp(cmdLineSyntax, options)
      System.exit(0)
    }

    val ret = (supervId, jcdb, logLvl)
    Supervisor.logger.info("Params from command line: jcdb={}, logLevel={} supervisor ID={}",
      ret._2.getOrElse("Undefined"),
      ret._3.getOrElse("Undefined"),
      ret._1.getOrElse("Undefined"))
    ret

  }

  /**
   *  Application: run a Supervisor with the passed ID and
   *  kafka producer and consumer.
   *
   *  Kill to terminate.
   */
  def main(args: Array[String]): Unit = {
    val parsedArgs = parseCommandLine(args)
    require(parsedArgs._1.nonEmpty, "Missing identifier in command line")

    val supervisorId = parsedArgs._1.get

    val reader: CdbReader = {
      if (parsedArgs._2.isDefined) {
        val jsonCdbPath = parsedArgs._2.get
        Supervisor.logger.info("Using JSON CDB @ {}",jsonCdbPath)
        val cdbFiles: CdbFiles = new CdbJsonFiles(jsonCdbPath)
        new JsonReader(cdbFiles)
      } else {
        new RdbReader()
      }
    }
    
    /** 
     *  Refresh rate and tolerance: it uses the first defined ones:
     *  1 java properties,
     *  2 CDB
     *  3 default
     */
    val (refreshRate, tolerance,kafkaBrokers) = {
      val RefreshTimeIntervalSeconds = Integer.getInteger(AutoSendPropName,AutoSendTimeIntervalDefault)
      val ToleranceSeconds = Integer.getInteger(TolerancePropName,ToleranceDefault)
      
      val iasDaoOpt = reader.getIas

      val fromCdb = if (iasDaoOpt.isPresent) {
        logger.debug("IAS configuration read from CDB")
        (iasDaoOpt.get.getRefreshRate,iasDaoOpt.get.getTolerance,Option(iasDaoOpt.get.getBsdbUrl))
      } else {
        logger.warn("IAS not found in CDB: using default values for auto send time interval ({}) and tolerance ({})",
          AutoSendTimeIntervalDefault,ToleranceDefault)
        (AutoSendTimeIntervalDefault,ToleranceDefault,None)
      }
      logger.debug("Using autosend time={}, HB frequency={}, Kafka brokers={}",fromCdb._1,fromCdb._2,fromCdb._3)
      
      (Integer.getInteger(AutoSendPropName,fromCdb._1),
      Integer.getInteger(TolerancePropName,fromCdb._2),
      fromCdb._3)
    }
    
    val outputPublisher: OutputPublisher = KafkaPublisher(supervisorId,None,kafkaBrokers,System.getProperties)
    val inputsProvider: InputSubscriber = KafkaSubscriber(supervisorId,None,kafkaBrokers,System.getProperties)
    
    // The identifier of the supervisor
    val identifier = new Identifier(supervisorId, IdentifierType.SUPERVISOR, None)
    
    val factory = (dd: DasuDao, i: Identifier, op: OutputPublisher, id: InputSubscriber) => 
      DasuImpl(dd,i,op,id,refreshRate,tolerance)
      
    val hbProducer: HbProducer = {
      val kafkaServers = System.getProperties.getProperty(KafkaHelper.BROKERS_PROPNAME,KafkaHelper.DEFAULT_BOOTSTRAP_BROKERS)
      
      new HbKafkaProducer(supervisorId+"HBSender",kafkaServers,new HbJsonSerializer())
    }
      
    // Build the supervisor
    val supervisor = new Supervisor(identifier,outputPublisher,inputsProvider,hbProducer,reader,factory,parsedArgs._3)
    
    val started = supervisor.start()
    
    // Release CDB resources
    reader.shutdown()
    
    started match {
      case Success(_) => val latch = new CountDownLatch(1); latch.await();
      case Failure(ex) => System.err.println("Error starting the supervisor: "+ex.getMessage)
    }
  }
  
  /**
   * The name of the property to override the auto send time interval
   * read from the CDB
   */
  val AutoSendPropName = "ias.supervisor.autosend.time"
  
  /**
   * The default time interval to automatically send the last calculated output
   * in seconds
   */
  val AutoSendTimeIntervalDefault = 5
  
  /**
   * The name of the property to override the tolerance
   * read from the CDB
   */
  val TolerancePropName = "ias.supervisor.autosend.tolerance"
  
  /**
   * The default tolarance in seconds: it is the time added to the auto-refresh before
   * invalidate an input
   */
  val ToleranceDefault = 1
  

}
