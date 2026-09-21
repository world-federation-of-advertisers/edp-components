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
import com.google.common.truth.Truth.assertWithMessage
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
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
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

  /** Value served as `x-business-use-case-usage`, or null to omit the header. */
  private var quotaHeader: String? = null

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
        path.endsWith("/insights") ->
          insightsResponses.getOrNull(insightsIndex++)
            ?: (500 to """{"error":{"message":"no canned insights response","code":0}}""")
        query.contains("fields=account_id") -> 200 to accountIdBody
        query.contains("fields=timezone_name") -> 200 to timezoneBody
        else -> 500 to """{"error":{"message":"unexpected request","code":1}}"""
      }
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    quotaHeader?.let { exchange.responseHeaders.add("x-business-use-case-usage", it) }
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  /**
   * [quotaLogSampleInterval] mirrors the client's own default; only the sampling test overrides it.
   */
  private fun client(quotaLogSampleInterval: Long = 1_000L) =
    MetaMarketingApiInsightsClient(
      accessToken = ACCESS_TOKEN,
      appSecret = APP_SECRET,
      apiVersion = API_VERSION,
      graphApiBase = "http://127.0.0.1:${server.address.port}",
      httpClient = HttpClient.newHttpClient(),
      quotaLogSampleInterval = quotaLogSampleInterval,
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
  fun `moves a sub-hour bound to the nearest bucket edge rather than rejecting it`() {
    // 2026-06-30T15:30Z is 00:30 in Tokyo. It is nearer 00:00 than 01:00, so the interval starts at
    // Tokyo midnight and the whole first day is queried daily rather than split by hour.
    insightsResponses = listOf(200 to """{"data":[{"impressions":"42"}]}""")

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-06-30T15:30:00Z", "2026-07-02T15:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(42L)
    assertThat(insightsQueries().single())
      .contains("""time_range={"since":"2026-07-01","until":"2026-07-02"}""")
  }

  @Test
  fun `sums across multiple targets and fetches the account timezone only once`() {
    insightsResponses =
      listOf(
        200 to """{"data":[{"impressions":"100"}]}""",
        200 to """{"data":[{"impressions":"25"}]}""",
      )

    val count =
      client()
        .queryImpressions(
          listOf(
            MetaInsightsTarget(nodeId = "111", level = "campaign"),
            MetaInsightsTarget(nodeId = "222", level = "campaign"),
          ),
          alignedInterval(),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(125L)
    // Both targets are under the same account, so the timezone is resolved once (cache reuse).
    assertThat(requestUris.count { (it.rawQuery ?: "").contains("fields=timezone_name") })
      .isEqualTo(1)
  }

  @Test
  fun `resolves a repeated node's account only once across queries`() {
    insightsResponses =
      listOf(
        200 to """{"data":[{"impressions":"10"}]}""",
        200 to """{"data":[{"impressions":"5"}]}""",
      )
    // One instance, as in a warm function instance serving successive requests. Querying the same
    // node twice must resolve its account_id only once (the accountIdByNodeId cache).
    val client = client()

    client.queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    client.queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)

    assertThat(requestUris.count { (it.rawQuery ?: "").contains("fields=account_id") }).isEqualTo(1)
  }

  @Test
  fun `resolves timezone directly for an account-level target without an account_id lookup`() {
    insightsResponses = listOf(200 to """{"data":[{"impressions":"7"}]}""")

    val count =
      client()
        .queryImpressions(
          listOf(MetaInsightsTarget(nodeId = "act_999", level = "account")),
          alignedInterval(),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(7L)
    assertThat(requestUris.none { (it.rawQuery ?: "").contains("fields=account_id") }).isTrue()
    assertThat(insightsRequest().path).isEqualTo("/$API_VERSION/act_999/insights")
  }

  @Test
  fun `throws MetaApiException when the node returns no account_id`() {
    accountIdBody = """{"id":"111"}"""
    insightsResponses = listOf(200 to """{"data":[]}""")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException when timezone_name is unrecognized`() {
    timezoneBody = """{"timezone_name":"Not/AZone"}"""
    insightsResponses = listOf(200 to """{"data":[]}""")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException on a non-numeric impressions value`() {
    insightsResponses = listOf(200 to """{"data":[{"impressions":"not-a-number"}]}""")

    assertFailsWith<MetaApiException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `computes appsecret_proof as the HMAC-SHA256 of the access token keyed by the app secret`() {
    insightsResponses = listOf(200 to """{"data":[{"impressions":"1"}]}""")

    client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)

    // HMAC-SHA256(key=APP_SECRET, data=ACCESS_TOKEN) as lowercase hex. Pinned to a known digest so
    // a swapped key/data pair or a wrong charset fails here rather than on the first real Meta
    // call — which would not happen until the sandbox test in #3.
    assertThat(insightsQueryDecoded())
      .contains("appsecret_proof=4bd72343ca044f8aab1d98f07606cdb1cf47df0c089ff7b5b2df44e40d869970")
  }

  @Test
  fun `url-encodes a node ID containing query-delimiter characters`() {
    insightsResponses = listOf(200 to """{"data":[{"impressions":"7"}]}""")

    val count =
      client()
        .queryImpressions(
          listOf(MetaInsightsTarget(nodeId = "111&level=account", level = "campaign")),
          alignedInterval(),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(7L)
    // The delimiters must stay inside the path segment rather than becoming a second query
    // parameter: an unencoded node ID would let a caller-supplied ID inject `level=account` and
    // silently change the aggregation level the count is computed at.
    assertThat(insightsRequest().rawPath).isEqualTo("/$API_VERSION/111%26level%3Daccount/insights")
    assertThat(insightsQueryDecoded()).contains("level=campaign")
  }

  @Test
  fun `classifies every recognised throttle code as MetaRateLimitException`() {
    // Each code has to be exercised: they are what distinguishes a throttle from an ordinary 400,
    // so one going unclassified would silently regress to a generic API error.
    for (code in listOf(80000L, 80004L, 4L, 17L, 341L, 613L)) {
      insightsIndex = 0
      insightsResponses = listOf(400 to """{"error":{"message":"too many calls","code":$code}}""")

      assertFailsWith<MetaRateLimitException>("code $code should classify as a throttle") {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }
    }
  }

  @Test
  fun `does not classify Pages throttle codes, which this client never triggers`() {
    // 80001 and 32 are documented for the Pages API. This client calls ad objects and Insights, so
    // treating them as throttles here would be classifying a response we cannot receive.
    for (code in listOf(80001L, 32L)) {
      insightsIndex = 0
      insightsResponses = listOf(400 to """{"error":{"message":"page limit","code":$code}}""")

      val failure =
        assertFailsWith<MetaApiException> {
          client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
        }

      assertThat(failure).isNotInstanceOf(MetaRateLimitException::class.java)
    }
  }

  @Test
  fun `classifies an Ads Insights throttle as MetaRateLimitException, not a generic API error`() {
    // Meta signals Business Use Case throttling with HTTP 400 and code 80000 — never HTTP 429,
    // which the Marketing API does not return. Status alone cannot distinguish this from a
    // malformed request, so classification has to come from `code`.
    insightsResponses =
      listOf(
        400 to
          """{"error":{"message":"(#80000) There have been too many calls from this ad-account.",
             "type":"OAuthException","code":80000,"error_subcode":2446079,
             "fbtrace_id":"AbC123"}}"""
      )

    assertFailsWith<MetaRateLimitException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `a throttled response never sums to zero impressions`() {
    // The failure mode this guards against: a throttle that degrades to a skip looks identical to
    // a campaign that genuinely did not deliver. Today the day-alignment guard means requests
    // rarely reach Meta at all, so throttling is not yet observable — that coupling is
    // load-bearing and undocumented. When the interval handling is fixed (#4) and real volume
    // starts flowing, a throttle misread as zero would silently under-report a live advertiser.
    insightsResponses =
      listOf(
        400 to """{"error":{"message":"too many calls","code":80000,"error_subcode":2446079}}"""
      )

    val failure =
      assertFailsWith<MetaRateLimitException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).isInstanceOf(MetaApiException::class.java)
  }

  @Test
  fun `prefers the error code over the HTTP status when classifying`() {
    // Status is the weaker signal: Meta answers throttling, expired credentials and malformed
    // requests all with 400, so a recognised `code` has to win over status. Pinned because it is
    // the one case where classifying by status and classifying by code disagree — without this,
    // the ordering in exceptionFor could be reversed and every other test would still pass.
    insightsResponses =
      listOf(
        404 to """{"error":{"message":"too many calls","code":80000,"error_subcode":2446079}}"""
      )

    assertFailsWith<MetaRateLimitException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `throws MetaApiException when a failure carries no parseable error object`() {
    // Infrastructure between us and Meta can fail with a non-JSON body — a proxy's HTML error
    // page, or nothing at all. That reaches a different branch than a malformed 200 body, which
    // is parsed as an Insights page rather than as an error.
    insightsResponses = listOf(502 to "<html><body>Bad Gateway</body></html>")

    val failure =
      assertFailsWith<MetaApiException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).isNotInstanceOf(MetaRateLimitException::class.java)
    assertThat(failure).hasMessageThat().contains("502")
  }

  @Test
  fun `classifies an expired token as MetaAuthException`() {
    insightsResponses =
      listOf(
        400 to
          """{"error":{"message":"Error validating access token: Session has expired.",
             "type":"OAuthException","code":190,"error_subcode":463}}"""
      )

    assertFailsWith<MetaAuthException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `an ordinary bad request stays a plain MetaApiException`() {
    // Same HTTP status as the throttle and the expired token above; only `code` differs.
    insightsResponses =
      listOf(
        400 to """{"error":{"message":"(#100) bad field","type":"OAuthException","code":100}}"""
      )

    val failure =
      assertFailsWith<MetaApiException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).isNotInstanceOf(MetaRateLimitException::class.java)
    assertThat(failure).isNotInstanceOf(MetaAuthException::class.java)
  }

  @Test
  fun `carries quota headers on a non-2xx throttle`() {
    quotaHeader = """{"1":[{"type":"ads_insights","call_count":100}]}"""
    insightsResponses = listOf(400 to """{"error":{"message":"too many","code":80000}}""")

    val failure =
      assertFailsWith<MetaRateLimitException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).hasMessageThat().contains("ads_insights")
  }

  @Test
  fun `carries quota headers on a throttle embedded in a 200 during a node lookup`() {
    // The account and timezone lookups parse through parseNode, a separate path from the Insights
    // page. A throttle arriving there previously reached the boundary with no quota context.
    quotaHeader = """{"1":[{"type":"ads_management","call_count":100}]}"""
    accountIdBody = """{"error":{"message":"too many","code":80004}}"""

    val failure =
      assertFailsWith<MetaRateLimitException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).hasMessageThat().contains("ads_management")
  }

  @Test
  fun `classifies a throttle embedded in a 200 body`() {
    // Meta also embeds errors in successful responses; that path must classify identically.
    insightsResponses =
      listOf(
        200 to """{"error":{"message":"too many calls","code":80000,"error_subcode":2446079}}"""
      )

    assertFailsWith<MetaRateLimitException> {
      client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    }
  }

  @Test
  fun `surfaces fbtrace_id in the error message`() {
    // Meta support asks for fbtrace_id first; losing it makes an escalation much slower.
    insightsResponses =
      listOf(400 to """{"error":{"message":"boom","code":100,"fbtrace_id":"TRACE-XYZ"}}""")

    val failure =
      assertFailsWith<MetaApiException> {
        client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
      }

    assertThat(failure).hasMessageThat().contains("TRACE-XYZ")
  }

  @Test
  fun `returns zero for a window with no delivery`() {
    // Verified against live Meta: an entity that did not deliver in the window returns HTTP 200
    // with an empty data array, no `paging` key and no `error` key. That is a real count of zero,
    // not a failure, so it must not raise — the caller distinguishes the two, and treating it as
    // an error would turn "this campaign didn't run" into a validation outage.
    insightsResponses = listOf(200 to """{"data":[]}""")

    val count = client().queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)

    assertThat(count).isEqualTo(0L)
  }

  @Test
  fun `samples quota logging every interval-th response and reports the true suppressed count`() {
    // Sampling at 1, N, 2N leaves the first gap one response shorter than every later gap, so the
    // entry at N claims to stand for N-1 suppressed responses when it stands for N-2. Sampling at
    // 1, N+1, 2N+1 makes every gap the same length, so the claim is always true.
    //
    // Warm the ad-account and timezone caches with the quota header absent: those two lookups are
    // also quota-bearing, and skipping them here makes every counted response an Insights response,
    // so the sampled entries line up with the query numbers below.
    quotaHeader = null
    insightsResponses = List(QUERY_COUNT + 1) { 200 to """{"data":[]}""" }
    val client = client(quotaLogSampleInterval = SAMPLE_INTERVAL)
    client.queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
    quotaHeader = """{"1":[{"type":"ads_insights","call_count":1}]}"""

    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records.add(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val logger = Logger.getLogger(MetaMarketingApiInsightsClient::class.java.name)
    logger.addHandler(handler)
    val entriesAfterEachQuery =
      try {
        (1..QUERY_COUNT).map {
          client.queryImpressions(listOf(campaignTarget()), alignedInterval(), UNFILTERED)
          records.size
        }
      } finally {
        logger.removeHandler(handler)
      }

    // An entry after queries 1, 4 and 7 — never 3 or 6, which is what the off-by-one cadence gave.
    assertThat(entriesAfterEachQuery).containsExactly(1, 1, 1, 2, 2, 2, 3).inOrder()
    assertThat(records).hasSize(3)
    assertThat(records[0].message).contains("(suppressed 0 since the previous entry)")
    assertThat(records[1].message)
      .contains("(suppressed ${SAMPLE_INTERVAL - 1} since the previous entry)")
    assertThat(records[2].message)
      .contains("(suppressed ${SAMPLE_INTERVAL - 1} since the previous entry)")
  }

  private fun campaignTarget() = MetaInsightsTarget(nodeId = "111", level = "campaign")

  private fun alignedInterval() = interval {
    startTime = timestamp { seconds = Instant.parse("2026-06-30T15:00:00Z").epochSecond }
    endTime = timestamp { seconds = Instant.parse("2026-07-02T15:00:00Z").epochSecond }
  }

  private fun intervalOf(start: String, end: String) = interval {
    startTime = timestamp { seconds = Instant.parse(start).epochSecond }
    endTime = timestamp { seconds = Instant.parse(end).epochSecond }
  }

  /** Every `/insights` query issued, decoded, in request order. */
  private fun insightsQueries(): List<String> =
    requestUris
      .filter { it.path.endsWith("/insights") }
      .map { URLDecoder.decode(it.rawQuery, StandardCharsets.UTF_8) }

  private fun hourlyRow(hour: Int, impressions: Long): String =
    """{"impressions":"$impressions","hourly_stats_aggregated_by_advertiser_time_zone":""" +
      """"%02d:00:00 - %02d:59:59"}""".format(hour, hour)

  private fun hourlyPage(vararg hours: Pair<Int, Long>): String =
    """{"data":[${hours.joinToString(",") { hourlyRow(it.first, it.second) }}]}"""

  @Test
  fun `reconstructs an interval whose both bounds are mid-day in the ad account timezone`() {
    // Asia/Tokyo is UTC+9, so a UTC-midnight interval starts and ends at 09:00 local. The exact
    // count is hours 9-23 of the first local day, every whole day between, and hours 0-8 of the
    // last. Buckets outside those hours must not be counted.
    insightsResponses =
      listOf(
        200 to hourlyPage(8 to 5L, 9 to 10L, 23 to 20L),
        200 to """{"data":[{"impressions":"1000"}]}""",
        200 to hourlyPage(0 to 7L, 8 to 3L, 9 to 50L),
      )

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-07-01T00:00:00Z", "2026-07-08T00:00:00Z"),
          UNFILTERED,
        )

    // 10 + 20 in hours 9-23 of the first day, 1000 for the whole days, 7 + 3 in hours 0-8 of the
    // last. Buckets 8 on the first day and 9 on the last fall outside and must not be counted.
    assertThat(count).isEqualTo(1040L)
    val queries = insightsQueries()
    assertThat(queries).hasSize(3)
    assertThat(queries[0]).contains("""time_range={"since":"2026-07-01","until":"2026-07-01"}""")
    assertThat(queries[0]).contains("breakdowns=hourly_stats_aggregated_by_advertiser_time_zone")
    assertThat(queries[1]).contains("""time_range={"since":"2026-07-02","until":"2026-07-07"}""")
    assertThat(queries[1]).contains("breakdowns=age,gender")
    assertThat(queries[2]).contains("""time_range={"since":"2026-07-08","until":"2026-07-08"}""")
    assertThat(queries[2]).contains("breakdowns=hourly_stats_aggregated_by_advertiser_time_zone")
  }

  @Test
  fun `reconstructs an interval with only a trailing partial day`() {
    // Starts at Tokyo midnight, ends at 12:00 Tokyo: whole days then one hourly boundary, no
    // leading hourly query.
    insightsResponses =
      listOf(200 to """{"data":[{"impressions":"400"}]}""", 200 to hourlyPage(0 to 9L, 12 to 99L))

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-06-30T15:00:00Z", "2026-07-03T03:00:00Z"),
          UNFILTERED,
        )

    // Bucket 12 starts exactly at the exclusive end, so it is outside the interval.
    assertThat(count).isEqualTo(409L)
    val queries = insightsQueries()
    assertThat(queries).hasSize(2)
    assertThat(queries[0]).contains("""time_range={"since":"2026-07-01","until":"2026-07-02"}""")
    assertThat(queries[1]).contains("""time_range={"since":"2026-07-03","until":"2026-07-03"}""")
  }

  @Test
  fun `queries one hourly day when both bounds fall inside the same local day`() {
    // Regression: treating this as both a leading and a trailing partial day would double-count it.
    insightsResponses = listOf(200 to hourlyPage(5 to 100L, 6 to 11L, 11 to 22L, 12 to 100L))

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-06-30T21:00:00Z", "2026-07-01T03:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(33L)
    assertThat(insightsQueries()).hasSize(1)
    assertThat(insightsQueries().single())
      .contains("""time_range={"since":"2026-07-01","until":"2026-07-01"}""")
  }

  @Test
  fun `treats hours Meta omits as zero delivery`() {
    // Meta returns no row for an hour with no delivery. Only bucket 23 is in the interval.
    insightsResponses =
      listOf(200 to hourlyPage(23 to 12L), 200 to """{"data":[]}""", 200 to """{"data":[]}""")

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-07-01T00:00:00Z", "2026-07-08T00:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(12L)
  }

  @Test
  fun `follows paging on an hourly boundary query`() {
    val nextUri =
      "http://127.0.0.1:${server.address.port}/$API_VERSION/111/insights?after=HOURS&access_token=$ACCESS_TOKEN"
    insightsResponses =
      listOf(
        200 to """{"data":[${hourlyRow(9, 4L)}],"paging":{"next":"$nextUri"}}""",
        200 to hourlyPage(10 to 6L),
        200 to """{"data":[{"impressions":"0"}]}""",
        200 to """{"data":[]}""",
      )

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-07-01T00:00:00Z", "2026-07-08T00:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(10L)
    val paged =
      requestUris.single {
        it.path.endsWith("/insights") && (it.rawQuery ?: "").contains("after=HOURS")
      }
    assertThat(paged.rawQuery).contains("appsecret_proof=")
  }

  @Test
  fun `stays exact when a fall-back repeated hour is wholly excluded`() {
    // America/New_York ends DST on 2026-11-01, so local 01:00-01:59 happens twice and shares one
    // bucket. Starting at 13:00 local leaves both occurrences outside the interval, so hours 13-23
    // are reconstructable with no adjustment at all.
    timezoneBody = NEW_YORK_TIMEZONE
    insightsResponses = listOf(200 to hourlyPage(1 to 900L, 13 to 8L, 23 to 2L), 200 to EMPTY_PAGE)

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-11-01T18:00:00Z", "2026-11-05T05:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(10L)
    assertThat(insightsQueries()[0])
      .contains("""time_range={"since":"2026-11-01","until":"2026-11-01"}""")
  }

  @Test
  fun `stays exact when a fall-back repeated hour is wholly included`() {
    // Starting at 00:00 local puts both occurrences of the repeated hour inside the interval, so
    // the whole bucket belongs to it and the count is exact.
    timezoneBody = NEW_YORK_TIMEZONE
    insightsResponses = listOf(200 to """{"data":[{"impressions":"250"}]}""")

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-11-01T04:00:00Z", "2026-11-03T05:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(250L)
    assertThat(insightsQueries().single())
      .contains("""time_range={"since":"2026-11-01","until":"2026-11-02"}""")
  }

  @Test
  fun `resolves a bound inside the fall-back repeated hour to the earlier edge of its bucket`() {
    // 2026-11-01T06:00Z is the *second* 01:00 local; 05:00Z is the first. Meta shares one bucket
    // between them, so a bound at either takes the whole bucket or none of it — never half. The
    // plan keys off local hour, which is 1 for both, so the bound resolves to the earlier of the
    // two equidistant edges and the bucket is wholly included.
    timezoneBody = NEW_YORK_TIMEZONE
    insightsResponses = listOf(200 to hourlyPage(0 to 500L, 1 to 30L, 2 to 6L), 200 to EMPTY_PAGE)

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-11-01T06:00:00Z", "2026-11-05T05:00:00Z"),
          UNFILTERED,
        )

    // Hour 0 precedes the snapped start and is excluded; hour 1 is now wholly inside.
    assertThat(count).isEqualTo(36L)
  }

  @Test
  fun `reconstructs across a spring-forward boundary day`() {
    // The mirror of the fall-back case: on 2026-03-08 the local day is 23 hours, but every bucket
    // still maps to at most one real hour, so the mapping stays exact. The hour that does not exist
    // simply returns no row.
    timezoneBody = NEW_YORK_TIMEZONE
    insightsResponses = listOf(200 to hourlyPage(1 to 3L, 3 to 4L), 200 to """{"data":[]}""")

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-03-08T06:00:00Z", "2026-03-10T04:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(7L)
  }

  @Test
  fun `keeps a whole daylight-saving day in the daily query`() {
    // The fall-back day is only a problem when it has to be split by hour. Wholly inside the
    // interval it is covered by the daily aggregate, which already includes the repeated hour.
    timezoneBody = NEW_YORK_TIMEZONE
    insightsResponses = listOf(200 to """{"data":[{"impressions":"77"}]}""")

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-10-31T04:00:00Z", "2026-11-03T05:00:00Z"),
          UNFILTERED,
        )

    assertThat(count).isEqualTo(77L)
    assertThat(insightsQueries()).hasSize(1)
    assertThat(insightsQueries().single())
      .contains("""time_range={"since":"2026-10-31","until":"2026-11-02"}""")
  }

  @Test
  fun `moves a half-hour-offset bound to the nearest bucket edge`() {
    // Asia/Kolkata is UTC+5:30, so a UTC-midnight bound lands exactly halfway through a bucket.
    // Both edges are thirty minutes away, so the tie resolves earlier: 05:30 local becomes 05:00.
    timezoneBody = KOLKATA_TIMEZONE
    insightsResponses =
      listOf(200 to hourlyPage(4 to 900L, 5 to 11L, 23 to 4L), 200 to EMPTY_PAGE, 200 to EMPTY_PAGE)

    val count =
      client()
        .queryImpressions(
          listOf(campaignTarget()),
          intervalOf("2026-07-01T00:00:00Z", "2026-07-08T00:00:00Z"),
          UNFILTERED,
        )

    // Hour 5 is inside the effective interval; hour 4 precedes it and is excluded.
    assertThat(count).isEqualTo(15L)
    assertThat(insightsQueries()[0])
      .contains("""time_range={"since":"2026-07-01","until":"2026-07-01"}""")
  }

  @Test
  fun `skips an interval that both bounds collapse onto the same bucket edge`() {
    // A span shorter than one bucket, wholly inside its second half: on Asia/Kolkata these bounds
    // are 05:40 and 05:50 local, and both are nearer 06:00 than 05:00, so both resolve to the same
    // edge and the effective interval is empty. Nothing is queried.
    timezoneBody = KOLKATA_TIMEZONE

    val failure =
      assertFailsWith<MetaIntervalNotSupportedException> {
        client()
          .queryImpressions(
            listOf(campaignTarget()),
            // 05:40 and 05:50 local; both are nearer 06:00, so the effective interval is empty.
            intervalOf("2026-07-01T00:10:00Z", "2026-07-01T00:20:00Z"),
            UNFILTERED,
          )
      }

    assertThat(failure).hasMessageThat().contains("empty")
    assertThat(failure).hasMessageThat().contains("effective")
    assertThat(requestUris.any { it.path.endsWith("/insights") }).isFalse()
  }

  @Test
  fun `skips a misaligned interval that also carries a demographic filter`() {
    // Meta rejects the hourly breakdown combined with age or gender, so a filtered boundary day
    // cannot be answered. Comparing a filtered reported count against an unfiltered publisher count
    // would be a false deviation, so this skips instead.
    val filter = MetaDemographicFilter(genders = setOf(MetaGender.FEMALE))

    val failure =
      assertFailsWith<MetaIntervalNotSupportedException> {
        client()
          .queryImpressions(
            listOf(campaignTarget()),
            intervalOf("2026-07-01T00:00:00Z", "2026-07-08T00:00:00Z"),
            filter,
          )
      }

    assertThat(failure).hasMessageThat().contains("age or gender")
  }

  @Test
  fun `still answers a day-aligned filtered interval with one daily query`() {
    // The demographic path is unaffected when no boundary day needs splitting.
    insightsResponses =
      listOf(
        200 to
          """{"data":[
            {"impressions":"60","age":"25-34","gender":"female"},
            {"impressions":"40","age":"25-34","gender":"male"}
          ]}"""
      )
    val filter = MetaDemographicFilter(genders = setOf(MetaGender.FEMALE))

    val count = client().queryImpressions(listOf(campaignTarget()), alignedInterval(), filter)

    assertThat(count).isEqualTo(60L)
    assertThat(insightsQueries().single()).contains("breakdowns=age,gender")
  }

  @Test
  fun `raises on an unrecognized hourly bucket label`() {
    // A bucket this client cannot place is not zero delivery — counting it as such would silently
    // undercount the interval.
    // The first is rejected by any parser. The rest are the cases the old integer-prefix parser
    // accepted: a start that is not the top of the hour, an end hour that does not match the start,
    // and a label with no second endpoint at all.
    val badLabels = listOf("midday", "09:30:00 - 09:59:59", "09:00:00 - 10:59:59", "09:00:00")
    for (label in badLabels) {
      requestUris.clear()
      insightsIndex = 0
      insightsResponses =
        listOf(
          200 to
            """{"data":[{"impressions":"5",""" +
              """"hourly_stats_aggregated_by_advertiser_time_zone":"$label"}]}"""
        )

      val failure =
        assertFailsWith<MetaApiException> {
          client()
            .queryImpressions(
              listOf(campaignTarget()),
              intervalOf("2026-06-30T21:00:00Z", "2026-07-01T03:00:00Z"),
              UNFILTERED,
            )
        }

      assertWithMessage("label %s", label).that(failure).hasMessageThat().contains(label)
    }
  }

  companion object {
    private const val ACCESS_TOKEN = "test-token"
    private const val APP_SECRET = "test-secret"
    private const val API_VERSION = "v25.0"
    private val UNFILTERED = MetaDemographicFilter.UNFILTERED

    // Small enough that the sampling test can reach three entries in a handful of queries.
    private const val KOLKATA_TIMEZONE = """{"timezone_name":"Asia/Kolkata","id":"act_999"}"""
    private const val NEW_YORK_TIMEZONE = """{"timezone_name":"America/New_York","id":"act_999"}"""
    private const val EMPTY_PAGE = """{"data":[]}"""

    private const val SAMPLE_INTERVAL = 3L
    private const val QUERY_COUNT = 7
  }
}
