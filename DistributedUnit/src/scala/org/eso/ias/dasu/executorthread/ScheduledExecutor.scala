package org.eso.ias.dasu.executorthread

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.lang.Runtime
import scala.util.control.NonFatal

/** The scheduled executor for the transfer functions of the DASU. 
 *  
 *  @constructor Builds the scheduled executor
 *  @param dasuID the identifier of the DASU
 *  */
class ScheduledExecutor(dasuId: String) extends ScheduledThreadPoolExecutor(
    ScheduledExecutor.getCoreSize(),
    new DasuThreadFactory(dasuId)) {
}

object ScheduledExecutor {
  
  /**
   * The name of the property to set the number of cores in the thread executor
   */
  val CoreSizePropName = "ias.prototype.dasu.threadpoolcoresize"
  
  /**
   * Get the size of the core from the java property or from
   * the number of available CPUs
   */
  def getCoreSize(): Int = {
    // Check if the java property has been set in the environment
    import scala.sys.SystemProperties
    val props = new SystemProperties()
    val coreFromProps: String = props.getOrElse(CoreSizePropName,{Runtime.getRuntime().availableProcessors().toString})
    try {
      coreFromProps.toInt
    } catch {
       case NonFatal(e) => 
         println("Malformed property "+CoreSizePropName+": "+coreFromProps)
         Runtime.getRuntime().availableProcessors()
    }
  }
}