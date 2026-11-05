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

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.struct
import com.google.protobuf.timestamp
import com.google.protobuf.value
import com.google.type.interval
import com.sun.net.httpserver.HttpServer
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupKt.entityKey
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.GetEventGroupRequest
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entity
import org.wfanet.measurement.api.v2alpha.copy
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.eventGroup
import org.wfanet.measurement.api.v2alpha.eventGroupMetadata
import org.wfanet.measurement.api.v2alpha.impressionQuery
import org.wfanet.measurement.gcloud.testing.FunctionsFrameworkInvokerProcess

/**
 * Runs [MetaImpressionQueryFunction] in a real Functions Framework invoker process, pointed at a
 * fake Graph API, so the HTTP status a caller sees is checked at the function boundary rather than
 * through the in-process servlet fakes in [MetaImpressionQueryFunctionTest].
 */
@RunWith(JUnit4::class)
class MetaImpressionQueryFunctionInvokerTest {
  private val metaRequests = AtomicInteger()
  private var metaErrorBody = AUTH_ERROR_BODY
  private lateinit var metaServer: HttpServer
  private lateinit var functionProcess: FunctionsFrameworkInvokerProcess
  private val kingdomRequests = CopyOnWriteArrayList<String>()
  private lateinit var kingdomServer: Server

  @Before
  fun startFakeMeta() {
    // Rejects every call with [metaErrorBody].
    metaServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    metaServer.createContext("/") { exchange ->
      metaRequests.incrementAndGet()
      val body = metaErrorBody.toByteArray()
      exchange.sendResponseHeaders(400, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    metaServer.start()
  }

  @After
  fun tearDown() {
    if (::functionProcess.isInitialized) {
      functionProcess.close()
    }
    if (::kingdomServer.isInitialized) {
      kingdomServer.shutdownNow()
    }
    metaServer.stop(0)
  }

  @Test
  fun `returns 502 at the function boundary when Meta rejects the credentials`() {
    assertThat(queryFunction(QUERY_REQUEST, emptyMap())).isEqualTo(502)
    // The 502 came from Meta rejecting the token, not from a failure before Meta was called.
    assertThat(metaRequests.get()).isGreaterThan(0)
  }

  @Test
  fun `returns 403 at the function boundary when Meta denies access to the ad account`() {
    metaErrorBody = PERMISSION_ERROR_BODY

    assertThat(queryFunction(QUERY_REQUEST, emptyMap())).isEqualTo(403)
    assertThat(metaRequests.get()).isGreaterThan(0)
  }

  @Test
  fun `treats a blank KINGDOM_TARGET as unset`() {
    // No TLS files are configured, so the function would fail to start if it used the target.
    assertThat(queryFunction(QUERY_REQUEST, mapOf("KINGDOM_TARGET" to ""))).isEqualTo(502)
  }

  @Test
  fun `fails to start when KINGDOM_TARGET is set without the TLS files`() {
    // A misconfigured Kingdom connection fails the deployment rather than silently disabling
    // attribution.
    assertFailsWith<IllegalStateException> {
      queryFunction(QUERY_REQUEST, mapOf("KINGDOM_TARGET" to "localhost:1"))
    }
  }

  @Test
  fun `fails to start when the ad account key is missing`() {
    assertFailsWith<IllegalStateException> {
      queryFunction(QUERY_REQUEST, kingdomEnv(1) - "ENTITY_METADATA_AD_ACCOUNT_ID_KEY")
    }
  }

  @Test
  fun `looks up the EventGroup of a hidden entity over mutual TLS`() {
    metaErrorBody = HIDDEN_ENTITY_ERROR_BODY
    val kingdomPort = startFakeKingdom()

    val status = queryFunction(QUERY_REQUEST_WITH_EVENT_GROUP, kingdomEnv(kingdomPort))

    // Meta hid the entity, so the query is skipped with ENTITY_NOT_FOUND.
    assertThat(status).isEqualTo(200)
    // The fake Kingdom answers only a client presenting a certificate signed by the trusted root,
    // over a connection whose server certificate matches KINGDOM_CERT_HOST.
    assertThat(kingdomRequests).containsExactly(EVENT_GROUP_NAME)
  }

  /**
   * Starts a fake Kingdom that requires mutual TLS and serves Meta `EventGroup`s naming their ad
   * account at [AD_ACCOUNT_ID_KEY], returning its port.
   */
  private fun startFakeKingdom(): Int {
    val sslContext =
      GrpcSslContexts.forServer(testdata("kingdom_tls.pem"), testdata("kingdom_tls.key"))
        .trustManager(testdata("root.pem"))
        .clientAuth(ClientAuth.REQUIRE)
        .build()
    val service =
      object : EventGroupsCoroutineImplBase() {
        override suspend fun getEventGroup(request: GetEventGroupRequest): EventGroup {
          kingdomRequests += request.name
          return eventGroup {
            name = request.name
            entityKey = ENTITY_KEY
            eventGroupMetadata = eventGroupMetadata {
              entityMetadata = struct {
                fields[AD_ACCOUNT_ID_KEY] = value { stringValue = "act_999" }
              }
            }
          }
        }
      }
    kingdomServer =
      NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        .sslContext(sslContext)
        .addService(service)
        .build()
        .start()
    return kingdomServer.port
  }

  /** The environment enabling `EventGroup` lookups against a fake Kingdom on [kingdomPort]. */
  private fun kingdomEnv(kingdomPort: Int): Map<String, String> =
    mapOf(
      "KINGDOM_TARGET" to "127.0.0.1:$kingdomPort",
      "KINGDOM_CERT_HOST" to "kingdom.example.com",
      "TLS_CERT_FILE" to testdata("edp_tls.pem").path,
      "TLS_KEY_FILE" to testdata("edp_tls.key").path,
      "CERT_COLLECTION_FILE" to testdata("root.pem").path,
      "ENTITY_METADATA_AD_ACCOUNT_ID_KEY" to AD_ACCOUNT_ID_KEY,
    )

  /**
   * Starts the function against the fake Graph API with [extraEnv] added to its environment, sends
   * [request], and returns its status.
   */
  private fun queryFunction(
    request: DataProviderImpressionQueryRequest,
    extraEnv: Map<String, String>,
  ): Int {
    functionProcess =
      FunctionsFrameworkInvokerProcess(
        javaBinaryPath = FUNCTION_BINARY_PATH,
        classTarget = FUNCTION_TARGET,
      )
    val port = runBlocking {
      functionProcess.start(
        mapOf(
          "META_ACCESS_TOKEN" to "dummy-token",
          "META_APP_SECRET" to "dummy-secret",
          "META_GRAPH_API_BASE" to "http://127.0.0.1:${metaServer.address.port}",
        ) + extraEnv
      )
    }

    val response =
      HttpClient.newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create("http://localhost:$port"))
            .header("Content-Type", "application/x-protobuf")
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray()))
            .build(),
          HttpResponse.BodyHandlers.discarding(),
        )
    return response.statusCode()
  }

  companion object {
    private val FUNCTION_BINARY_PATH =
      Paths.get(
        "_main",
        "src",
        "test",
        "kotlin",
        "org",
        "wfanet",
        "measurement",
        "edpcomponents",
        "meta",
        "InvokeMetaImpressionQueryFunction",
      )
    private const val FUNCTION_TARGET =
      "org.wfanet.measurement.edpcomponents.meta.MetaImpressionQueryFunction"

    private const val AUTH_ERROR_BODY =
      """{"error":{"message":"Error validating access token","type":"OAuthException",""" +
        """"code":190,"error_subcode":463}}"""

    private const val PERMISSION_ERROR_BODY =
      """{"error":{"message":"(#200) Requires ads_read permission","type":"OAuthException",""" +
        """"code":200}}"""

    private val TESTDATA_DIR =
      Paths.get("src", "test", "kotlin", "org", "wfanet", "measurement", "edpcomponents", "meta")
        .resolve("testdata")
        .toAbsolutePath()

    /** The testing-only file [name]; see `testdata/generate_test_certs.sh`. */
    private fun testdata(name: String): File = TESTDATA_DIR.resolve(name).toFile()

    // A non-default key, as a DataProvider defines its own `entity_metadata`.
    private const val AD_ACCOUNT_ID_KEY = "meta_account"
    private const val EVENT_GROUP_NAME = "dataProviders/edp-meta/eventGroups/eg-111"
    private val ENTITY_KEY = entityKey {
      entityType = "campaign"
      entityId = "111"
    }

    private const val HIDDEN_ENTITY_ERROR_BODY =
      """{"error":{"message":"Unsupported get request","type":"GraphMethodException",""" +
        """"code":100,"error_subcode":33}}"""

    private val QUERY_REQUEST = dataProviderImpressionQueryRequest {
      requestId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
      dataProvider = "dataProviders/edp-meta"
      query = impressionQuery {
        entities += entity {
          entityKey = entityKey {
            entityType = "campaign"
            entityId = "111"
          }
        }
        timeInterval = interval {
          startTime = timestamp { seconds = 1_700_000_000 }
          endTime = timestamp { seconds = 1_700_086_400 }
        }
      }
    }

    private val QUERY_REQUEST_WITH_EVENT_GROUP =
      QUERY_REQUEST.copy {
        query =
          query.copy {
            val named = entities.map { it.copy { eventGroup = EVENT_GROUP_NAME } }
            entities.clear()
            entities += named
          }
      }
  }
}
