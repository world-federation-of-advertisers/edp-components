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

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpc

/** Reads the Meta Cloud Function's configuration from its environment. */
internal object MetaEnvironment {
  /**
   * Builds the production Meta client with the Meta System User token and app secret.
   *
   * Both env vars are populated from Secret Manager at deploy time — no Kotlin changes needed — via
   * the Cloud Functions Gen 2 `--set-secrets` flag:
   * ```
   * gcloud functions deploy ... \
   *   --set-secrets=META_ACCESS_TOKEN=meta-access-token:latest,META_APP_SECRET=meta-app-secret:latest
   * ```
   *
   * https://cloud.google.com/functions/docs/configuring/secrets
   *
   * `META_GRAPH_API_BASE` optionally overrides the Graph API origin. Production leaves it unset;
   * the function-boundary tests point it at a fake Graph API.
   */
  fun marketingApiClient(): MetaMarketingApiInsightsClient {
    val token =
      System.getenv("META_ACCESS_TOKEN")
        ?: error(
          "META_ACCESS_TOKEN not set (populated from Secret Manager at deploy via --set-secrets)"
        )
    val appSecret =
      System.getenv("META_APP_SECRET")
        ?: error(
          "META_APP_SECRET not set (populated from Secret Manager at deploy via --set-secrets)"
        )
    val graphApiBase = System.getenv("META_GRAPH_API_BASE")
    return if (graphApiBase == null) {
      MetaMarketingApiInsightsClient(accessToken = token, appSecret = appSecret)
    } else {
      MetaMarketingApiInsightsClient(
        accessToken = token,
        appSecret = appSecret,
        graphApiBase = graphApiBase,
      )
    }
  }

  /**
   * Builds the [MetaAdAccountResolver] that reads ad accounts from CMMS `EventGroup`s, or returns
   * null when `KINGDOM_TARGET` is unset or blank, in which case only accounts the Meta client
   * resolves are recorded.
   *
   * The function authenticates to the Kingdom as Meta's `DataProvider` over mutual TLS, using the
   * files named by `CMMS_TLS_CERT_FILE` (certificate chain, PEM), `CMMS_TLS_KEY_FILE` (PKCS #8
   * private key, PEM) and `CMMS_TRUSTED_CERTS_FILE` (trusted root certificates, PEM), typically
   * Secret Manager secrets mounted as files. With `KINGDOM_TARGET` set, a missing or unreadable
   * file is a misconfiguration and fails the function's startup. `KINGDOM_CERT_HOST` optionally
   * overrides the host name expected in the Kingdom's certificate, and
   * `CMMS_REQUEST_TIMEOUT_SECONDS` the deadline of each lookup.
   *
   * The channel is built with Netty directly because common-jvm's mutual TLS helpers are not
   * published to Maven.
   */
  fun cmmsAdAccountResolver(): MetaAdAccountResolver? {
    val target = nonBlankEnv("KINGDOM_TARGET") ?: return null
    fun requiredFile(name: String) =
      File(nonBlankEnv(name) ?: error("$name not set (required with KINGDOM_TARGET)"))
    val sslContext =
      GrpcSslContexts.forClient()
        .keyManager(requiredFile("CMMS_TLS_CERT_FILE"), requiredFile("CMMS_TLS_KEY_FILE"))
        .trustManager(requiredFile("CMMS_TRUSTED_CERTS_FILE"))
        .build()
    val channelBuilder =
      NettyChannelBuilder.forTarget(target)
        .sslContext(sslContext)
        .defaultServiceConfig(KINGDOM_SERVICE_CONFIG)
        .enableRetry()
    val certHost = nonBlankEnv("KINGDOM_CERT_HOST")
    if (certHost != null) {
      channelBuilder.overrideAuthority(certHost)
    }
    val channel = channelBuilder.build()
    // Lets in-flight lookups finish when the instance shuts down.
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          channel
            .shutdown()
            .awaitTermination(CHANNEL_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
      )
    val requestTimeout =
      nonBlankEnv("CMMS_REQUEST_TIMEOUT_SECONDS")?.let { Duration.ofSeconds(it.toLong()) }
        ?: DEFAULT_CMMS_REQUEST_TIMEOUT
    return CmmsEventGroupAdAccountResolver(EventGroupsGrpc.newBlockingStub(channel), requestTimeout)
  }

  /** The value of the environment variable [name], or null if it is unset or blank. */
  private fun nonBlankEnv(name: String): String? = System.getenv(name)?.ifBlank { null }

  // Short, because a lookup runs inside the function's request timeout.
  private val DEFAULT_CMMS_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
  private val CHANNEL_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(1)

  /**
   * Retries a lookup the Kingdom was unavailable for, as common-jvm's default service config does.
   * The request deadline still bounds the total time.
   */
  private val KINGDOM_SERVICE_CONFIG: Map<String, Any> =
    mapOf(
      "methodConfig" to
        listOf(
          mapOf(
            "name" to listOf(mapOf("service" to "wfa.measurement.api.v2alpha.EventGroups")),
            "retryPolicy" to
              mapOf(
                "maxAttempts" to 3.0,
                "initialBackoff" to "0.1s",
                "maxBackoff" to "1s",
                "backoffMultiplier" to 2.0,
                "retryableStatusCodes" to listOf("UNAVAILABLE"),
              ),
          )
        )
    )
}
