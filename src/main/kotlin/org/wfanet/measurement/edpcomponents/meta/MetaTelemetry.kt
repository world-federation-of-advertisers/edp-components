/*
 * Copyright 2026 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wfanet.measurement.edpcomponents.meta

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OpenTelemetry SDK for the Meta Cloud Functions, configured from the standard `OTEL_*` environment
 * variables. Every exporter defaults to `none`, so nothing is exported unless the deployment opts
 * in, for example with `OTEL_METRICS_EXPORTER=google_cloud_monitoring`.
 */
internal object MetaTelemetry {
  private val sdk: OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
      .addPropertiesSupplier {
        mapOf(
          "otel.metrics.exporter" to "none",
          "otel.traces.exporter" to "none",
          "otel.logs.exporter" to "none",
        )
      }
      .build()
      .openTelemetrySdk

  /** Meter for the Meta Cloud Functions' instruments. */
  val meter: Meter = sdk.getMeter(INSTRUMENTATION_SCOPE)

  /**
   * Exports pending metrics, returning whether that succeeded. Cloud Functions may freeze an idle
   * instance, so a handler calls this before it returns. A failure is logged rather than thrown, so
   * it never replaces the handler's response.
   *
   * Cloud Monitoring accepts one point per time series every 5 seconds, so during a burst it may
   * reject a flush that closely follows another. Counters are cumulative, so a later successful
   * export, at the next request or when the instance shuts down, carries the full total.
   */
  fun flush(): Boolean =
    try {
      val result =
        sdk.sdkMeterProvider.forceFlush().join(FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
      if (!result.isSuccess) {
        logger.warning("Metric export failed or did not complete within $FLUSH_TIMEOUT")
      }
      result.isSuccess
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Metric export failed", e)
      false
    }

  private const val INSTRUMENTATION_SCOPE = "org.wfanet.measurement.edpcomponents.meta"
  // Short, because the flush runs inside the function's request timeout: a slow export must not
  // turn a meaningful 429 or 502 into a timeout for the caller.
  private val FLUSH_TIMEOUT: Duration = Duration.ofSeconds(2)
  private val logger: Logger = Logger.getLogger(MetaTelemetry::class.java.name)
}
