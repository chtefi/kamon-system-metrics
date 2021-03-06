/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.system

import java.io.File
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.MILLISECONDS

import com.typesafe.config.Config
import kamon.sigar.SigarProvisioner
import kamon.system.custom.CustomMetricsUpdater
import kamon.system.host.SigarMetricsUpdater
import kamon.system.jvm.JmxMetricsUpdater
import kamon.{Kamon, OnReconfigureHook}
import org.slf4j.{Logger, LoggerFactory}

object SystemMetrics {

  val FilterName = "system-metric"

  val logger: Logger = LoggerFactory.getLogger("kamon.metrics.SystemMetrics")

  var sigarFolder:String = _
  var sigarRefreshInterval:Duration = _
  var jmxRefreshInterval:Duration = _
  var sigarEnabled:Boolean = _
  var jmxEnabled: Boolean =_
  var hiccupMonitorEnabled: Boolean =_
  var contextSwitchesRefreshInterval:Duration = _
  var hiccupSampleIntervalResolution:Duration = _
  var scheduledCollections: Seq[ScheduledFuture[_]] = _

  logger.info(s"Starting the Kamon(SystemMetrics) module")

  loadConfiguration(Kamon.config())

  Kamon.onReconfigure(new OnReconfigureHook {
    override def onReconfigure(newConfig: Config): Unit =
      SystemMetrics.loadConfiguration(newConfig)
  })

  private def loadConfiguration(config: Config): Unit = synchronized {
    val systemMetricsConfig = config.getConfig("kamon.system-metrics")
    sigarFolder = systemMetricsConfig.getString("host.sigar-native-folder")
    sigarRefreshInterval = systemMetricsConfig.getDuration("host.refresh-interval")
    jmxRefreshInterval = systemMetricsConfig.getDuration("jvm.refresh-interval")
    sigarEnabled = systemMetricsConfig.getBoolean("host.enabled")
    jmxEnabled = systemMetricsConfig.getBoolean("jvm.enabled")
    contextSwitchesRefreshInterval = systemMetricsConfig.getDuration("host.context-switches-refresh-interval")
    hiccupMonitorEnabled = systemMetricsConfig.getBoolean("jvm.hiccup-monitor.enabled")
    hiccupSampleIntervalResolution = systemMetricsConfig.getDuration("jvm.hiccup-monitor.sample-interval-resolution")
  }

  def startCollecting(): Unit = synchronized {
    scheduledCollections = Seq(
      // OS Metrics collected with Sigar
      if (SystemMetrics.sigarEnabled) Some(collectSigarMetrics) else None,

      // If we are in Linux, add ContextSwitchesMetrics as well.
      if (SystemMetrics.sigarEnabled && isLinux) Some(collectCustomMetrics) else None,

      // JMX Metrics
      if (SystemMetrics.jmxEnabled) Some(collectJMXMetrics) else None
    ).flatten
  }

  def stopCollecting(): Unit = synchronized {
    scheduledCollections.foreach(_.cancel(false))
    scheduledCollections = Seq.empty
  }

  private def collectSigarMetrics: ScheduledFuture[_] = {
    SigarProvisioner.provision(new File(SystemMetrics.sigarFolder))
    val sigarMetricsUpdater = new SigarMetricsUpdater(SystemMetrics.logger)
    val refreshInterval = SystemMetrics.sigarRefreshInterval.toMillis

    Kamon.scheduler().scheduleAtFixedRate(sigarMetricsUpdater, 0L, refreshInterval, MILLISECONDS)
  }

  private def collectJMXMetrics: ScheduledFuture[_] = {
    val jmxMetricsUpdater = new JmxMetricsUpdater()
    val refreshInterval = SystemMetrics.jmxRefreshInterval.toMillis

    Kamon.scheduler().scheduleAtFixedRate(jmxMetricsUpdater, 0L, refreshInterval, MILLISECONDS)
  }

  private def collectCustomMetrics: ScheduledFuture[_] = {
    val customMetricsUpdater = new CustomMetricsUpdater()
    val refreshInterval = SystemMetrics.contextSwitchesRefreshInterval.toMillis

    Kamon.scheduler().scheduleAtFixedRate(customMetricsUpdater, 0L, refreshInterval, MILLISECONDS)
  }

  def isLinux: Boolean =
    System.getProperty("os.name").indexOf("Linux") != -1
}


