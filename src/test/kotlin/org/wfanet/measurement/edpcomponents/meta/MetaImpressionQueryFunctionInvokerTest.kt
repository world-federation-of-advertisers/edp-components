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
import com.google.protobuf.timestamp
import com.google.type.interval
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entityKey
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
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
  private lateinit var metaServer: HttpServer
  private lateinit var functionProcess: FunctionsFrameworkInvokerProcess

  @Before
  fun startFakeMeta() {
    // Rejects every call the way Meta rejects an expired or revoked token.
    metaServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    metaServer.createContext("/") { exchange ->
      metaRequests.incrementAndGet()
      val body = AUTH_ERROR_BODY.toByteArray()
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
    metaServer.stop(0)
  }

  @Test
  fun `returns 502 at the function boundary when Meta rejects the credentials`() {
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
        )
      )
    }

    val response =
      HttpClient.newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create("http://localhost:$port"))
            .header("Content-Type", "application/x-protobuf")
            .POST(HttpRequest.BodyPublishers.ofByteArray(QUERY_REQUEST.toByteArray()))
            .build(),
          HttpResponse.BodyHandlers.discarding(),
        )

    assertThat(response.statusCode()).isEqualTo(502)
    // The 502 came from Meta rejecting the token, not from a failure before Meta was called.
    assertThat(metaRequests.get()).isGreaterThan(0)
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

    private val QUERY_REQUEST = dataProviderImpressionQueryRequest {
      requestId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
      dataProvider = "dataProviders/edp-meta"
      query = impressionQuery {
        entityKeys += entityKey {
          entityType = "campaign"
          entityId = "111"
        }
        timeInterval = interval {
          startTime = timestamp { seconds = 1_700_000_000 }
          endTime = timestamp { seconds = 1_700_086_400 }
        }
      }
    }
  }
}
