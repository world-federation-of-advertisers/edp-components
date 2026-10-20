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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.gcloud.testing.FunctionsFrameworkInvokerProcess

/**
 * Runs [MetaAccountAccessCheckFunction] from the deploy JAR in a real Functions Framework invoker
 * process, pointed at a fake Graph API, so its environment parsing and HTTP status are checked as
 * Cloud Functions runs it.
 */
@RunWith(JUnit4::class)
class MetaAccountAccessCheckFunctionInvokerTest {
  private val metaPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private var metaStatus = 200
  private var metaBody = """{"id":"act_111"}"""
  private lateinit var metaServer: HttpServer
  private lateinit var functionProcess: FunctionsFrameworkInvokerProcess

  @Before
  fun startFakeMeta() {
    metaServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    metaServer.createContext("/") { exchange ->
      metaPaths.add(exchange.requestURI.path)
      val body = metaBody.toByteArray()
      exchange.sendResponseHeaders(metaStatus, body.size.toLong())
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
  fun `checks each configured account once and returns 200`() {
    assertThat(triggerFunction(expectedAdAccountIds = " 111, act_222,,act_111")).isEqualTo(200)

    assertThat(metaPaths).containsExactly("/v25.0/act_111", "/v25.0/act_222")
  }

  @Test
  fun `returns 502 when Meta rejects the credentials`() {
    metaStatus = 400
    metaBody =
      """{"error":{"message":"Error validating access token","type":"OAuthException",""" +
        """"code":190,"error_subcode":463}}"""

    assertThat(triggerFunction(expectedAdAccountIds = "111,222")).isEqualTo(502)
    assertThat(metaPaths).containsExactly("/v25.0/act_111")
  }

  /** Starts the function against the fake Graph API, triggers it once, and returns its status. */
  private fun triggerFunction(expectedAdAccountIds: String): Int {
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
          "META_EXPECTED_AD_ACCOUNT_IDS" to expectedAdAccountIds,
        )
      )
    }
    val response =
      HttpClient.newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create("http://localhost:$port"))
            .POST(HttpRequest.BodyPublishers.noBody())
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
      "org.wfanet.measurement.edpcomponents.meta.MetaAccountAccessCheckFunction"
  }
}
