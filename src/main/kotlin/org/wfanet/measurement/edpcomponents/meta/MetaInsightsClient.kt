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

/**
 * Reads impression counts from Meta's Marketing API (Graph API Insights).
 *
 * This is the only part of the Meta cloud function that talks to Meta. It is intentionally narrow:
 * given already-resolved Insights [targets][MetaInsightsTarget] (Graph node ID + aggregation
 * `level`), a time interval, and a demographic breakdown filter, it returns the summed impression
 * count. Resolving the request's entity keys to targets (via [MetaEntityLevels]) and the CEL filter
 * to [MetaDemographicFilter] happens upstream in [MetaImpressionQueryFunction].
 */
interface MetaInsightsClient {
  /**
   * Returns the total Meta impression count across [targets] over [timeInterval], restricted to the
   * demographic buckets in [demographics].
   *
   * [timeInterval] need not be day-aligned in the ad account's timezone. A bound on a Meta bucket
   * boundary there is answered exactly. The one bound that is not on a boundary — one inside an
   * hour a daylight-saving fall-back repeats, which Meta reports as a single bucket — resolves to
   * the earlier edge of that bucket, so the count can cover up to an hour more than requested.
   *
   * @throws MetaEntityNotFoundException if a target node is not found / not accessible.
   * @throws MetaApiException for any other Marketing API failure.
   * @throws MetaIntervalNotSupportedException if [timeInterval] is empty, as requested or once its
   *   bounds are moved to bucket edges.
   */
  fun queryImpressions(
    targets: List<MetaInsightsTarget>,
    timeInterval: Interval,
    demographics: MetaDemographicFilter,
  ): Long
}

/**
 * A resolved Meta Insights query target: the Graph [nodeId] to query and the aggregation [level]
 * (`account` / `campaign` / `adset` / `ad`) that node is at.
 */
data class MetaInsightsTarget(val nodeId: String, val level: String)

/**
 * A demographic slice expressed in Meta's fixed breakdown dimensions. An empty set means "no
 * restriction on that dimension" (i.e. all buckets). A non-empty set restricts to those buckets,
 * which are summed. This is what a supported CEL filter translates to; anything that cannot be
 * expressed here is skipped with `FILTER_NOT_SUPPORTED` (see [MetaImpressionQueryFunction]).
 */
data class MetaDemographicFilter(
  val ages: Set<MetaAgeBracket> = emptySet(),
  val genders: Set<MetaGender> = emptySet(),
) {
  /** Whether this restricts neither dimension, and so needs no demographic breakdown. */
  val isUnfiltered: Boolean
    get() = ages.isEmpty() && genders.isEmpty()

  companion object {
    /** No demographic restriction — total impressions for the campaigns over the interval. */
    val UNFILTERED = MetaDemographicFilter()
  }
}

/**
 * Meta's fixed Insights `age` breakdown brackets. CMM age groups (e.g. 18-34) are unions of these,
 * so a supported CEL age filter maps to the set of brackets it covers.
 */
enum class MetaAgeBracket(val apiValue: String) {
  // Meta documents 13-17 as a valid age value. It only appears for inventory eligible to serve to
  // minors, so a live run against an account whose ad sets all target 18+ will never produce it.
  AGE_13_17("13-17"),
  AGE_18_24("18-24"),
  AGE_25_34("25-34"),
  AGE_35_44("35-44"),
  AGE_45_54("45-54"),
  AGE_55_64("55-64"),
  AGE_65_PLUS("65+"),
}

/** Meta's Insights `gender` breakdown values. */
enum class MetaGender(val apiValue: String) {
  MALE("male"),
  FEMALE("female"),
  UNKNOWN("unknown"),
}

/** A Meta campaign/entity referenced by the request could not be found or accessed. */
class MetaEntityNotFoundException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/** The Marketing API call failed (network, 5xx, malformed response, etc.). */
open class MetaApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Meta throttled the request under a Business Use Case rate limit.
 *
 * Distinct from [MetaApiException] because it is **transient and self-correcting**, where a
 * malformed request is neither. Meta signals throttling with HTTP 400 and an error `code` of 80000
 * (Ads Insights), 80004 (Ads Management), or 80001 (Page) — never HTTP 429, which the Marketing API
 * does not return. Status alone cannot distinguish a throttle from a bad request.
 *
 * Handling is to wait out the window for the affected ad account rather than retrying:
 * `X-Business-Use-Case-Usage` carries `estimated_time_to_regain_access` for that purpose.
 */
class MetaRateLimitException(message: String, cause: Throwable? = null) :
  MetaApiException(message, cause)

/**
 * Meta rejected the credentials — error `code` 190, typically an expired or revoked access token.
 *
 * Distinct from [MetaApiException] because it is **not transient**: every subsequent call fails
 * identically until the token is replaced. It warrants alerting rather than being counted among
 * ordinary API errors.
 */
class MetaAuthException(message: String, cause: Throwable? = null) :
  MetaApiException(message, cause)

/**
 * The requested time interval cannot be answered in Meta's buckets.
 *
 * Meta Insights measures whole days in the ad account's timezone, and whole hours within one such
 * day. An interval that is not day-aligned there is answered by combining the interior whole days
 * with the hourly buckets of each partial boundary day, and a bound that is not on a bucket edge is
 * moved to the nearest one. This is raised only when even that leaves nothing to query:
 * - An interval that is empty, either as requested or once its bounds are moved. A span shorter
 *   than one bucket can collapse this way — on a zone at a half-hour offset, for example, both
 *   bounds of a twenty-minute interval can resolve to the same edge.
 * - An interval needing hourly boundary queries together with a demographic filter, which Meta does
 *   not allow in one query.
 */
class MetaIntervalNotSupportedException(message: String) : Exception(message)
