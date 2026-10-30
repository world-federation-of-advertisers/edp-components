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
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.time.Duration
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupKt.entityKey
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpc
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.GetEventGroupRequest
import org.wfanet.measurement.api.v2alpha.eventGroup
import org.wfanet.measurement.api.v2alpha.eventGroupMetadata

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

  private fun newResolver() =
    CmmsEventGroupAdAccountResolver(EventGroupsGrpc.newBlockingStub(channel), Duration.ofSeconds(5))

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
  fun `adAccountIdOf returns the account without its act_ prefix`() {
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("act_999")))

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
    assertThat(requestedNames).containsExactly(EVENT_GROUP_NAME)
  }

  @Test
  fun `adAccountIdOf caches the account`() {
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("999")))
    val resolver = newResolver()

    resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
    val second = resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)

    assertThat(second).isEqualTo("999")
    assertThat(requestedNames).hasSize(1)
  }

  @Test
  fun `adAccountIdOf does not serve a cached account for another entity key`() {
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("999")))
    val resolver = newResolver()
    resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)

    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, OTHER_ENTITY_KEY)).isNull()
    assertThat(requestedNames).hasSize(2)
  }

  @Test
  fun `adAccountIdOf returns null when the EventGroup has a different entity key`() {
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("999")))

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, OTHER_ENTITY_KEY)).isNull()
  }

  @Test
  fun `adAccountIdOf returns null when the EventGroup names no account`() {
    served = metaEventGroup(emptyMap())

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
  }

  @Test
  fun `adAccountIdOf returns null when the account is not a string`() {
    served = metaEventGroup(mapOf("ad_account_id" to value { numberValue = 999.0 }))

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
  }

  @Test
  fun `adAccountIdOf returns null and does not cache an empty account`() {
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("act_")))
    val resolver = newResolver()

    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("act_999")))
    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
    assertThat(requestedNames).hasSize(2)
  }

  @Test
  fun `adAccountIdOf throws when the lookup fails and looks up again later`() {
    val resolver = newResolver()

    val exception =
      assertFailsWith<StatusRuntimeException> {
        resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.NOT_FOUND)
    served = metaEventGroup(mapOf("ad_account_id" to stringValue("999")))
    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
    assertThat(requestedNames).hasSize(2)
  }

  companion object {
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
