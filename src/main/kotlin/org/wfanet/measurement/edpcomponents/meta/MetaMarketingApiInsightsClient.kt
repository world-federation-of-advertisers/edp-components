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

import com.google.type.Interval
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/**
 * [MetaInsightsClient] backed by the Meta Marketing API (Graph API) Insights endpoint.
 *
 * For each campaign it issues a synchronous Insights query broken down by `age` and `gender`, then
 * sums the impressions of the buckets that match [MetaDemographicFilter]. Synchronous is sufficient
 * for the validation query sizes (a handful of campaigns over a report interval); large-query async
 * report runs are deferred (see the class TODO and design doc §"future work").
 *
 * Auth: [accessToken] is a Meta System User token loaded from Secret Manager by the caller — it
 * never leaves this environment.
 *
 * TODO(@jojijacob): Add the async Insights report-run path (POST report run -> poll -> fetch) for
 *   campaigns/intervals whose synchronous query exceeds Meta's row/time limits.
 */
class MetaMarketingApiInsightsClient(
  private val accessToken: String,
  private val apiVersion: String = DEFAULT_API_VERSION,
  private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : MetaInsightsClient {

  override fun queryImpressions(
    campaignIds: List<String>,
    timeInterval: Interval,
    demographics: MetaDemographicFilter,
  ): Long {
    require(campaignIds.isNotEmpty()) { "campaignIds must not be empty" }
    val since = timeInterval.startTime.toUtcDate()
    val until = timeInterval.endTime.toUtcDate()
    return campaignIds.sumOf { campaignId -> queryCampaign(campaignId, since, until, demographics) }
  }

  private fun queryCampaign(
    campaignId: String,
    since: String,
    until: String,
    demographics: MetaDemographicFilter,
  ): Long {
    // Insights broken down by age + gender so we can sum only the requested buckets. `time_range`
    // is
    // date-granular in Meta's API.
    val timeRange = """{"since":"$since","until":"$until"}"""
    val uri =
      URI.create(
        "$GRAPH_API_BASE/$apiVersion/$campaignId/insights" +
          "?level=campaign" +
          "&fields=impressions" +
          "&breakdowns=age,gender" +
          "&time_range=${timeRange.urlEncoded()}" +
          "&access_token=${accessToken.urlEncoded()}"
      )
    val httpResponse: HttpResponse<String> =
      try {
        httpClient.send(
          HttpRequest.newBuilder(uri).GET().build(),
          HttpResponse.BodyHandlers.ofString(),
        )
      } catch (e: Exception) {
        throw MetaApiException("Marketing API request failed for campaign $campaignId", e)
      }

    when (httpResponse.statusCode()) {
      in 200..299 -> {}
      404 -> throw MetaEntityNotFoundException("Campaign $campaignId not found")
      else ->
        throw MetaApiException(
          "Marketing API returned ${httpResponse.statusCode()} for campaign $campaignId: " +
            httpResponse.body().take(500)
        )
    }

    // TODO(@jojijacob): Parse the Insights JSON and sum impressions over the rows whose `age`/
    //   `gender` fall in [demographics] (empty dimension = include all). Response shape:
    //   { "data": [ { "impressions": "123", "age": "25-34", "gender": "male" }, ... ] }.
    //   Also handle Meta's paging (`paging.next`) and rows with `impressions` absent (treat as 0).
    //   A JSON dependency needs to be introduced per WFA convention (separate dep-approval); pick
    //   one consistent with edp-components once its build conventions are confirmed.
    logger.fine {
      "Insights query for campaign $campaignId [$since..$until]: ${httpResponse.body().take(200)}"
    }
    throw NotImplementedError("Insights JSON parsing pending — see TODO above")
  }

  private fun com.google.protobuf.Timestamp.toUtcDate(): String =
    java.time.Instant.ofEpochSecond(seconds, nanos.toLong())
      .atZone(ZoneOffset.UTC)
      .toLocalDate()
      .format(DateTimeFormatter.ISO_LOCAL_DATE)

  private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)

  companion object {
    private val logger = Logger.getLogger(MetaMarketingApiInsightsClient::class.java.name)
    private const val GRAPH_API_BASE = "https://graph.facebook.com"
    // TODO(@jojijacob): Pin to the Marketing API version confirmed for the Meta EDP integration.
    private const val DEFAULT_API_VERSION = "v21.0"
  }
}
