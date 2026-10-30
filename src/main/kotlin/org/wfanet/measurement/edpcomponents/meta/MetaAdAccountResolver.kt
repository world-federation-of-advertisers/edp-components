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
import io.grpc.StatusException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.api.v2alpha.getEventGroupRequest

/** Resolves the Meta ad account that owns an entity from the entity's `EventGroup`. */
fun interface MetaAdAccountResolver {
  /**
   * Returns the ad account ID, without its `act_` prefix, recorded in the `EventGroup` named
   * [eventGroupName] for the entity with [entityKey], or null if it cannot be determined.
   */
  fun adAccountIdOf(eventGroupName: String, entityKey: EventGroup.EntityKey): String?
}

/**
 * [MetaAdAccountResolver] that looks each `EventGroup` up with the CMMS `GetEventGroup` method and
 * reads the `ad_account_id` string from its `entity_metadata`. An `EventGroup` whose entity key
 * does not match the requested one is ignored. Accounts are cached for the life of the instance; a
 * failed lookup is retried on the next request.
 */
class CmmsEventGroupAdAccountResolver(
  private val eventGroupsStub: EventGroupsCoroutineStub,
  private val requestTimeout: Duration,
) : MetaAdAccountResolver {
  private val adAccountIdByEventGroup =
    ConcurrentHashMap<Pair<String, EventGroup.EntityKey>, String>()

  override fun adAccountIdOf(eventGroupName: String, entityKey: EventGroup.EntityKey): String? {
    val cacheKey = eventGroupName to entityKey
    adAccountIdByEventGroup[cacheKey]?.let {
      return it
    }
    val eventGroup: EventGroup =
      try {
        runBlocking {
          eventGroupsStub
            .withDeadlineAfter(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .getEventGroup(getEventGroupRequest { name = eventGroupName })
        }
      } catch (e: StatusException) {
        logger.log(Level.WARNING, "GetEventGroup failed for $eventGroupName", e)
        return null
      }
    if (eventGroup.entityKey != entityKey) {
      logger.warning(
        "EventGroup $eventGroupName has entity key ${eventGroup.entityKey}, not the requested " +
          "$entityKey"
      )
      return null
    }
    val adAccountId =
      eventGroup.eventGroupMetadata.entityMetadata.fieldsMap[AD_ACCOUNT_ID_FIELD]
        ?.takeIf { it.kindCase == Value.KindCase.STRING_VALUE }
        ?.stringValue
        ?.removePrefix(ACCOUNT_PREFIX)
        ?.ifEmpty { null } ?: return null
    adAccountIdByEventGroup[cacheKey] = adAccountId
    return adAccountId
  }

  companion object {
    private val logger = Logger.getLogger(CmmsEventGroupAdAccountResolver::class.java.name)
    // Key in a Meta EventGroup's `entity_metadata` naming the ad account that owns the entity.
    private const val AD_ACCOUNT_ID_FIELD = "ad_account_id"
    private const val ACCOUNT_PREFIX = "act_"
  }
}
