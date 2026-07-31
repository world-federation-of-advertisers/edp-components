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
 * count. Resolving the request's entity keys to targets (via [MetaEntityModule]) and the CEL filter
 * to [MetaDemographicFilter] happens upstream in [MetaImpressionQueryFunction].
 */
interface MetaInsightsClient {
  /**
   * Returns the total Meta impression count across [targets] over [timeInterval], restricted to the
   * demographic buckets in [demographics].
   *
   * @throws MetaEntityNotFoundException if a target node is not found / not accessible.
   * @throws MetaApiException for any other Marketing API failure.
   * @throws MetaIntervalNotSupportedException if [timeInterval] cannot be expressed as a Meta
   *   day-granular `time_range` (not midnight-aligned in the ad account's timezone).
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
 * expressed here is [FILTER_NOT_SUPPORTED][SkipTranslation].
 */
data class MetaDemographicFilter(
  val ages: Set<MetaAgeBracket> = emptySet(),
  val genders: Set<MetaGender> = emptySet(),
) {
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

/** The Marketing API call failed (network, auth, rate limit, 5xx, etc.). */
class MetaApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The requested time interval cannot be expressed as a Meta day-granular `time_range` — i.e. it is
 * not aligned to midnight boundaries in the ad account's timezone. Meta Insights only supports
 * whole days in the account's timezone, so sub-day or unaligned intervals cannot be answered.
 */
class MetaIntervalNotSupportedException(message: String) : Exception(message)
