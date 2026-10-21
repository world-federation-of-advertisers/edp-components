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

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongGauge
import io.opentelemetry.api.metrics.Meter
import java.time.Instant

/**
 * Metrics for [MetaImpressionQueryFunction]:
 * - `meta.impression_query.queries`, one per query, keyed by [OUTCOME] and [ENTITY_TYPE].
 * - `meta.account_access.last_observed`, a gauge holding when each ([ACCOUNT_ID], [RESULT]) pair
 *   was last observed, in seconds since the epoch. An account's current access is the result with
 *   the latest time. It is updated only when a report queries the account, so an account no report
 *   has queried has no points yet.
 *
 * The names and attributes are also used by the Meta impression-query dashboard in
 * cross-media-measurement (`meta_impression_query_dashboard.json`); change both together.
 */
class MetaImpressionQueryMetrics(meter: Meter) {
  private val queries: LongCounter =
    meter
      .counterBuilder("meta.impression_query.queries")
      .setDescription("Meta impression queries, by outcome and entity type")
      .setUnit("{query}")
      .build()

  private val accountAccessLastObserved: LongGauge =
    meter
      .gaugeBuilder("meta.account_access.last_observed")
      .ofLongs()
      .setDescription("When each Meta ad account access result was last observed")
      .setUnit("s")
      .build()

  /** Records one query with [outcome] for entities of [entityType]. */
  fun recordQuery(outcome: Outcome, entityType: String) {
    queries.add(1, Attributes.of(OUTCOME, outcome.label, ENTITY_TYPE, entityType))
  }

  /** Records that a query observed [access] to the ad account [adAccountId] at [observedAt]. */
  fun recordAccountAccess(adAccountId: String, access: AccountAccess, observedAt: Instant) {
    accountAccessLastObserved.set(
      observedAt.epochSecond,
      Attributes.of(ACCOUNT_ID, adAccountId.removePrefix(ACCOUNT_PREFIX), RESULT, access.label),
    )
  }

  /** How an impression query ended, with the label it is recorded under. */
  enum class Outcome(val label: String) {
    /** Answered with an impression count. */
    SUCCESS("success"),
    /** Skipped with `FILTER_NOT_SUPPORTED`. */
    FILTER_NOT_SUPPORTED("filter_not_supported"),
    /** Skipped with `ENTITY_NOT_FOUND`: the entity is missing or the token cannot see it. */
    ENTITY_NOT_FOUND("entity_not_found"),
    /** Skipped with `API_ERROR`. */
    API_ERROR("api_error"),
    /** HTTP 403: the token is valid but has not been granted the ad account. */
    PERMISSION_DENIED("permission_denied"),
    /** HTTP 429: Meta throttled the query. */
    RATE_LIMITED("rate_limited"),
    /** HTTP 502: Meta rejected the credentials. */
    AUTH_FAILED("auth_failed"),
    /** Any other failure, such as a malformed request; answered with HTTP 500. */
    ERROR("error"),
  }

  /** Access to an ad account observed by a query, with the label it is recorded under. */
  enum class AccountAccess(val label: String) {
    /** Meta answered the query. */
    ACCESSIBLE("accessible"),
    /** Error `code` 10 or 200–299: the token is valid but has not been granted the account. */
    PERMISSION_DENIED("permission_denied"),
    /**
     * Error `code` 100 with subcode 33, or HTTP 404: missing or hidden from the token. Meta answers
     * the same way for an ad account that has not been shared with us.
     */
    INACCESSIBLE("inaccessible"),
  }

  companion object {
    /** [Outcome.label] of the query. */
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    /**
     * Meta node level the request's entities are queried at (`campaign`, `adset`, `ad` or
     * `account`), `mixed` for several levels, `unsupported` for an unrecognized `entity_type`, or
     * `unknown` when the request could not be read or names no entities.
     */
    val ENTITY_TYPE: AttributeKey<String> = AttributeKey.stringKey("entity_type")
    /** The ad account, without its `act_` prefix. */
    val ACCOUNT_ID: AttributeKey<String> = AttributeKey.stringKey("account_id")
    /** [AccountAccess.label] observed. */
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")

    private const val ACCOUNT_PREFIX = "act_"
  }
}
