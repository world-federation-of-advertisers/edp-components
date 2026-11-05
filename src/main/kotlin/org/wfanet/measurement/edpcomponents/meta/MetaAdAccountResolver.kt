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

import com.google.protobuf.Value
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpc.EventGroupsBlockingStub
import org.wfanet.measurement.api.v2alpha.getEventGroupRequest

/** Resolves the Meta ad account that owns an entity from the entity's `EventGroup`. */
fun interface MetaAdAccountResolver {
  /**
   * Returns the ad account ID recorded in the `EventGroup` named [eventGroupName] for the entity
   * with [entityKey], as recorded, or null if that `EventGroup` names no account or is for another
   * entity. [MetaImpressionQueryMetrics.recordAccountAccess] drops Meta's `act_` prefix.
   *
   * @throws io.grpc.StatusRuntimeException if the lookup fails
   */
  fun adAccountIdOf(eventGroupName: String, entityKey: EventGroup.EntityKey): String?
}

/**
 * [MetaAdAccountResolver] that looks each `EventGroup` up with the CMMS `GetEventGroup` method and
 * reads the ad account from the string at [adAccountIdKey] in its `entity_metadata`, whose keys the
 * `DataProvider` defines. An `EventGroup` whose entity key does not match the requested one is
 * ignored. Each `EventGroup` that names no account is logged. Resolved accounts are cached for the
 * life of the instance; anything else is looked up again on the next request.
 */
class CmmsEventGroupAdAccountResolver(
  private val eventGroupsStub: EventGroupsBlockingStub,
  private val adAccountIdKey: String,
  private val requestTimeout: Duration,
) : MetaAdAccountResolver {
  /** Resolved ad account ID by the `EventGroup` and entity it was resolved for. */
  private val adAccountIdByEventGroup = ConcurrentHashMap<CacheKey, String>()

  override fun adAccountIdOf(eventGroupName: String, entityKey: EventGroup.EntityKey): String? {
    val cacheKey = CacheKey(eventGroupName, entityKey)
    val cached: String? = adAccountIdByEventGroup[cacheKey]
    if (cached != null) {
      return cached
    }
    val eventGroup: EventGroup =
      eventGroupsStub
        .withDeadlineAfter(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .getEventGroup(getEventGroupRequest { name = eventGroupName })
    if (eventGroup.entityKey != entityKey) {
      logger.warning(
        "EventGroup $eventGroupName has entity key ${eventGroup.entityKey}, not the requested " +
          "$entityKey"
      )
      return null
    }
    val adAccountId =
      eventGroup.eventGroupMetadata.entityMetadata.fieldsMap[adAccountIdKey]
        ?.takeIf { it.kindCase == Value.KindCase.STRING_VALUE }
        ?.stringValue
        ?.ifEmpty { null }
    if (adAccountId == null) {
      logger.warning(
        "EventGroup $eventGroupName has no ad account: its entity_metadata has no non-empty " +
          "string at \"$adAccountIdKey\""
      )
      return null
    }
    adAccountIdByEventGroup[cacheKey] = adAccountId
    return adAccountId
  }

  private data class CacheKey(val eventGroupName: String, val entityKey: EventGroup.EntityKey)

  companion object {
    private val logger = Logger.getLogger(CmmsEventGroupAdAccountResolver::class.java.name)
  }
}
