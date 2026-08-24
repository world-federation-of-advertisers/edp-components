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

/**
 * How Meta's Insights API is queried for one node level.
 *
 * Meta's Insights endpoint answers at four node levels — account, campaign, ad set, ad — each a
 * different [level] on `/{node-id}/insights`. [nodeIdPrefix] is prepended to the request's
 * `entity_id` to form the Graph node ID; only ad accounts need one (`act_`).
 */
data class MetaEntityLevel(val level: String, val nodeIdPrefix: String = "")

/**
 * Supported request `entity_type` strings → the Meta node level each is queried at.
 *
 * A type may have aliases (e.g. `"ad_set"` and `"adset"`) since the taxonomy string is chosen
 * per-`DataProvider`. An `entity_type` absent from this map is unsupported and the request is
 * skipped with `FILTER_NOT_SUPPORTED` (see [MetaImpressionQueryFunction]). Support a new type by
 * adding an entry here.
 */
object MetaEntityLevels {
  private val BY_ENTITY_TYPE: Map<String, MetaEntityLevel> =
    mapOf(
      "campaign" to MetaEntityLevel(level = "campaign"),
      "ad" to MetaEntityLevel(level = "ad"),
      // Meta's leaf ad node is what CMM calls a creative.
      "creative" to MetaEntityLevel(level = "ad"),
      "ad_set" to MetaEntityLevel(level = "adset"),
      "adset" to MetaEntityLevel(level = "adset"),
      "account" to MetaEntityLevel(level = "account", nodeIdPrefix = "act_"),
      "ad_account" to MetaEntityLevel(level = "account", nodeIdPrefix = "act_"),
    )

  /** The node level for [entityType], or null if the type is unsupported. */
  operator fun get(entityType: String): MetaEntityLevel? = BY_ENTITY_TYPE[entityType]
}
