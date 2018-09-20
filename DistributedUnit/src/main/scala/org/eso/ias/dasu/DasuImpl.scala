package org.eso.ias.dasu

import scala.util.Try
import java.util.concurrent.ScheduledFuture

import org.eso.ias.dasu.publisher.OutputPublisher
import org.eso.ias.dasu.subscriber.InputSubscriber
import org.eso.ias.cdb.CdbReader
import org.eso.ias.types.Identifier
import org.eso.ias.types.IdentifierType

import scala.collection.mutable.{Map => MutableMap}
import org.eso.ias.asce.ComputingElement

import scala.util.Properties
import org.eso.ias.cdb.pojos.AsceDao
import org.eso.ias.types.IASValue
import org.eso.ias.logging.IASLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Properties

import scala.util.Failure
import scala.util.Success
import scala.collection.JavaConverters
import org.eso.ias.types.InOut
import org.eso.ias.asce.AsceStates
import org.eso.ias.dasu.executorthread.ScheduledExecutor
import java.util.concurrent.TimeUnit

import org.eso.ias.cdb.pojos.DasuDao
import org.eso.ias.types.Validity
import org.eso.ias.types.IasValidity
import java.util.HashMap
import java.util.Collections

import org.eso.ias.cdb.topology.DasuTopology

/**
 * The implementation of the DASU.
 * 
 * A DASU normally has 2 threads running:
 * - the automatic sending of the output when no new input arrives 
 *   or the output did not change
 * - a thread for the throttling to avoid that processing too many 
 *   inputs takes 100% CPU
 * 
 * @param the identifier of the DASU
 * @param refreshRate the refresh rate (seconds) to automatically resend the last calculated 
 *                    output even if it did not change
 * @param outputPublisher the publisher to send the output
 * @param inputSubscriber the subscriber getting events to be processed 
 * @param cdbReader the CDB reader to get the configuration of the DASU from the CDB
 * @param autoSendTimeInterval refresh rate (sec) to automatically send the output  
 *                             even if it did not change
 * @param tolerance the max delay (secs) before declaring an input unreliable
 */
class DasuImpl (
    dasuIdentifier: Identifier,
    dasuDao: DasuDao,
    private val outputPublisher: OutputPublisher,
    private val inputSubscriber: InputSubscriber,
    autoSendTimeInterval: Integer,
    tolerance: Integer)
    extends Dasu(dasuIdentifier,autoSendTimeInterval,tolerance) {
  require(Option(dasuIdentifier).isDefined,"Invalid Identifier")
  require(Option(dasuDao).isDefined,"Invalid DASU CDB configuration")
  require(dasuIdentifier.idType==IdentifierType.DASU,"Invalid identifier type for DASU")
  require(Option(outputPublisher).isDefined,"Invalid output publisher")
  require(Option(inputSubscriber).isDefined,"Invalid input subscriber")
  require(Option(autoSendTimeInterval).isDefined && autoSendTimeInterval>0,"Invalid auto-send time interval")
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  logger.info("Building DASU [{}] with running id {}",id,dasuIdentifier.fullRunningID)
  
  /**
   * The configuration of the ASCEs that run in the DASU
   */
  val asceDaos = JavaConverters.asScalaSet(dasuDao.getAsces).toList
  
  // Are there ASCEs assigned to this DASU?
  require(dasuDao.getAsces.size()>0,"No ASCE found for DASU "+id)
  logger.info("DASU [{}]: will load and run {} ASCEs",id,""+dasuDao.getAsces.size())
  
  // The ID of the output generated by the DASU
  val dasuOutputId = dasuDao.getOutput().getId()
  logger.info("Output of DASU [{}]: [{}]",id,dasuOutputId)
  
  // Build the topology
  val dasuTopology: DasuTopology = new DasuTopology(
      id,
      dasuOutputId,
      asceDaos)
  logger.debug("DASU [{}]: topology built",id)
  logger.debug(dasuTopology.toString())
  
  // Instantiate the ASCEs
  val asces: Map[String, ComputingElement[_]] = {
    val validityThreshold = autoSendTimeInterval + tolerance
    val addToMapFunc = (m: Map[String, ComputingElement[_]], asce: AsceDao) => {
      val propsForAsce = new Properties()
      asce.getProps.forEach(p => propsForAsce.setProperty(p.getName, p.getValue))
      m + (asce.getId -> ComputingElement(asce,dasuIdentifier,validityThreshold,propsForAsce))
    }
    asceDaos.foldLeft(Map[String,ComputingElement[_]]())(addToMapFunc)
  }
  logger.info("DASU [{}] ASCEs loaded: [{}]",id, asces.keys.mkString(", "))
    
  // Activate the ASCEs
  val ascesInitedOk=asces.valuesIterator.map(asce => asce.initialize()).forall(s => s==AsceStates.InputsUndefined)
  assert(ascesInitedOk,"At least one ASCE did not pass the initialization phase")
  logger.info("DASU [{}]: ASCEs initialized",id)
  
  // The ASCE that produces the output of the DASU
  val idOfAsceThatProducesTheOutput = {
    val idOpt=dasuTopology.asceProducingOutput(dasuOutputId)
    require(idOpt.isDefined && !idOpt.isEmpty,"ASCE producing output not found")
    idOpt.get
  }
  logger.info("The output [{}] of the DASU [{}] is produced by [{}] ASCE",dasuOutputId,id,idOfAsceThatProducesTheOutput)
  
  /** The ASCE that produces the output */
  val asceThatProducesTheOutput = asces(idOfAsceThatProducesTheOutput)
  
  /**
   * Values that have been received in input from plugins or other DASUs (BSDB)
   * and not yet processed by the ASCEs
   * 
   * TODO: protect the map against access from multiple threads
   */
  val notYetProcessedInputs: java.util.Map[String,IASValue[_]] = Collections.synchronizedMap(new HashMap[String,IASValue[_]]())
  
  /**
   * The fullRuning Ids of the received inputs
   */
  val fullRunningIdsOfInputs: java.util.Map[String,String] = Collections.synchronizedMap(new HashMap[String,String]())
  
  /** 
   *  The last calculated output by ASCEs
   */
  val lastCalculatedOutput = new AtomicReference[Option[IASValue[_]]](None)
  
  /** 
   *  The last sent output and validity
   *  this is sent again if no new inputs arrives and the autoSendTimerTask is running
   *  and the validity did not change since the last sending
   */
  val lastSentOutputAndValidity = new AtomicReference[Option[Tuple2[InOut[_], IasValidity]]](None)
  
  /** 
   *  The point in time when the output has been sent to the
   *  BSDB either due to new inputs or auto-refresh
   *  
   *  It is better to initialize at the actual timestamp
   *  for the calculation of the throttling in inputsReceived 
   */
  val lastSentTime = new AtomicLong(System.currentTimeMillis())
  
  /** The thread executor service */
  val scheduledExecutor = new ScheduledExecutor(id)
  
  /** True if the automatic re-sending of the output has been enabled */
  val timelyRefreshing = new AtomicBoolean(false)
  
  /** True if the DASU has been started */
  val started = new AtomicBoolean(false)
  
  /** 
   *  The task to delay the generation the output 
   *  when new inputs must be processed 
   */
  val throttlingTask = new AtomicReference[Option[ScheduledFuture[_]]](None)
  
  /**
   * The Runnable to update the output when it is delayed by the throttling
   */
  val delayedUpdateTask = new Runnable {
      override def run() = {
        logger.info("DASU [{}] running the throttling task",id)
        updateAndPublishOutput()
      }
  }
  
  /**
   * The point in time of the last time when 
   * the output has been generated and published
   */
  val lastUpdateTime = new AtomicLong(0)
  
  /** 
   *  The task that timely send the last computed output
   *  when no new inputs arrived: initially disabled, must be enabled 
   *  invoking enableAutoRefreshOfOutput.
   *  
   *  If a new output is generated before this time interval elapses,
   *  this task is delayed of the duration of autoSendTimeInterval msecs.  
   */
  val autoSendTimerTask: AtomicReference[ScheduledFuture[_]] = new AtomicReference[ScheduledFuture[_]]()
  
  /** Closed: the DASU does not process inputs */
  val closed = new AtomicBoolean(false)
  
  logger.debug("DASU [{}]: initializing the publisher", id)
  
  val outputPublisherInitialized = outputPublisher.initializePublisher()
  outputPublisherInitialized match {
    case Failure(f) => logger.error("DASU [{}] failed to initialize the publisher: NO output will be produced", id,f)
    case Success(s) => logger.info("DASU [{}] publisher successfully initialized",id)
  }
  logger.debug("DASU [{}] initializing the subscriber", id)
  
  val inputSubscriberInitialized = inputSubscriber.initializeSubscriber()
  inputSubscriberInitialized match {
    case Failure(f) => logger.error("DASU [{}] failed to initialize the subscriber: NO input will be processed", id,f)
    case Success(s) => logger.info("DASU [{}] subscriber successfully initialized",id)
  }
  
  /** The generator of statistics */
  val statsCollector = new StatsCollector(id)
  
  logger.info("DASU [{}] built", id)
  
  /**
   * Reschedule the auto send time interval if the output is generated before
   * the autoSendTimeInterval elapses.
   */
  private def rescheduleAutoSendTimeInterval() = synchronized {
    val autoSendIsEnabled = timelyRefreshing.get()
    if (autoSendIsEnabled) {
      
      val lastTimerScheduledFeature: Option[ScheduledFuture[_]] = Option(autoSendTimerTask.get)
      lastTimerScheduledFeature.foreach(_.cancel(false))
      val runnable = new Runnable {
            /** The task to refresh the output when no new inputs have been received */
            override def run() = publishOutput(calcOutputValidity())
        }
        val newTask=scheduledExecutor.scheduleWithFixedDelay(
            runnable, 
            autoSendTimeInterval.toLong, 
            autoSendTimeInterval.toLong, 
            TimeUnit.SECONDS)
        autoSendTimerTask.set(newTask)
    }
  }
  
  /**
   * Propagates the inputs received from the BSDB to each of the ASCEs
   * in the DASU generating the output of the entire DASU.
   * 
   * This method runs after the throttling time interval elapses.
   * All the iasios collected in the time interval will be passed to the first level of the ASCEs
   * and up till the last ASCE that generates the output of the DASU itself.
   * Each ASCE runs the TF and produces another output to be propagated to the next level.
   * The last level is the one that produces the output of the DASU
   * to be sent to the BSDB.
   * 
   * @param iasios the IASIOs received from the BDSB in the last time interval
   * @return the IASIO to send back to the BSDB
   */
  private def propagateIasios(iasios: Set[IASValue[_]]): Option[IASValue[_]] = {
      
      // Updates one ASCE i.e. runs its TF passing the inputs
      // and returns the output of the ASCE
      def updateOneAsce(asceId: String, asceInputs: Set[IASValue[_]]): Option[IASValue[_]] = {
        
        
        val requiredInputs = dasuTopology.inputsOfAsce(asceId)
        val inputs: Set[IASValue[_]] = asceInputs.filter( p => requiredInputs.contains(p.id))
        
        if (!inputs.isEmpty) {
          // Get the ASCE with the given ID 
          val asceOpt: Option[ComputingElement[_]] = asces.get(asceId)
          assert(asceOpt.isDefined,"ASCE "+asceId+" NOT found!")
          
          asceOpt.flatMap( aaaa => {
            aaaa.update(inputs).asInstanceOf[(Option[InOut[_]], AsceStates.State)]
              ._1.map(inOut => inOut.toIASValue())
          })
          
        } else {
          None
        }
        
      }
      
      // Run the TFs of all the ASCEs in one level
      // Returns the inputs plus all the outputs produced by the ACSEs
      def updateOneLevel(asces: Set[String], levelInputs: Set[IASValue[_]]): Set[IASValue[_]] = {
        
        asces.foldLeft(levelInputs) ( 
          (s: Set[IASValue[_]], id: String ) => {
            updateOneAsce(id, levelInputs).map( v => s+v).getOrElse(s)
            })
      }
      
      if (!closed.get) {
        val outputs = dasuTopology.levels.foldLeft(iasios){ (s: Set[IASValue[_]], ids: Set[String]) => s ++ updateOneLevel(ids, s) }
        outputs.find(_.id==dasuOutputId).map(_.updateDasuProdTime(System.currentTimeMillis()))
      } else {
        None
      }
  }
  
  /**
   * New inputs have been received from the BSDB.
   * 
   * This method is not invoked while automatically re-sending the last computed value.
   * Such value is in fact stored into lastSentOutput and does not trigger
   * a recalculation by the DASU. 
   * 
   * @param iasios the inputs received
   * @see InputsListener
   */
  override def inputsReceived(iasios: Set[IASValue[_]]) = synchronized {
    assert(iasios.size>0)
        
    // Merge the inputs with the buffered ones to keep only the last updated values
    iasios.filter( p => getInputIds().contains(p.id)).foreach(iasio => {
      fullRunningIdsOfInputs.put(iasio.id, iasio.fullRunningId)
      notYetProcessedInputs.put(iasio.id,iasio)
    })
    
    // The new output must be immediately recalculated and sent unless 
    // * the throttling is already in place (i.e. calculation already delayed)
    // * the last value has been updated shortly before 
    //   (i.e. the calculation must be delayed and the throttling activated)
    val now = System.currentTimeMillis()
    val afterEndOfThrottling = now>lastUpdateTime.get+throttling
    val beforeEndOfThrottling = !afterEndOfThrottling
    val throttlingIsScheduled = throttlingTask.get().isDefined && !throttlingTask.get().get.isDone()
    
    (throttlingIsScheduled, beforeEndOfThrottling) match {
      case (true, true)  => {} // delayed: do nothing
      case (_ , false)   => updateAndPublishOutput() // send immediately
      case (false, true) => // Activate throttling
        val delay =  throttling+now-lastSentTime.get
        val schedFeature = scheduledExecutor.schedule(delayedUpdateTask,delay,TimeUnit.MILLISECONDS)
        throttlingTask.set(Some(schedFeature))
    }
  }
  
  /**
   * Publish the last calculated output to the BSDB with the given validity.
   *
   * This method can be called by the automatic sending and by a change in the output
   * and blindly publish the last calculated output.
   * 
   * @param actualValidity the validity
   */
  private def publishOutput(actualValidity: Validity) {
    
    val currentOutput = asceThatProducesTheOutput.output
    
    currentOutput.value.foreach({ v =>
      lastSentTime.set(System.currentTimeMillis())
      val iasioToSend = currentOutput.updateSentToBsdbTStamp(lastSentTime.get)
      val iasValueToSend = iasioToSend.toIASValue().updateFullIdsOfDependents(fullRunningIdsOfInputs.values).updateValidity(actualValidity)
      
      lastSentOutputAndValidity.set(Some(iasioToSend,actualValidity))
      outputPublisher.publish(iasValueToSend)
    })
  }
  
  /**
   * Calculate the validity of the output depending on
   * the arrival time
   */
  def calcOutputValidity(): Validity = {
    val lastOutput= {
      val temp = lastCalculatedOutput.get
      require(temp.isDefined,"Cannot calc the validity if there is no output")
      temp.get
    }
    
    assert(
            lastOutput.dasuProductionTStamp.isPresent() && !lastOutput.pluginProductionTStamp.isPresent()||
            !lastOutput.dasuProductionTStamp.isPresent() && lastOutput.pluginProductionTStamp.isPresent(),
            "Invariant violation for IasValue "+lastOutput.toString())
    
    val thresholdTStamp = System.currentTimeMillis() - autoSendTimeIntervalMillis - toleranceMillis
    
    val iasioTstamp = {
      if (lastOutput.dasuProductionTStamp.isPresent()) {
        lastOutput.dasuProductionTStamp.get()
      } else {
        lastOutput.pluginProductionTStamp.get()
      }
    }

          
    val validityByTime = if (iasioTstamp<thresholdTStamp) {
        Validity(IasValidity.UNRELIABLE)
    } else {
        Validity(IasValidity.RELIABLE)
    }
    
    // The validity of the output calculated by the ASCE 
    val validityByAsce = Validity(lastOutput.iasValidity)
    
    Validity.minValidity(Set(validityByTime,validityByAsce))
    
  }
  
  /**
   * Update the output with the set of inputs collected so far
   * and send the output to the BSDB.
   * 
   * The method delegates the calculation to propapgateIasios
   */
  private def updateAndPublishOutput() = {
    // Let the ASCEs produce the new output
    val before = System.currentTimeMillis()
    val oldCalculatedOutput = lastCalculatedOutput.get
    
    // Gets the input from the synchronized java map into a scala Set
    val inputsFromMap = JavaConverters.collectionAsScalaIterable(notYetProcessedInputs.values).toSet
    
    lastCalculatedOutput.set(propagateIasios(inputsFromMap))
    
    val after = System.currentTimeMillis()
    lastUpdateTime.set(after)
    notYetProcessedInputs.clear()
    
    // Publish the value only if the output has been produced
    lastCalculatedOutput.get.foreach( output => {
      
      logger.debug("DASU [{}] calculated output [{}] with validity {}",
        id,
        output.id,
        output.iasValidity)
      
      // The validity of the output
      val outputValidity = calcOutputValidity()
      
      // Do we really need to send the output immediately?
      // If it did not change then it will be sent by the auto-send
      val prevSentOutputAndValidity = lastSentOutputAndValidity.get()
      
      prevSentOutputAndValidity.foreach(tuple => {
        assert(tuple._1.value.isDefined)
      })
      if (prevSentOutputAndValidity.isEmpty || 
          prevSentOutputAndValidity.get._2!=outputValidity.iasValidity ||
          prevSentOutputAndValidity.get._1.value.get!= output.value ||
          prevSentOutputAndValidity.get._1.mode!=output.mode) {
        publishOutput(outputValidity)
        rescheduleAutoSendTimeInterval()
        statsCollector.updateStats(after-before)
      }
    })
    
  }
  
  /**
   * Check if the new output must be sent to the BSDB
   * i.e if its mode or value or validity has changed
   * 
   * @param oldOutput the last output sent to the BSDB
   * @param oldValidity the last validity sent to the BSDB
   * @param newOutput the new output
   * @param newValidity the new validity
   * @return true if the new output changed from the last sent output 
   *              and must be sent to the BSDB and false otherwise
   * 
   */
  def mustSendOutput(
      oldOutput: InOut[_], 
      oldValidity: Validity,
      newOutput: InOut[_],
      newValidity: Validity): Boolean = {
    oldValidity!=newValidity ||
    oldOutput.value!=newOutput.value ||
    oldOutput.mode!=newOutput.mode ||
    oldOutput.props!=newOutput.props ||
    oldOutput.idsOfDependants!=newOutput.idsOfDependants
  }
  
  /** 
   *  Start getting events from the inputs subscriber
   *  to produce the output
   */
  def start(): Try[Unit] = {
    val alreadyStarted = started.getAndSet(true)
    if (!alreadyStarted) {
      logger.debug("DASU [{}] starting", id)
      inputSubscriberInitialized.map(x => inputSubscriber.startSubscriber(this, dasuTopology.dasuInputs))
    } else {
      new Failure(new Exception("DASU already started"))
    }
  }
  
  /**
   * Enable/disable the automatic update of the output
   * in case no new inputs arrive.
   * 
   * Most likely, the value of the output remains the same 
   * while the validity could change.
   */
  def enableAutoRefreshOfOutput(enable: Boolean) = synchronized {
    val alreadyEnabled = timelyRefreshing.getAndSet(enable)
    (enable, alreadyEnabled) match {
      case (true, true) => 
        logger.warn("DASU [{}]: automatic refresh of output already ative",id)
      case (true, false) => 
        rescheduleAutoSendTimeInterval()
        logger.info("DASU [{}]: automatic send of output enabled at intervals of {} secs (aprox)",id, autoSendTimeInterval.toString())
      case (false , _) => 
        val oldTask: Option[ScheduledFuture[_]] = Option(autoSendTimerTask.getAndSet(null))
        oldTask.foreach(task => {
          task.cancel(false)
          logger.info("DASU [{}]: automatic send of output disabled",id)  
        })
    }
  }
  
  /**
   * Release all the resources before exiting
   */
  def cleanUp() {
    val alreadyClosed = closed.getAndSet(true)
    if (alreadyClosed) {
      logger.warn("DASU [{}]: already cleaned up!", id)
    } else {
      logger.info("DASU [{}]: releasing resources", id)
      logger.debug("DASU [{}]: stopping the auto-refresh of the output", id)
      Try(enableAutoRefreshOfOutput(false))
      logger.debug("DASU [{}]: releasing the subscriber", id)
      Try(inputSubscriber.cleanUpSubscriber())
      logger.debug("DASU [{}]: releasing the publisher", id)
      Try(outputPublisher.cleanUpPublisher())
      logger.info("DASU [{}]: cleaned up",id)  
    }
  }
  
  /** @return the IDs of the inputs of the DASU */
  def getInputIds(): Set[String] = dasuTopology.dasuInputs
  
  /** @return the IDs of the ASCEs running in the DASU  */
  def getAsceIds(): Set[String] = asces.keys.toSet
  
  /** @return the IDs of the of the inputs of the ASCE with the given ID */ 
  def getInputsOfAsce(id: String) = dasuTopology.inputsOfAsce(id)
}

object DasuImpl {
  
  /**
   * Factory method to build a DasuImpl
   * 
   * @param dasuDao: the configuration of the DASU from the CDB
   * @param supervidentifier: the identifier of the supervisor that runs the dasu
   * @param outputPublisher: the producer to send outputs of DASUs to the BSDB
   * @param inputSubscriber: the consumer to get values from the BSDB
   * @param cdbReader: the CDB reader
   * @param autoSendTimeInterval refresh rate (msec) to automatically send the output  
   *                             when no new inputs have been received
   * @param tolerance the max delay (secs) before declaring an input unreliable
   */
  def apply(
    dasuDao: DasuDao, 
    supervidentifier: Identifier,
    outputPublisher: OutputPublisher,
    inputSubscriber: InputSubscriber,
    autoSendTimeInterval: Integer,
    tolerance: Integer): DasuImpl = {
    
    
    require(Option(dasuDao).isDefined)
    require(Option(supervidentifier).isDefined)
    require(Option(outputPublisher).isDefined)
    require(Option(inputSubscriber).isDefined)
    require(Option(autoSendTimeInterval).isDefined)
    require(Option(tolerance).isDefined)
    
    val dasuId = dasuDao.getId
    
    val dasuIdentifier = new Identifier(dasuId,IdentifierType.DASU,supervidentifier)
    
    new DasuImpl(
        dasuIdentifier,
        dasuDao,
        outputPublisher,
        inputSubscriber,
        autoSendTimeInterval,
        tolerance)
  }
}
