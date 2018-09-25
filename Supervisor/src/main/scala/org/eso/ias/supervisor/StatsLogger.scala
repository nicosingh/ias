package org.eso.ias.supervisor

import java.util
import java.util.Collections
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import org.eso.ias.dasu.executorthread.ScheduledExecutor
import org.eso.ias.logging.IASLogger

import scala.collection.JavaConverters
import scala.util.Try

/**
  * Stats logger produces statistics for the Supervisor
  *
  * @param dasusIds The IDs of the DAUS deployed in the Supervisur
  */
class StatsLogger (val dasusIds: Set[String] ) {
  require(Option(dasusIds).isDefined && !dasusIds.isEmpty,"Invalid undefined or empty list of DASU IDs")

  /** Total number of inputs forwarded to the DASU to be processed since when the Supervisor started */
  val totInputsProcessed = new AtomicLong(0)

  /** Total number of inputs forwarded to the DASU to be processed in the last time interval */
  val inputsProcessed = new AtomicLong(0)


  /**
    * The map to associate the execution time and processed inputs
    * to each DASU.
    *
    * The key is the ID of the DASU.
    * The value is a couple with
    * - the number of inputs processed
    * - the number of updates invoked (to calculate thefrequency)
    */
  val dasusInputsAndFrequency: util.Map[String, (Long, Long)] = new ConcurrentHashMap[String,(Long,Long)]()
  dasusIds.foreach(id => dasusInputsAndFrequency.put(id,(0,0)))

  /** The scheduler to log statistics */
  private val scheduer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

  /** Th efuture to cancel logging of statistics */
  private var future = new AtomicReference[ScheduledFuture[_]]()

  /** Add the number of inputs received to the accumulator */
  def numberOfInputsReceived(numberOfInputs: Int): Unit =  {
    totInputsProcessed.addAndGet(numberOfInputs)
    if (totInputsProcessed.get()<0) totInputsProcessed.set(0)
    inputsProcessed.addAndGet(numberOfInputs)
  }

  /**
    * Updated the execution time and number of inputs processed
    * ot the DASU with the passed id
    *
    * @param id the ID of the daus
    * @param inputsProcessed the number of inputs processed
    */
  def numOfInputsOfDasu(id: String, inputsProcessed: Int): Unit = {
    require(Option(id).isDefined && dasusInputsAndFrequency.containsKey(id),"Unrecognized DASU id "+id)

    val actual: (Long,Long) = dasusInputsAndFrequency.get(id)
    val newNumOfInputs = actual._1+inputsProcessed
    val iterations: Long  = actual._2+1
    dasusInputsAndFrequency.put(id, (newNumOfInputs, iterations))
  }

  /** Start logging statistics */
  def start() = synchronized {
    Option(future.get()) match {
      case Some(f) => StatsLogger.logger.warn("StatsCollector already started")
      case None => future.set(scheduer.scheduleAtFixedRate( () =>
        logStats(),
        StatsLogger.StatisticsTimeInterval,
        StatsLogger.StatisticsTimeInterval,
        TimeUnit.MINUTES))
    }
    StatsLogger.logger.info("StatsLogger started: will publish stats every "+StatsLogger.StatisticsTimeInterval+" minutes")
  }

  /** Stop loging statistics */
  def cleanUp() = synchronized {
    Option(future.getAndSet(null)).foreach(f => {
      f.cancel(false)
      StatsLogger.logger.info("StatsLogger closed")
    })

  }

  /** Emit the logs with the statistics and reset the counters */
  def logStats(): Unit = {
    val totProcessedInputs = totInputsProcessed.getAndSet(0)

    val message= StringBuilder.newBuilder
    message.append("Stats: ")
    message.append(totProcessedInputs)
    message.append(" IASIOs processed so far ")
    message.append(" (")
    message.append(totProcessedInputs/StatsLogger.StatisticsTimeInterval)
    message.append("/min); input in the last interval ")
    message.append(inputsProcessed.get)
    message.append("; statistics collected from DASUS: [")
    dasusInputsAndFrequency.keySet().forEach(id => {
      val dasuStats = dasusInputsAndFrequency.get(id)
      message.append("DASU ")
      message.append(id)
      message.append(" #inputs=")
      message.append(dasuStats._1)
      message.append(' ')
      message.append(dasuStats._2/StatsLogger.StatisticsTimeInterval)
      message.append("/min")
      message.append("] ")
    })
    StatsLogger.logger.info(message.toString())
    // Reset counters
    inputsProcessed.set(0)
    dasusInputsAndFrequency.keySet().forEach(id => dasusInputsAndFrequency.put(id, (0,0)))
  }
}

object StatsLogger {

  /** The logger */
  val logger = IASLogger.getLogger(StatsLogger.getClass)

  /** The time interval to log statistics (minutes) */
  val DefaultStatisticsTimeInterval = 10

  /** The name of the java property to set the statistics generation time interval */
  val StatisticsTimeIntervalPropName = "ias.supervisor.stats.timeinterval"

  /** The actual time interval to log statistics (minutes) */
  val StatisticsTimeInterval: Int = {
    val prop = Option(System.getProperties.getProperty(StatisticsTimeIntervalPropName))
    prop.map(s => Try(s.toInt).getOrElse(DefaultStatisticsTimeInterval)).getOrElse(DefaultStatisticsTimeInterval).abs
  }
}