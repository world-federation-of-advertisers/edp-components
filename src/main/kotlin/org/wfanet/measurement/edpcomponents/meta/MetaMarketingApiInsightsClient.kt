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
import java.util.concurrent.ConcurrentHashMap
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
 * extra Graph call, cached per account) and converts the interval to a `time_range` in that zone:
 * `since` is the start's date and `until` is `(end - 1 day)`'s date (mapping the exclusive end to
 * Meta's inclusive `until`). An interval that is not midnight-aligned in the account timezone
 * cannot be a whole number of Meta days and raises [MetaIntervalNotSupportedException].
 *
 * Auth: [accessToken] is a Meta System User token and [appSecret] is the Meta app secret, both
 * loaded from Secret Manager by the caller — they never leave this environment. Every request
 * carries an `appsecret_proof` (HMAC-SHA256 of the access token, keyed by the app secret) per
 * Meta's server-to-server hardening, so a leaked token alone cannot be replayed.
 * https://developers.facebook.com/docs/graph-api/guides/secure-requests
 *
 * TODO(@jojijacob): Add the async Insights report-run path (POST report run -> poll -> fetch) for
 *   entities/intervals whose synchronous query exceeds Meta's row/time limits.
 */
class MetaMarketingApiInsightsClient(
  private val accessToken: String,
  private val appSecret: String,
  private val apiVersion: String = DEFAULT_API_VERSION,
  private val graphApiBase: String = GRAPH_API_BASE,
  private val httpClient: HttpClient =
    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
) : MetaInsightsClient {

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
    val timeRange: String = toMetaTimeRange(timeInterval, zone)

    val allowedAges: Set<String> = demographics.ages.map { it.apiValue }.toSet()
    val allowedGenders: Set<String> = demographics.genders.map { it.apiValue }.toSet()

    // The node ID is the caller's entity_id verbatim; encode it so an odd character can't break the
    // URI or inject query parameters.
    val initialUri =
      URI.create(
        "$graphApiBase/$apiVersion/${target.nodeId.urlEncoded()}/insights" +
          "?level=${target.level}" +
          "&fields=impressions" +
          "&breakdowns=age,gender" +
          "&time_range=${timeRange.urlEncoded()}" +
          "&$authQuery"
      )

    var total = 0L
    var nextUri: URI? = initialUri
    while (nextUri != null) {
      val page: InsightsPage = parseInsightsPage(get(nextUri, target.nodeId), target.nodeId)
      for (row in page.data.orEmpty()) {
        if (allowedAges.isNotEmpty() && row.age !in allowedAges) continue
        if (allowedGenders.isNotEmpty() && row.gender !in allowedGenders) continue
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
   * Converts [interval] (half-open `[start, end)` in absolute time) to a Meta `time_range` JSON
   * string in [zone]. `since` is the start date; `until` is `(end - 1 day)`'s date so the exclusive
   * end maps to Meta's inclusive `until`. Throws [MetaIntervalNotSupportedException] if either
   * bound is not midnight-aligned in [zone] (Meta supports only whole days) or the interval is
   * empty.
   */
  private fun toMetaTimeRange(interval: Interval, zone: ZoneId): String {
    val start = instantOf(interval.startTime).atZone(zone)
    val end = instantOf(interval.endTime).atZone(zone)
    if (start.toLocalTime() != LocalTime.MIDNIGHT || end.toLocalTime() != LocalTime.MIDNIGHT) {
      throw MetaIntervalNotSupportedException(
        "Interval is not day-aligned in ad account timezone $zone; Meta Insights supports only " +
          "whole days"
      )
    }
    val since: LocalDate = start.toLocalDate()
    val until: LocalDate = end.toLocalDate().minusDays(1)
    if (until.isBefore(since)) {
      throw MetaIntervalNotSupportedException("Interval is empty (since=$since, until=$until)")
    }
    // LocalDate.toString() is ISO-8601 (yyyy-MM-dd), which is Meta's expected date format.
    return """{"since":"$since","until":"$until"}"""
  }

  /** Sends a GET to [uri] and returns the body, mapping non-success statuses to exceptions. */
  private fun get(uri: URI, nodeForError: String): String {
    val response: HttpResponse<String> =
      try {
        httpClient.send(
          HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofString(),
        )
      } catch (e: Exception) {
        throw MetaApiException("Marketing API request failed for $nodeForError", e)
      }
    return when (val status = response.statusCode()) {
      in 200..299 -> response.body()
      404 -> throw MetaEntityNotFoundException("Meta node $nodeForError not found")
      else ->
        throw MetaApiException(
          "Marketing API returned $status for $nodeForError: ${response.body().take(500)}"
        )
    }
  }

  private fun parseInsightsPage(body: String, nodeForError: String): InsightsPage {
    val page =
      try {
        gson.fromJson(body, InsightsPage::class.java)
      } catch (e: JsonSyntaxException) {
        throw MetaApiException("Malformed Insights JSON for $nodeForError: ${body.take(200)}", e)
      } ?: throw MetaApiException("Empty Insights response for $nodeForError")
    page.error.throwIfPresent(nodeForError)
    return page
  }

  private fun parseNode(body: String, nodeForError: String): NodeResponse {
    val node =
      try {
        gson.fromJson(body, NodeResponse::class.java)
      } catch (e: JsonSyntaxException) {
        throw MetaApiException("Malformed node JSON for $nodeForError: ${body.take(200)}", e)
      } ?: throw MetaApiException("Empty node response for $nodeForError")
    node.error.throwIfPresent(nodeForError)
    return node
  }

  // Meta sometimes embeds an error object in an HTTP 200 body; treating that as zero impressions
  // would be a silent wrong answer, so any present error is raised.
  private fun MetaError?.throwIfPresent(nodeForError: String) {
    if (this != null) {
      throw MetaApiException("Meta returned error for $nodeForError: $message (code $code)")
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
    val type: String? = null,
  )

  companion object {
    private const val GRAPH_API_BASE = "https://graph.facebook.com"
    private const val ACCOUNT_LEVEL = "account"
    private const val ACCOUNT_PREFIX = "act_"
    // Per-connection and per-request ceilings so a stalled Meta call can't hang the function: a
    // single query makes several sequential Graph round trips under the caller's overall budget.
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    // v25.0 (released 2026-02-18) is the current Graph API version per Meta's changelog:
    // https://developers.facebook.com/docs/graph-api/changelog
    private const val DEFAULT_API_VERSION = "v25.0"
  }
}
