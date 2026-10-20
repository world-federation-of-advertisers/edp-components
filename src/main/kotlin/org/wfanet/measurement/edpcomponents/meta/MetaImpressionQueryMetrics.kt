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
import io.opentelemetry.api.metrics.Meter

/**
 * Records impression queries as `meta.impression_queries_total`, keyed by [OUTCOME] and
 * [ENTITY_TYPE]. Unlike the hourly account check, this covers every account a report asks about,
 * including ones no one listed, so a rise in `entity_not_found` or `permission_denied` shows
 * advertisers whose access is missing.
 */
class MetaImpressionQueryMetrics(meter: Meter) {
  private val queries: LongCounter =
    meter
      .counterBuilder("meta.impression_queries_total")
      .setDescription("Meta impression queries, by outcome and entity type")
      .setUnit("{query}")
      .build()

  /** Records one query with [outcome] for entities of [entityType]. */
  fun record(outcome: Outcome, entityType: String) {
    queries.add(
      1,
      Attributes.builder().put(OUTCOME, outcome.label).put(ENTITY_TYPE, entityType).build(),
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
    /** HTTP 500: any other failure, including a malformed request. */
    ERROR("error"),
  }

  companion object {
    /** [Outcome.label] of the query. */
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    /**
     * Meta node level the request's entities are queried at (`campaign`, `adset`, `ad` or
     * `account`), `mixed` for several levels, `unsupported` for an unrecognized `entity_type`, or
     * `unknown` when the request could not be read.
     */
    val ENTITY_TYPE: AttributeKey<String> = AttributeKey.stringKey("entity_type")
  }
}
