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
import com.google.protobuf.timestamp
import com.google.type.Interval
import com.google.type.interval
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse.SkipDetail.SkipReason
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entityKey
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.eventFilter
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.impressionQuery

@RunWith(JUnit4::class)
class MetaImpressionQueryFunctionTest {

  /** [MetaInsightsClient] that returns a fixed count or throws, and records the targets it saw. */
  private class FakeMetaInsightsClient(
    private val result: Long = 0L,
    private val throwable: Throwable? = null,
  ) : MetaInsightsClient {
    var lastTargets: List<MetaInsightsTarget>? = null
      private set

    override fun queryImpressions(
      targets: List<MetaInsightsTarget>,
      timeInterval: Interval,
      demographics: MetaDemographicFilter,
    ): Long {
      lastTargets = targets
      throwable?.let { throw it }
      return result
    }
  }

  @Test
  fun `returns the impression count and routes campaign entities to campaign-level targets`() {
    val fake = FakeMetaInsightsClient(result = 12_345L)

    val response =
      MetaImpressionQueryFunction(fake).handle(request(entityIds = listOf("111", "222")))

    assertThat(response.requestId).isEqualTo(REQUEST_ID)
    assertThat(response.hasResult()).isTrue()
    assertThat(response.result.value).isEqualTo(12_345L)
    assertThat(fake.lastTargets)
      .containsExactly(
        MetaInsightsTarget(nodeId = "111", level = "campaign"),
        MetaInsightsTarget(nodeId = "222", level = "campaign"),
      )
      .inOrder()
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED for a non-empty filter`() {
    val response =
      MetaImpressionQueryFunction(FakeMetaInsightsClient(result = 1L))
        .handle(request(filter = "person.age_group == 1", entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED for an unsupported entity_type without calling the client`() {
    val fake = FakeMetaInsightsClient(result = 1L)

    val response =
      MetaImpressionQueryFunction(fake)
        .handle(request(entityType = "ad_set", entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
    assertThat(response.skipped.detail).contains("ad_set")
    assertThat(fake.lastTargets).isNull()
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED when the interval cannot be a Meta time_range`() {
    val response =
      MetaImpressionQueryFunction(
          FakeMetaInsightsClient(throwable = MetaIntervalNotSupportedException("not day-aligned"))
        )
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  @Test
  fun `skips with ENTITY_NOT_FOUND when the client cannot find the entity`() {
    val response =
      MetaImpressionQueryFunction(
          FakeMetaInsightsClient(throwable = MetaEntityNotFoundException("nope"))
        )
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.ENTITY_NOT_FOUND)
  }

  @Test
  fun `skips with API_ERROR on a Marketing API failure`() {
    val response =
      MetaImpressionQueryFunction(FakeMetaInsightsClient(throwable = MetaApiException("boom")))
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.API_ERROR)
  }

  private fun request(
    filter: String = "",
    entityType: String = "campaign",
    entityIds: List<String>,
  ) =
    dataProviderImpressionQueryRequest {
      requestId = REQUEST_ID
      dataProvider = "dataProviders/edp-meta"
      query = impressionQuery {
        for (id in entityIds) {
          entityKeys += entityKey {
            this.entityType = entityType
            entityId = id
          }
        }
        timeInterval = interval {
          startTime = timestamp { seconds = 1_700_000_000 }
          endTime = timestamp { seconds = 1_700_086_400 }
        }
        this.filter = eventFilter { expression = filter }
      }
    }

  companion object {
    private const val REQUEST_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }
}
