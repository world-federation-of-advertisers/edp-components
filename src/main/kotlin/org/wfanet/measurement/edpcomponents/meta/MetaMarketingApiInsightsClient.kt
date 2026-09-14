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

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.protobuf.Timestamp
import com.google.type.Interval
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [MetaInsightsClient] backed by the Meta Marketing API (Graph API) Insights endpoint.
 *
 * For each target it issues a synchronous Insights query at the target's `level`, broken down by
 * `age` and `gender`, then sums the impressions of the buckets that match [MetaDemographicFilter].
 * Synchronous is sufficient for validation query sizes (a handful of entities over a report
 * interval); the async report-run path is deferred (see the class TODO and design doc §"future
 * work").
 *
 * **Time zone.** Meta Insights buckets impressions by day in the *ad account's* immutable timezone,
 * and `time_range.until` is inclusive of its calendar date. The request interval is a half-open
 * `[start, end)` in absolute time, so this client resolves each node's ad-account timezone (an
 * extra Graph call, cached per account) and covers the interval exactly with the queries
 * [planQuery] produces: one daily query for the interior whole days, plus an hourly query for each
 * partial boundary day counting only the in-interval buckets. A day-aligned interval is still a
 * single daily query. What cannot be reconstructed exactly — a bound off a whole hour, or a
 * boundary day that repeats an hour for a daylight-saving change — raises
 * [MetaIntervalNotSupportedException] rather than returning an approximation.
 *
 * Auth: [accessToken] is a Meta System User token and [appSecret] is the Meta app secret, both
 * loaded from Secret Manager by the caller — they never leave this environment. Every request
 * carries an `appsecret_proof` (HMAC-SHA256 of the access token, keyed by the app secret) per
 * Meta's server-to-server hardening, so a leaked token alone cannot be replayed.
 * https://developers.facebook.com/docs/graph-api/guides/secure-requests
 *
 * [quotaLogSampleInterval] is how many quota-bearing responses each sampled log entry stands for; 1
 * logs every response.
 *
 * TODO(@jojijacob): Add the async Insights report-run path (POST report run -> poll -> fetch) for
 *   entities/intervals whose synchronous query exceeds Meta's row/time limits.
 * TODO(world-federation-of-advertisers/edp-components#3): Add a real Meta-sandbox integration test
 *   confirming the `time_range`/timezone behavior, the age/gender bucket strings, and `level=ad` on
 *   an ad node against live Meta.
 */
class MetaMarketingApiInsightsClient(
  private val accessToken: String,
  private val appSecret: String,
  private val apiVersion: String = DEFAULT_API_VERSION,
  private val graphApiBase: String = GRAPH_API_BASE,
  private val httpClient: HttpClient =
    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
  private val quotaLogSampleInterval: Long = DEFAULT_QUOTA_LOG_SAMPLE_INTERVAL,
) : MetaInsightsClient {

  init {
    require(quotaLogSampleInterval > 0) {
      "quotaLogSampleInterval must be positive, got $quotaLogSampleInterval"
    }
  }

  // HMAC-SHA256 of the access token keyed by the app secret, in lowercase hex. Constant per client
  // instance (token and secret are fixed), so it is computed once and appended to every request.
  private val appSecretProof: String = hmacSha256Hex(key = appSecret, data = accessToken)

  private val authQuery: String =
    "access_token=${accessToken.urlEncoded()}&appsecret_proof=$appSecretProof"

  private val gson = Gson()

  // Ad-account timezone by account ID. Meta account timezones are immutable, so this is cached for
  // the life of the client (which is reused across requests / concurrent invocations).
  private val zoneByAccountId = ConcurrentHashMap<String, ZoneId>()

  // Ad-account ID by node ID, so a repeated node isn't re-resolved on every request. A node's
  // parent account is stable, so caching for the client's life is safe.
  private val accountIdByNodeId = ConcurrentHashMap<String, String>()

  // Successful responses carrying quota headers, counted per client instance to drive sampling.
  private val quotaResponsesSeen = AtomicLong()

  override fun queryImpressions(
    targets: List<MetaInsightsTarget>,
    timeInterval: Interval,
    demographics: MetaDemographicFilter,
  ): Long {
    require(targets.isNotEmpty()) { "targets must not be empty" }
    return targets.sumOf { target -> queryTarget(target, timeInterval, demographics) }
  }

  private fun queryTarget(
    target: MetaInsightsTarget,
    timeInterval: Interval,
    demographics: MetaDemographicFilter,
  ): Long {
    val zone: ZoneId = accountZone(target)
    val segments: List<QuerySegment> = planQuery(timeInterval, zone)
    // Meta rejects the hourly breakdown combined with age or gender, so a boundary day cannot be
    // both split by hour and filtered by demographic. Skip rather than compare a demographically
    // filtered reported count against an unfiltered publisher count.
    if (segments.any { it.hours != null } && !demographics.isUnfiltered) {
      throw MetaIntervalNotSupportedException(
        "Interval needs hourly boundary queries in ad account timezone $zone, which Meta does not " +
          "allow alongside an age or gender breakdown"
      )
    }
    return segments.sumOf { segment -> querySegment(target, segment, demographics) }
  }

  /** Queries [segment] and returns the impressions in it that match [demographics]. */
  private fun querySegment(
    target: MetaInsightsTarget,
    segment: QuerySegment,
    demographics: MetaDemographicFilter,
  ): Long {
    val allowedAges: Set<String> = demographics.ages.map { it.apiValue }.toSet()
    val allowedGenders: Set<String> = demographics.genders.map { it.apiValue }.toSet()
    val breakdowns = if (segment.hours == null) "age,gender" else HOURLY_BREAKDOWN

    // The node ID is the caller's entity_id verbatim; encode it so an odd character can't break the
    // URI or inject query parameters.
    val initialUri =
      URI.create(
        "$graphApiBase/$apiVersion/${target.nodeId.urlEncoded()}/insights" +
          "?level=${target.level}" +
          "&fields=impressions" +
          "&breakdowns=$breakdowns" +
          "&time_range=${segment.timeRange.urlEncoded()}" +
          "&$authQuery"
      )

    var total = 0L
    var nextUri: URI? = initialUri
    while (nextUri != null) {
      val graphResponse = get(nextUri, target.nodeId)
      val page: InsightsPage =
        parseInsightsPage(graphResponse.body, target.nodeId, graphResponse.quota)
      for (row in page.data.orEmpty()) {
        if (segment.hours == null) {
          if (allowedAges.isNotEmpty() && row.age !in allowedAges) continue
          if (allowedGenders.isNotEmpty() && row.gender !in allowedGenders) continue
        } else {
          val bucket =
            row.hourlyBucket
              ?: throw MetaApiException(
                "Insights row for ${target.nodeId} has no $HOURLY_BREAKDOWN value despite the " +
                  "hourly breakdown being requested"
              )
          if (hourOf(bucket, target.nodeId) !in segment.hours) continue
        }
        // Fail loudly on a missing/non-numeric count: unlike a total-zero (which is skipped), a
        // partial undercount gets compared and can inflate the deviation toward a false FAIL.
        val impressions =
          row.impressions
            ?: throw MetaApiException("Missing impressions in Insights row for ${target.nodeId}")
        total +=
          impressions.toLongOrNull()
            ?: throw MetaApiException(
              "Non-numeric impressions '$impressions' in Insights row for ${target.nodeId}"
            )
      }
      // Meta's paging.next echoes access_token but deliberately NOT appsecret_proof (Meta won't act
      // as a signing oracle for it), so it must be re-appended or the paged request fails auth.
      nextUri = page.paging?.next?.let { URI.create(withAppSecretProof(it)) }
    }
    return total
  }

  private fun withAppSecretProof(url: String): String {
    val separator = if ('?' in url) "&" else "?"
    return "$url${separator}appsecret_proof=$appSecretProof"
  }

  /**
   * Resolves the ad-account timezone for [target]'s node. Both the node→account mapping and the
   * account→timezone mapping are cached, so a repeated node costs zero extra Graph round trips.
   */
  private fun accountZone(target: MetaInsightsTarget): ZoneId {
    val accountId: String =
      if (target.level == ACCOUNT_LEVEL) target.nodeId.removePrefix(ACCOUNT_PREFIX)
      else accountIdByNodeId.computeIfAbsent(target.nodeId) { fetchAccountId(it) }
    return zoneByAccountId.computeIfAbsent(accountId) { fetchAccountTimeZone(it) }
  }

  private fun fetchAccountId(nodeId: String): String {
    val uri =
      URI.create("$graphApiBase/$apiVersion/${nodeId.urlEncoded()}?fields=account_id&$authQuery")
    val node = parseNode(get(uri, nodeId), nodeId)
    return node.accountId ?: throw MetaApiException("Meta node $nodeId returned no account_id")
  }

  private fun fetchAccountTimeZone(accountId: String): ZoneId {
    val node = "$ACCOUNT_PREFIX$accountId"
    val uri =
      URI.create("$graphApiBase/$apiVersion/${node.urlEncoded()}?fields=timezone_name&$authQuery")
    val name =
      parseNode(get(uri, node), node).timezoneName
        ?: throw MetaApiException("Meta account $node returned no timezone_name")
    return try {
      ZoneId.of(name)
    } catch (e: RuntimeException) {
      throw MetaApiException("Meta account $node returned unrecognized timezone_name '$name'", e)
    }
  }

  /**
   * Splits [interval] (half-open `[start, end)` in absolute time) into the Insights queries that
   * together cover it exactly in [zone].
   *
   * Meta buckets impressions by whole day in the ad account's timezone, so an interval whose bounds
   * are not account-local midnights cannot be answered by one daily query. The interior whole days
   * are a single daily query; each partial boundary day is an hourly query whose in-interval
   * buckets are summed. That is at most two extra requests however long the interval is.
   *
   * @throws MetaIntervalNotSupportedException if [interval] is empty, a bound is not on a whole
   *   hour in [zone], or a partial boundary day repeats an hour for a daylight-saving change.
   */
  private fun planQuery(interval: Interval, zone: ZoneId): List<QuerySegment> {
    val start = instantOf(interval.startTime).atZone(zone)
    val end = instantOf(interval.endTime).atZone(zone)
    if (!end.isAfter(start)) {
      throw MetaIntervalNotSupportedException("Interval is empty (start=$start, end=$end)")
    }
    requireWholeHour(start, "start", zone)
    requireWholeHour(end, "end", zone)

    val startsAtMidnight = start.toLocalTime() == LocalTime.MIDNIGHT
    val endsAtMidnight = end.toLocalTime() == LocalTime.MIDNIGHT
    val firstDay: LocalDate = start.toLocalDate()
    // The end is exclusive, so an end at local midnight belongs to the preceding day.
    val lastDay: LocalDate =
      if (endsAtMidnight) end.toLocalDate().minusDays(1) else end.toLocalDate()

    if (firstDay == lastDay) {
      // Both bounds fall in one local day: either it is that whole day, or it is one hourly query.
      // Counting it once here is what keeps a single-day interval from being double-counted as
      // both a leading and a trailing partial day.
      return if (startsAtMidnight && endsAtMidnight) {
        listOf(QuerySegment(firstDay, firstDay))
      } else {
        val toHour = if (endsAtMidnight) HOURS_PER_DAY else end.hour
        listOf(hourlySegment(firstDay, start.hour, toHour, zone))
      }
    }

    return buildList {
      if (!startsAtMidnight) {
        add(hourlySegment(firstDay, start.hour, HOURS_PER_DAY, zone))
      }
      val wholeFrom = if (startsAtMidnight) firstDay else firstDay.plusDays(1)
      val wholeTo = if (endsAtMidnight) lastDay else lastDay.minusDays(1)
      if (!wholeFrom.isAfter(wholeTo)) {
        add(QuerySegment(wholeFrom, wholeTo))
      }
      if (!endsAtMidnight) {
        add(hourlySegment(lastDay, 0, end.hour, zone))
      }
    }
  }

  /**
   * An hourly [QuerySegment] over `[fromHour, toHourExclusive)` of [date].
   *
   * @throws MetaIntervalNotSupportedException if [date] repeats a local hour for a daylight-saving
   *   fall-back in [zone]. Meta labels hourly buckets by local hour only, so both occurrences of
   *   the repeated hour share one bucket and neither can be attributed to the requested interval. A
   *   spring-forward day is fine: every bucket still maps to at most one real hour, and the hour
   *   that does not exist simply returns no row.
   */
  private fun hourlySegment(
    date: LocalDate,
    fromHour: Int,
    toHourExclusive: Int,
    zone: ZoneId,
  ): QuerySegment {
    val dayLength = Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone))
    if (dayLength > Duration.ofHours(HOURS_PER_DAY.toLong())) {
      throw MetaIntervalNotSupportedException(
        "Partial boundary day $date repeats an hour for a daylight-saving change in ad account " +
          "timezone $zone, so Meta's hourly buckets cannot be mapped to the requested interval"
      )
    }
    return QuerySegment(date, date, fromHour until toHourExclusive)
  }

  /**
   * Requires [time] to fall on a whole hour in [zone]. A zone at a half-hour offset (for example
   * `Asia/Kolkata`) puts a UTC-aligned bound in the middle of a Meta hourly bucket, which cannot be
   * split.
   */
  private fun requireWholeHour(time: ZonedDateTime, bound: String, zone: ZoneId) {
    val local: LocalTime = time.toLocalTime()
    if (local.minute != 0 || local.second != 0 || local.nano != 0) {
      throw MetaIntervalNotSupportedException(
        "Interval $bound is $local in ad account timezone $zone, which is not a whole hour; " +
          "Meta's smallest bucket is one hour"
      )
    }
  }

  /** Parses the local hour from a Meta hourly bucket label, which reads `HH:00:00 - HH:59:59`. */
  private fun hourOf(bucket: String, nodeForError: String): Int =
    bucket.substringBefore(':').toIntOrNull()?.takeIf { it in 0 until HOURS_PER_DAY }
      ?: throw MetaApiException("Unrecognized hourly bucket '$bucket' for $nodeForError")

  /**
   * One Insights query covering part of the requested interval.
   *
   * @param since first account-local day to query, inclusive
   * @param until last account-local day to query; Meta's `until` is inclusive of its date
   * @param hours when set, query [since]'s hourly breakdown and count only these local hours; when
   *   null, query `[since, until]` as one daily aggregate
   */
  private data class QuerySegment(
    val since: LocalDate,
    val until: LocalDate,
    val hours: IntRange? = null,
  ) {
    init {
      require(hours == null || since == until) { "An hourly segment covers a single day" }
    }

    /** LocalDate.toString() is ISO-8601 (yyyy-MM-dd), which is Meta's expected date format. */
    val timeRange: String
      get() = """{"since":"$since","until":"$until"}"""
  }

  /** A successful Graph response: its body, and the quota headers that accompanied it. */
  private data class GraphResponse(val body: String, val quota: ThrottleHeaders)

  /** Sends a GET to [uri], mapping non-success statuses to exceptions. */
  private fun get(uri: URI, nodeForError: String): GraphResponse {
    val response: HttpResponse<String> =
      try {
        httpClient.send(
          HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofString(),
        )
      } catch (e: Exception) {
        throw MetaApiException("Marketing API request failed for $nodeForError", e)
      }

    logThrottleHeaders(response, nodeForError)

    val status = response.statusCode()
    // Meta answers this endpoint with 200 or an error; any other success status is unexpected and
    // is surfaced rather than parsed as though it carried an Insights payload.
    if (status == HTTP_OK) return GraphResponse(response.body(), throttleHeadersOf(response))
    throw exceptionFor(status, response, nodeForError)
  }

  /**
   * Maps a non-success response to the most specific exception it identifies, in precedence order.
   *
   * A recognised error `code` wins over the HTTP status; see [MetaRateLimitException] for why
   * status is not a usable discriminator. Status is the fallback for responses carrying no error
   * object.
   */
  private fun exceptionFor(
    status: Int,
    response: HttpResponse<String>,
    nodeForError: String,
  ): Exception {
    val error: MetaError? = parseErrorOrNull(response.body())
    return when {
      error?.isRateLimit == true ->
        MetaRateLimitException(
          "Marketing API throttled for $nodeForError. ${error.describe(nodeForError)}. " +
            "Quota: ${throttleHeadersOf(response)}"
        )
      error?.isAuthFailure == true ->
        MetaAuthException("Marketing API rejected credentials. ${error.describe(nodeForError)}")
      status == 404 -> MetaEntityNotFoundException("Meta node $nodeForError not found")
      error != null -> MetaApiException("HTTP $status. ${error.describe(nodeForError)}")
      else ->
        MetaApiException(
          "Marketing API returned $status for $nodeForError: ${response.body().take(500)}"
        )
    }
  }

  private fun parseErrorOrNull(body: String): MetaError? =
    try {
      gson.fromJson(body, InsightsPage::class.java)?.error
    } catch (e: JsonSyntaxException) {
      null
    }

  private fun throttleHeadersOf(response: HttpResponse<*>) =
    ThrottleHeaders(
      businessUseCase = response.headers().firstValue(BUSINESS_USE_CASE_USAGE_HEADER).orElse(null),
      appUsage = response.headers().firstValue(APP_USAGE_HEADER).orElse(null),
    )

  /**
   * Quota consumption and `ads_api_access_tier` are observable only from these headers, so a sample
   * is logged at INFO — FINE is suppressed by the default JUL configuration, which would write them
   * nowhere. Throttles are logged in full by the caller of [exceptionFor].
   */
  private fun logThrottleHeaders(response: HttpResponse<*>, nodeForError: String) {
    val headers = throttleHeadersOf(response)
    if (headers.isEmpty()) return
    val seen = quotaResponsesSeen.incrementAndGet()
    // Sample the first response and every quotaLogSampleInterval-th one after it (1, 1001, 2001,
    // ...), so each entry after the first stands for exactly quotaLogSampleInterval - 1 suppressed
    // responses. Sampling at 1, 1000, 2000 instead makes only the second gap short, and its entry
    // would claim one more suppressed response than it actually stood for.
    if ((seen - 1) % quotaLogSampleInterval != 0L) return
    val suppressed = if (seen == 1L) 0 else quotaLogSampleInterval - 1
    logger.info(
      "Meta quota for $nodeForError: $headers (suppressed $suppressed since the previous entry)"
    )
  }

  private fun parseInsightsPage(
    body: String,
    nodeForError: String,
    quota: ThrottleHeaders,
  ): InsightsPage {
    val page =
      try {
        gson.fromJson(body, InsightsPage::class.java)
      } catch (e: JsonSyntaxException) {
        throw MetaApiException("Malformed Insights JSON for $nodeForError: ${body.take(200)}", e)
      } ?: throw MetaApiException("Empty Insights response for $nodeForError")
    page.error.throwIfPresent(nodeForError, quota)
    return page
  }

  private fun parseNode(response: GraphResponse, nodeForError: String): NodeResponse {
    val node =
      try {
        gson.fromJson(response.body, NodeResponse::class.java)
      } catch (e: JsonSyntaxException) {
        throw MetaApiException(
          "Malformed node JSON for $nodeForError: ${response.body.take(200)}",
          e,
        )
      } ?: throw MetaApiException("Empty node response for $nodeForError")
    node.error.throwIfPresent(nodeForError, response.quota)
    return node
  }

  // Meta sometimes embeds an error object in an HTTP 200 body; treating that as zero impressions
  // would be a silent wrong answer, so any present error is raised — classified by `code`, as on
  // the non-2xx path, so a throttle is never mistaken for an ordinary failure.
  private fun MetaError?.throwIfPresent(nodeForError: String, quota: ThrottleHeaders) {
    if (this == null) return
    throw when {
      isRateLimit ->
        MetaRateLimitException("Marketing API throttled. ${describe(nodeForError)}. Quota: $quota")
      isAuthFailure ->
        MetaAuthException("Marketing API rejected credentials. ${describe(nodeForError)}")
      else -> MetaApiException(describe(nodeForError))
    }
  }

  private fun instantOf(ts: Timestamp): Instant =
    Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong())

  private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

  private fun hmacSha256Hex(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") {
      "%02x".format(it.toInt() and 0xFF)
    }
  }

  // Meta Insights response shapes (only the fields consumed here). All nullable: gson leaves absent
  // fields null, and a `{"error":{...}}` body has none of the data fields.
  private data class InsightsPage(
    val data: List<InsightsRow>? = null,
    val paging: Paging? = null,
    val error: MetaError? = null,
  )

  private data class InsightsRow(
    val impressions: String? = null,
    val age: String? = null,
    val gender: String? = null,
    @SerializedName(HOURLY_BREAKDOWN) val hourlyBucket: String? = null,
  )

  private data class Paging(val next: String? = null)

  private data class NodeResponse(
    @SerializedName("account_id") val accountId: String? = null,
    @SerializedName("timezone_name") val timezoneName: String? = null,
    val error: MetaError? = null,
  )

  private data class MetaError(
    val message: String? = null,
    val code: Long? = null,
    @SerializedName("error_subcode") val subcode: Long? = null,
    val type: String? = null,
    @SerializedName("fbtrace_id") val fbtraceId: String? = null,
  ) {
    /** Whether this is a Business Use Case throttle. See [MetaRateLimitException]. */
    val isRateLimit: Boolean
      get() = code in RATE_LIMIT_CODES

    /** Whether Meta rejected the credentials. See [MetaAuthException]. */
    val isAuthFailure: Boolean
      get() = code == AUTH_ERROR_CODE

    fun describe(nodeForError: String): String =
      "Meta returned error for $nodeForError: $message " +
        "(code=$code subcode=$subcode type=$type fbtrace_id=$fbtraceId)"
  }

  /** Response headers Meta uses to report quota consumption. Logged so throttling is visible. */
  private data class ThrottleHeaders(val businessUseCase: String?, val appUsage: String?) {
    fun isEmpty(): Boolean = businessUseCase == null && appUsage == null

    override fun toString(): String =
      "$BUSINESS_USE_CASE_USAGE_HEADER=$businessUseCase $APP_USAGE_HEADER=$appUsage"
  }

  companion object {
    private const val GRAPH_API_BASE = "https://graph.facebook.com"
    private const val ACCOUNT_LEVEL = "account"
    private const val ACCOUNT_PREFIX = "act_"
    // Buckets a single day into 24 rows labelled by hour in the ad account's timezone. Over a range
    // it sums each hour across every day in the range, so a segment using it covers one day.
    private const val HOURLY_BREAKDOWN = "hourly_stats_aggregated_by_advertiser_time_zone"
    // Bucket count Meta returns per day, including on a 23- or 25-hour daylight-saving day.
    private const val HOURS_PER_DAY = 24
    // Per-connection and per-request ceilings so a stalled Meta call can't hang the function: a
    // single query makes several sequential Graph round trips under the caller's overall budget.
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    // v25.0 (released 2026-02-18) is the current Graph API version per Meta's changelog:
    // https://developers.facebook.com/docs/graph-api/changelog
    private const val DEFAULT_API_VERSION = "v25.0"

    private const val HTTP_OK = 200

    // https://developers.facebook.com/docs/graph-api/overview/rate-limiting
    //
    // Marketing API calls fall under Business Use Case limits, which is what the Insights endpoint
    // and the ad-object reads here are subject to. The two Platform codes are included because an
    // app- or account-wide throttle can still surface on these endpoints. Page codes (80001, 32)
    // are deliberately absent: they apply to the Pages API, which this client never calls.
    private const val ADS_INSIGHTS_RATE_LIMIT_CODE = 80000L
    private const val ADS_MANAGEMENT_RATE_LIMIT_CODE = 80004L
    private const val APP_RATE_LIMIT_CODE = 4L
    private const val USER_RATE_LIMIT_CODE = 17L
    private const val APPLICATION_LIMIT_CODE = 341L
    private const val CUSTOM_RATE_LIMIT_CODE = 613L
    private val RATE_LIMIT_CODES =
      setOf(
        ADS_INSIGHTS_RATE_LIMIT_CODE,
        ADS_MANAGEMENT_RATE_LIMIT_CODE,
        APP_RATE_LIMIT_CODE,
        USER_RATE_LIMIT_CODE,
        APPLICATION_LIMIT_CODE,
        CUSTOM_RATE_LIMIT_CODE,
      )

    private const val AUTH_ERROR_CODE = 190L

    // Logged so quota consumption and `ads_api_access_tier` are visible before requests start
    // being rejected.
    private const val BUSINESS_USE_CASE_USAGE_HEADER = "x-business-use-case-usage"
    private const val APP_USAGE_HEADER = "x-app-usage"

    // Successful responses are sampled rather than logged individually: at report-creation volume
    // one line per Graph call would be unusable, but quota climbs silently without any.
    private const val DEFAULT_QUOTA_LOG_SAMPLE_INTERVAL = 1_000L

    private val logger: Logger = Logger.getLogger(MetaMarketingApiInsightsClient::class.java.name)
  }
}
