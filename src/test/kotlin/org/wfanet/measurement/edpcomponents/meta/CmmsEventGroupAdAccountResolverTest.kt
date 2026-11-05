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

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Value
import com.google.protobuf.struct
import com.google.protobuf.value
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupKt.entityKey
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.api.v2alpha.GetEventGroupRequest
import org.wfanet.measurement.api.v2alpha.eventGroup
import org.wfanet.measurement.api.v2alpha.eventGroupMetadata
import org.wfanet.measurement.common.throttler.Throttler

@RunWith(JUnit4::class)
class CmmsEventGroupAdAccountResolverTest {
  private val requestedNames = mutableListOf<String>()
  private var served: EventGroup? = null

  private val service =
    object : EventGroupsCoroutineImplBase() {
      override suspend fun getEventGroup(request: GetEventGroupRequest): EventGroup {
        requestedNames += request.name
        return served ?: throw StatusException(Status.NOT_FOUND)
      }
    }

  private lateinit var server: Server
  private lateinit var channel: ManagedChannel

  @Before
  fun startServer() {
    val name = InProcessServerBuilder.generateName()
    server =
      InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
    channel = InProcessChannelBuilder.forName(name).directExecutor().build()
  }

  @After
  fun stopServer() {
    channel.shutdownNow()
    server.shutdownNow()
  }

  /** Counts the calls that wait on it, and lets each through. */
  private val throttler =
    object : Throttler {
      var calls = 0

      override suspend fun <T> onReady(block: suspend () -> T): T {
        calls++
        return block()
      }
    }

  private fun newResolver(adAccountIdKey: String) =
    CmmsEventGroupAdAccountResolver(
      EventGroupsCoroutineStub(channel),
      throttler,
      adAccountIdKey,
      Duration.ofSeconds(5),
    )

  /** A Meta `EventGroup` for [ENTITY_KEY] whose `entity_metadata` has [fields]. */
  private fun metaEventGroup(fields: Map<String, Value>): EventGroup = eventGroup {
    name = EVENT_GROUP_NAME
    entityKey = ENTITY_KEY
    eventGroupMetadata = eventGroupMetadata {
      entityMetadata = struct { this.fields.putAll(fields) }
    }
  }

  private fun stringValue(text: String) = value { stringValue = text }

  @Test
  fun `adAccountIdOf returns the account as recorded`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("act_999")))

      assertThat(newResolver(AD_ACCOUNT_ID_KEY).adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY))
        .isEqualTo("act_999")
      assertThat(requestedNames).containsExactly(EVENT_GROUP_NAME)
    }

  @Test
  fun `adAccountIdOf reads the account at the configured key`() =
    runBlocking<Unit> {
      served =
        metaEventGroup(
          mapOf(AD_ACCOUNT_ID_KEY to stringValue("999"), "meta_account" to stringValue("888"))
        )

      assertThat(newResolver("meta_account").adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY))
        .isEqualTo("888")
    }

  @Test
  fun `adAccountIdOf caches the account`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))
      val resolver = newResolver(AD_ACCOUNT_ID_KEY)

      resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
      val second = resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)

      assertThat(second).isEqualTo("999")
      assertThat(requestedNames).hasSize(1)
    }

  @Test
  fun `adAccountIdOf does not serve a cached account for another entity key`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))
      val resolver = newResolver(AD_ACCOUNT_ID_KEY)
      resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)

      assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, OTHER_ENTITY_KEY)).isNull()
      assertThat(requestedNames).hasSize(2)
    }

  @Test
  fun `adAccountIdOf returns null when the EventGroup has a different entity key`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))

      assertThat(newResolver(AD_ACCOUNT_ID_KEY).adAccountIdOf(EVENT_GROUP_NAME, OTHER_ENTITY_KEY))
        .isNull()
    }

  @Test
  fun `adAccountIdOf returns null when the EventGroup names no account`() =
    runBlocking<Unit> {
      served = metaEventGroup(emptyMap())

      assertThat(newResolver(AD_ACCOUNT_ID_KEY).adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY))
        .isNull()
    }

  @Test
  fun `adAccountIdOf returns null when the account is not a string`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to value { numberValue = 999.0 }))

      assertThat(newResolver(AD_ACCOUNT_ID_KEY).adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY))
        .isNull()
    }

  @Test
  fun `adAccountIdOf returns null and does not cache an empty account`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("")))
      val resolver = newResolver(AD_ACCOUNT_ID_KEY)

      assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))
      assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
      assertThat(requestedNames).hasSize(2)
    }

  @Test
  fun `adAccountIdOf throws when the lookup fails and looks up again later`() =
    runBlocking<Unit> {
      val resolver = newResolver(AD_ACCOUNT_ID_KEY)

      val exception =
        assertFailsWith<StatusException> { resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY) }
      assertThat(exception.status.code).isEqualTo(Status.Code.NOT_FOUND)
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))
      assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
      assertThat(requestedNames).hasSize(2)
    }

  @Test
  fun `adAccountIdOf waits on the throttler for each lookup but not for a cached account`() =
    runBlocking<Unit> {
      served = metaEventGroup(mapOf(AD_ACCOUNT_ID_KEY to stringValue("999")))
      val resolver = newResolver(AD_ACCOUNT_ID_KEY)

      resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
      resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
      resolver.adAccountIdOf(EVENT_GROUP_NAME, OTHER_ENTITY_KEY)

      assertThat(requestedNames).hasSize(2)
      assertThat(throttler.calls).isEqualTo(2)
    }

  companion object {
    private const val AD_ACCOUNT_ID_KEY = "ad_account_id"
    private const val EVENT_GROUP_NAME = "dataProviders/edp-meta/eventGroups/eg-111"
    private val ENTITY_KEY = entityKey {
      entityType = "campaign"
      entityId = "111"
    }
    private val OTHER_ENTITY_KEY = entityKey {
      entityType = "campaign"
      entityId = "222"
    }
  }
}
