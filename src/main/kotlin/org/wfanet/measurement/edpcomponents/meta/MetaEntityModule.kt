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
 * says which level an `entity_id` refers to; a module turns that into the Graph node ID and `level`.
 *
 * Support for a new entity type is added by implementing this interface and registering the module
 * in [MetaEntityModules] — nothing else changes. An `entity_type` with no module is unsupported and
 * the request is skipped with `FILTER_NOT_SUPPORTED` (see [MetaImpressionQueryFunction]).
 */
interface MetaEntityModule {
  /** The request `entity_type` this module handles (e.g. "campaign"). */
  val entityType: String

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
  override val entityType: String = "campaign"
  override val level: String = "campaign"
}

/**
 * Registry of supported [MetaEntityModule]s keyed by `entity_type`.
 *
 * Only `campaign` is supported today — the only entity type currently onboarded. Enable another
 * level by adding its module to the list below, e.g.:
 * ```
 * object AdSetModule : MetaEntityModule { entityType = "ad_set"; level = "adset" }
 * object AdModule : MetaEntityModule { entityType = "ad"; level = "ad" }
 * object AccountModule : MetaEntityModule {
 *   entityType = "account"; level = "account"; fun nodeId(id) = "act_$id"
 * }
 * ```
 * Nothing else needs to change: the function routes on `entity_type` and the client uses [level] /
 * [nodeId] to build the request.
 */
object MetaEntityModules {
  private val byEntityType: Map<String, MetaEntityModule> =
    listOf(CampaignModule).associateBy { it.entityType }

  /** The module for [entityType], or null if the type is unsupported. */
  operator fun get(entityType: String): MetaEntityModule? = byEntityType[entityType]
}
