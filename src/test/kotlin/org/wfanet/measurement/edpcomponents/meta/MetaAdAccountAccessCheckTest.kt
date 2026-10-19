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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MetaAdAccountAccessCheckTest {
  private lateinit var server: HttpServer
  private val requestUris = mutableListOf<URI>()
  private var status = 200
  private var body = """{"id":"act_$AD_ACCOUNT_ID"}"""

  @Before
  fun startServer() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  @After
  fun stopServer() {
    server.stop(0)
  }

  private fun handle(exchange: HttpExchange) {
    requestUris.add(exchange.requestURI)
    val bytes = body.toByteArray()
    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun newClient() =
    MetaMarketingApiInsightsClient(
      accessToken = ACCESS_TOKEN,
      appSecret = APP_SECRET,
      apiVersion = API_VERSION,
      graphApiBase = "http://127.0.0.1:${server.address.port}",
      httpClient = HttpClient.newHttpClient(),
    )

  private fun errorBody(code: Int, subcode: Int? = null): String {
    val subcodeField = if (subcode == null) "" else ""","error_subcode":$subcode"""
    return """{"error":{"message":"refused","type":"OAuthException","code":$code$subcodeField}}"""
  }

  @Test
  fun `checkAdAccountAccess returns ENABLED when the account is readable`() {
    val access = newClient().checkAdAccountAccess(AD_ACCOUNT_ID)

    assertThat(access)
      .isEqualTo(MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null))
    val uri = requestUris.single()
    assertThat(uri.path).isEqualTo("/$API_VERSION/act_$AD_ACCOUNT_ID")
    assertThat(uri.query).contains("fields=id")
    assertThat(uri.query).contains("appsecret_proof=")
  }

  @Test
  fun `checkAdAccountAccess accepts an ID with its act_ prefix`() {
    newClient().checkAdAccountAccess("act_$AD_ACCOUNT_ID")

    assertThat(requestUris.single().path).isEqualTo("/$API_VERSION/act_$AD_ACCOUNT_ID")
  }

  @Test
  fun `checkAdAccountAccess returns PERMISSION_DENIED with Meta's codes`() {
    status = 400
    body = errorBody(code = 200, subcode = 1815066)

    val access = newClient().checkAdAccountAccess(AD_ACCOUNT_ID)

    assertThat(access)
      .isEqualTo(MetaAdAccountAccess(MetaAdAccountAccess.Result.PERMISSION_DENIED, 200L, 1815066L))
  }

  @Test
  fun `checkAdAccountAccess returns PERMISSION_DENIED for an error embedded in a 200`() {
    body = errorBody(code = 10)

    val access = newClient().checkAdAccountAccess(AD_ACCOUNT_ID)

    assertThat(access)
      .isEqualTo(MetaAdAccountAccess(MetaAdAccountAccess.Result.PERMISSION_DENIED, 10L, null))
  }

  @Test
  fun `checkAdAccountAccess returns INACCESSIBLE for a missing or unreachable account`() {
    status = 400
    body = errorBody(code = 100, subcode = 33)

    val access = newClient().checkAdAccountAccess(AD_ACCOUNT_ID)

    assertThat(access)
      .isEqualTo(MetaAdAccountAccess(MetaAdAccountAccess.Result.INACCESSIBLE, 100L, 33L))
  }

  @Test
  fun `checkAdAccountAccess returns INACCESSIBLE for a bare 404`() {
    status = 404
    body = ""

    val access = newClient().checkAdAccountAccess(AD_ACCOUNT_ID)

    assertThat(access)
      .isEqualTo(MetaAdAccountAccess(MetaAdAccountAccess.Result.INACCESSIBLE, null, null))
  }

  @Test
  fun `checkAdAccountAccess throws MetaAuthException when the token is rejected`() {
    status = 400
    body = errorBody(code = 190)

    assertFailsWith<MetaAuthException> { newClient().checkAdAccountAccess(AD_ACCOUNT_ID) }
  }

  @Test
  fun `checkAdAccountAccess throws MetaRateLimitException when throttled`() {
    status = 400
    body = errorBody(code = 80004)

    assertFailsWith<MetaRateLimitException> { newClient().checkAdAccountAccess(AD_ACCOUNT_ID) }
  }

  @Test
  fun `checkAdAccountAccess throws MetaApiException for an unclassified error`() {
    status = 500
    body = errorBody(code = 1)

    val exception =
      assertFailsWith<MetaApiException> { newClient().checkAdAccountAccess(AD_ACCOUNT_ID) }
    assertThat(exception).isNotInstanceOf(MetaPermissionDeniedException::class.java)
  }

  companion object {
    private const val ACCESS_TOKEN = "test-token"
    private const val APP_SECRET = "test-secret"
    private const val API_VERSION = "v25.0"
    private const val AD_ACCOUNT_ID = "1234567890"
  }
}
