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

  /** [MetaInsightsClient] that returns a fixed count or throws, and records the campaign IDs. */
  private class FakeMetaInsightsClient(
    private val result: Long = 0L,
    private val throwable: Throwable? = null,
  ) : MetaInsightsClient {
    var lastCampaignIds: List<String>? = null
      private set

    override fun queryImpressions(
      campaignIds: List<String>,
      timeInterval: Interval,
      demographics: MetaDemographicFilter,
    ): Long {
      lastCampaignIds = campaignIds
      throwable?.let { throw it }
      return result
    }
  }

  @Test
  fun `returns the impression count and maps entity_id to campaign id for an unfiltered query`() {
    val fake = FakeMetaInsightsClient(result = 12_345L)

    val response =
      MetaImpressionQueryFunction(fake).handle(request(campaignIds = listOf("111", "222")))

    assertThat(response.requestId).isEqualTo(REQUEST_ID)
    assertThat(response.hasResult()).isTrue()
    assertThat(response.result.value).isEqualTo(12_345L)
    assertThat(fake.lastCampaignIds).containsExactly("111", "222").inOrder()
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED for a non-empty filter`() {
    val response =
      MetaImpressionQueryFunction(FakeMetaInsightsClient(result = 1L))
        .handle(request(filter = "person.age_group == 1", campaignIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  @Test
  fun `skips with ENTITY_NOT_FOUND when the client cannot find the campaign`() {
    val response =
      MetaImpressionQueryFunction(
          FakeMetaInsightsClient(throwable = MetaEntityNotFoundException("nope"))
        )
        .handle(request(campaignIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.ENTITY_NOT_FOUND)
  }

  @Test
  fun `skips with API_ERROR on a Marketing API failure`() {
    val response =
      MetaImpressionQueryFunction(FakeMetaInsightsClient(throwable = MetaApiException("boom")))
        .handle(request(campaignIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.API_ERROR)
  }

  private fun request(filter: String = "", campaignIds: List<String>) =
    dataProviderImpressionQueryRequest {
      requestId = REQUEST_ID
      dataProvider = "dataProviders/edp-meta"
      query = impressionQuery {
        for (id in campaignIds) {
          entityKeys += entityKey {
            entityType = "campaign"
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
