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
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.EventGroup
import org.wfanet.measurement.api.v2alpha.EventGroupKt.entityKey
import org.wfanet.measurement.api.v2alpha.EventGroupMetadata
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.api.v2alpha.GetEventGroupRequest
import org.wfanet.measurement.api.v2alpha.eventGroup

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
    CmmsEventGroupAdAccountResolver(EventGroupsCoroutineStub(channel), Duration.ofSeconds(5))

  private fun metaEventGroup(adAccountId: Value?): EventGroup = eventGroup {
    name = EVENT_GROUP_NAME
    entityKey = ENTITY_KEY
    if (adAccountId != null) {
      eventGroupMetadata =
        EventGroupMetadata.newBuilder()
          .setEntityMetadata(Struct.newBuilder().putFields("ad_account_id", adAccountId))
          .build()
    }
  }

  @Test
  fun `adAccountIdOf returns the account without its act_ prefix`() {
    served = metaEventGroup(Value.newBuilder().setStringValue("act_999").build())

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
    assertThat(requestedNames).containsExactly(EVENT_GROUP_NAME)
  }

  @Test
  fun `adAccountIdOf caches the account`() {
    served = metaEventGroup(Value.newBuilder().setStringValue("999").build())
    val resolver = newResolver()

    resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)
    val second = resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)

    assertThat(second).isEqualTo("999")
    assertThat(requestedNames).hasSize(1)
  }

  @Test
  fun `adAccountIdOf returns null when the EventGroup has a different entity key`() {
    served = metaEventGroup(Value.newBuilder().setStringValue("999").build())
    val other = entityKey {
      entityType = "campaign"
      entityId = "222"
    }

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, other)).isNull()
  }

  @Test
  fun `adAccountIdOf returns null when the EventGroup names no account`() {
    served = metaEventGroup(null)

    assertThat(newResolver().adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
  }

  @Test
  fun `adAccountIdOf returns null and retries later when the lookup fails`() {
    val resolver = newResolver()

    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isNull()
    served = metaEventGroup(Value.newBuilder().setStringValue("999").build())
    assertThat(resolver.adAccountIdOf(EVENT_GROUP_NAME, ENTITY_KEY)).isEqualTo("999")
    assertThat(requestedNames).hasSize(2)
  }

  companion object {
    private const val EVENT_GROUP_NAME = "dataProviders/edp-meta/eventGroups/eg-111"
    private val ENTITY_KEY = entityKey {
      entityType = "campaign"
      entityId = "111"
    }
  }
}
