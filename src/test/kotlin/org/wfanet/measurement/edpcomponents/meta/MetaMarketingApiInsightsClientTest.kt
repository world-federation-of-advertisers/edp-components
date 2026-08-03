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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MetaMarketingApiInsightsClientTest {

  private lateinit var server: HttpServer
  private val requestUris = mutableListOf<URI>()

  /** Ordered insights responses (status, body) served to successive `/insights` requests. */
  private var insightsResponses: List<Pair<Int, String>> = emptyList()
  private var insightsIndex = 0
  private var accountIdBody = """{"account_id":"999","id":"111"}"""
  private var timezoneBody = """{"timezone_name":"Asia/Tokyo","id":"act_999"}"""

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
    val path = exchange.requestURI.path
    val query = exchange.requestURI.rawQuery ?: ""
    val (status, body) =
      when {
        path.endsWith("/insights") -> insightsResponses[insightsIndex++]
        query.contains("fields=account_id") -> 200 to accountIdBody
        query.contains("fields=timezone_name") -> 200 to timezoneBody
        else -> 500 to """{"error":{"message":"unexpected request","code":1}}"""
      }
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun client() =
    MetaMarketingApiInsightsClient(
      accessToken = ACCESS_TOKEN,
      appSecret = APP_SECRET,
      apiVersion = API_VERSION,
      graphApiBase = "http://127.0.0.1:${server.address.port}",
      httpClient = HttpClient.newHttpClient(),
    )

  private fun insightsRequest(): URI = requestUris.single { it.path.endsWith("/insights") }

  private fun insightsQueryDecoded(): String =
    URLDecoder.decode(insightsRequest().rawQuery, StandardCharsets.UTF_8)

  @Test
  fun `sums a single page and builds the insights URL with level, fields, auth, and account-TZ time_range`() {
    insightsResponses =
      listOf(
        200 to
          """{"data":[
            {"impressions":"100","age":"25-34","gender":"female"},
            {"impressions":"50","age":"18-24","gender":"male"}
          ]}"""
      )

    // Interval bounds are Asia/Tokyo (UTC+9) midnights: 2026-06-30T15:00Z == 2026-07-01T00:00
    // Tokyo,
    // and 2026-07-02T15:00Z == 2026-07-03T00:00 Tokyo. So since=2026-07-01 (proves account TZ is
    // applied, not UTC where it would be 06-30) and until=2026-07-02 (end date 07-03 minus one day,
    // proving the exclusive end maps to Meta's inclusive until).
    val count =
      client()
        .queryImpressions(
          listOf(MetaInsightsTarget(nodeId = "111", level = "campaign")),
          interval {
            startTime = timestamp { seconds = Instant.parse("2026-06-30T15:00:00Z").epochSecond }
            endTime = timestamp { seconds = Instant.parse("2026-07-02T15:00:00Z").epochSecond }
          },
          MetaDemographicFilter.UNFILTERED,
        )

    assertThat(count).isEqualTo(150L)
    val query = insightsQueryDecoded()
    assertThat(insightsRequest().path).isEqualTo("/$API_VERSION/111/insights")
    assertThat(query).contains("level=campaign")
    assertThat(query).contains("fields=impressions")
    assertThat(query).contains("breakdowns=age,gender")
    assertThat(query).contains("""time_range={"since":"2026-07-01","until":"2026-07-02"}""")
    assertThat(query).contains("access_token=$ACCESS_TOKEN")
    assertThat(query).contains("appsecret_proof=")
  }

  @Test
  fun `follows paging_next, re-appends appsecret_proof, and sums across pages`() {
    // Meta's paging.next echoes access_token but omits appsecret_proof; the client must re-append
    // it.
    val nextUri =
      "http://127.0.0.1:${server.address.port}/$API_VERSION/111/insights?after=CURSOR&access_token=$ACCESS_TOKEN"
    insightsResponses =
      listOf(
        200 to """{"data":[{"impressions":"100"}],"paging":{"next":"$nextUri"}}""",
        200 to """{"data":[{"impressions":"23"}]}""",
      )

    val count = client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)

    assertThat(count).isEqualTo(123L)
    assertThat(requestUris.count { it.path.endsWith("/insights") }).isEqualTo(2)
    val pagedRequest =
      requestUris.single {
        it.path.endsWith("/insights") && (it.rawQuery ?: "").contains("after=CURSOR")
      }
    assertThat(pagedRequest.rawQuery).contains("appsecret_proof=")
  }

  @Test
  fun `sums only the buckets matching a non-UNFILTERED demographic filter`() {
    insightsResponses =
      listOf(
        200 to
          """{"data":[
            {"impressions":"100","age":"25-34","gender":"female"},
            {"impressions":"50","age":"25-34","gender":"male"},
            {"impressions":"30","age":"18-24","gender":"female"}
          ]}"""
      )
    val filter =
      MetaDemographicFilter(
        ages = setOf(MetaAgeBracket.AGE_25_34),
        genders = setOf(MetaGender.FEMALE),
      )

    val count = client().queryImpressions(listOf(campaignTarget()), alignedInterval(), filter)

    assertThat(count).isEqualTo(100L)
  }

  @Test
  fun `throws MetaEntityNotFoundException on 404`() {
    insightsResponses = listOf(404 to """{"error":{"message":"not found","code":803}}""")

    assertFailsWith<MetaEntityNotFoundException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException on 5xx`() {
    insightsResponses = listOf(500 to """{"error":{"message":"server error","code":2}}""")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException on malformed JSON`() {
    insightsResponses = listOf(200 to "not json at all")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException on an error embedded in a 200 body`() {
    insightsResponses = listOf(200 to """{"error":{"message":"Invalid OAuth token","code":190}}""")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaIntervalNotSupportedException when the interval is not day-aligned in account TZ`() {
    insightsResponses = listOf(200 to """{"data":[]}""") // should never be reached

    // 2026-06-30T15:30Z is 00:30 in Tokyo — not a midnight boundary.
    val unaligned = interval {
      startTime = timestamp { seconds = Instant.parse("2026-06-30T15:30:00Z").epochSecond }
      endTime = timestamp { seconds = Instant.parse("2026-07-02T15:00:00Z").epochSecond }
    }

    assertFailsWith<MetaIntervalNotSupportedException> {
      client().queryImpressions(listOf(campaignTarget()), unaligned, UNFILTERED)
    }
    assertThat(requestUris.any { it.path.endsWith("/insights") }).isFalse()
  }

  private fun campaignTarget() = MetaInsightsTarget(nodeId = "111", level = "campaign")

  private fun alignedInterval() = interval {
    startTime = timestamp { seconds = Instant.parse("2026-06-30T15:00:00Z").epochSecond }
    endTime = timestamp { seconds = Instant.parse("2026-07-02T15:00:00Z").epochSecond }
  }

  companion object {
    private const val ACCESS_TOKEN = "test-token"
    private const val APP_SECRET = "test-secret"
    private const val API_VERSION = "v25.0"
    private val UNFILTERED = MetaDemographicFilter.UNFILTERED
  }
}
