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
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub

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
   * null when `CMMS_KINGDOM_TARGET` is unset, in which case only accounts the Meta client resolves
   * are recorded.
   *
   * The function authenticates to the Kingdom as Meta's `DataProvider` over mutual TLS, using the
   * files named by `CMMS_TLS_CERT_FILE` (certificate chain, PEM), `CMMS_TLS_KEY_FILE` (PKCS #8
   * private key, PEM) and `CMMS_TRUSTED_CERTS_FILE` (trusted root certificates, PEM), typically
   * Secret Manager secrets mounted as files. `CMMS_KINGDOM_CERT_HOST` optionally overrides the host
   * name expected in the Kingdom's certificate.
   */
  fun cmmsAdAccountResolver(): MetaAdAccountResolver? {
    val target = System.getenv("CMMS_KINGDOM_TARGET") ?: return null
    fun requiredFile(name: String) =
      File(System.getenv(name) ?: error("$name not set (required with CMMS_KINGDOM_TARGET)"))
    val sslContext =
      GrpcSslContexts.forClient()
        .keyManager(requiredFile("CMMS_TLS_CERT_FILE"), requiredFile("CMMS_TLS_KEY_FILE"))
        .trustManager(requiredFile("CMMS_TRUSTED_CERTS_FILE"))
        .build()
    val channelBuilder = NettyChannelBuilder.forTarget(target).sslContext(sslContext)
    System.getenv("CMMS_KINGDOM_CERT_HOST")?.let { channelBuilder.overrideAuthority(it) }
    return CmmsEventGroupAdAccountResolver(
      EventGroupsCoroutineStub(channelBuilder.build()),
      CMMS_REQUEST_TIMEOUT,
    )
  }

  // Short, because a lookup runs inside the function's request timeout.
  private val CMMS_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
}
