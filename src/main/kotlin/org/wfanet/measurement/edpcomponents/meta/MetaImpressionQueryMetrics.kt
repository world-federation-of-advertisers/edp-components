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
 * Metrics for [MetaImpressionQueryFunction]:
 * - `meta.impression_query.queries`, one per query, keyed by [OUTCOME] and [ENTITY_TYPE].
 * - `meta.impression_query.inaccessible_entities`, one per query Meta refused for lack of access,
 *   keyed by [REASON], [ENTITY_TYPE], [NODE_ID] and, when the refused node is an ad account,
 *   [ACCOUNT_ID]. Meta reports an ad account that has not been shared with us the same way as a
 *   missing one and does not name the account behind a hidden campaign, so the node is often the
 *   only identifier available. Unlike the hourly account check, this covers every entity a report
 *   asks about, including those in accounts no one listed.
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

  private val inaccessibleEntities: LongCounter =
    meter
      .counterBuilder("meta.impression_query.inaccessible_entities")
      .setDescription("Meta impression queries refused for lack of access, by Meta node")
      .setUnit("{query}")
      .build()

  /** Records one query with [outcome] for entities of [entityType]. */
  fun recordQuery(outcome: Outcome, entityType: String) {
    queries.add(1, Attributes.of(OUTCOME, outcome.label, ENTITY_TYPE, entityType))
  }

  /**
   * Records a query for entities of [entityType] that Meta refused for [reason] at Graph node
   * [nodeId].
   */
  fun recordInaccessibleEntity(reason: InaccessibleReason, entityType: String, nodeId: String) {
    val attributes =
      Attributes.builder()
        .put(REASON, reason.label)
        .put(ENTITY_TYPE, entityType)
        .put(NODE_ID, nodeId)
    if (nodeId.startsWith(ACCOUNT_PREFIX)) {
      attributes.put(ACCOUNT_ID, nodeId.removePrefix(ACCOUNT_PREFIX))
    }
    inaccessibleEntities.add(1, attributes.build())
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

  /** Why Meta refused a query for lack of access, with the label it is recorded under. */
  enum class InaccessibleReason(val label: String) {
    /** Error `code` 100 with subcode 33, or HTTP 404: missing or hidden from the token. */
    ENTITY_NOT_FOUND("entity_not_found"),
    /** Error `code` 10 or 200–299: visible, but the token has not been granted access. */
    PERMISSION_DENIED("permission_denied"),
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
    /** [InaccessibleReason.label] of a refused query. */
    val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
    /** Graph node Meta refused, such as a campaign ID or `act_<id>`. */
    val NODE_ID: AttributeKey<String> = AttributeKey.stringKey("node_id")
    /** The refused ad account, without its `act_` prefix, when [NODE_ID] is an ad account. */
    val ACCOUNT_ID: AttributeKey<String> = AttributeKey.stringKey("account_id")

    private const val ACCOUNT_PREFIX = "act_"
  }
}
