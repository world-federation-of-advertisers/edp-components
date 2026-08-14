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
 * Maps a request `entity_type` to how Meta's Insights API is queried for that node level.
 *
 * Meta's Insights endpoint answers at four node levels — account, campaign, ad set, ad — each a
 * different `level` on `/{node-id}/insights`. The request's `ImpressionQuery.EntityKey.entity_type`
 * says which level an `entity_id` refers to; a module turns that into the Graph node ID and
 * `level`.
 *
 * Support for a new entity type is added by implementing this interface and registering the module
 * in [MetaEntityModules]. An `entity_type` with no module is unsupported and the request is skipped
 * with `FILTER_NOT_SUPPORTED` (see [MetaImpressionQueryFunction]).
 */
interface MetaEntityModule {
  /**
   * The request `entity_type` strings this module handles. A module may accept more than one (e.g.
   * `"ad_set"` and `"adset"`) since the taxonomy string is chosen per-`DataProvider`.
   */
  val entityTypes: Set<String>

  /** Meta Insights `level` parameter for this node level (e.g. "campaign"). */
  val level: String

  /**
   * The Meta Graph node ID for [entityId]. Identity for campaign/ad set/ad; ad accounts prefix
   * "act_".
   */
  fun nodeId(entityId: String): String = entityId
}

/** `campaign` → `/{campaign-id}/insights?level=campaign`. */
object CampaignModule : MetaEntityModule {
  override val entityTypes: Set<String> = setOf("campaign")
  override val level: String = "campaign"
}

/** `ad` / `creative` (Meta's leaf ad node) → `/{ad-id}/insights?level=ad`. */
object AdModule : MetaEntityModule {
  override val entityTypes: Set<String> = setOf("ad", "creative")
  override val level: String = "ad"
}

/** `ad_set` / `adset` → `/{adset-id}/insights?level=adset`. */
object AdSetModule : MetaEntityModule {
  override val entityTypes: Set<String> = setOf("ad_set", "adset")
  override val level: String = "adset"
}

/** `account` / `ad_account` → `/act_{account-id}/insights?level=account`. */
object AccountModule : MetaEntityModule {
  override val entityTypes: Set<String> = setOf("account", "ad_account")
  override val level: String = "account"

  override fun nodeId(entityId: String): String = "$ACCOUNT_NODE_PREFIX$entityId"

  private const val ACCOUNT_NODE_PREFIX = "act_"
}

/**
 * Registry of supported [MetaEntityModule]s keyed by every `entity_type` string they accept.
 *
 * Add a module to [MODULES] to support a new entity type; each module declares its own accepted
 * `entity_type` strings (including aliases). An `entity_type` with no module is unsupported.
 */
object MetaEntityModules {
  private val MODULES: List<MetaEntityModule> =
    listOf(CampaignModule, AdModule, AdSetModule, AccountModule)

  private val byEntityType: Map<String, MetaEntityModule> =
    MODULES.flatMap { module -> module.entityTypes.map { it to module } }.toMap()

  /** The module for [entityType], or null if the type is unsupported. */
  operator fun get(entityType: String): MetaEntityModule? = byEntityType[entityType]
}
