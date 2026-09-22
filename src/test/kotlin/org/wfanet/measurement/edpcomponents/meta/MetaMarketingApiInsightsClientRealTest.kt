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
import com.google.type.interval
import java.time.Instant
import java.util.logging.Logger
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Exercises [MetaMarketingApiInsightsClient] against the **live** Meta Marketing API.
 *
 * Excluded from `bazel test //...` by the `manual` tag, and skipped via [assumeTrue] when the
 * required environment variables are absent, so it can never fail an unattended run. The meta
 * package README documents how to run it.
 *
 * Everything risky in the client — URL construction, `appsecret_proof`, the account-timezone
 * lookup, JSON parsing, paging, and bucket summing — is covered against a fake server in
 * [MetaMarketingApiInsightsClientTest]. This class exists to confirm those same paths against Meta
 * itself, which no automated test can do.
 *
 * The interval need not be day-aligned in the ad account's timezone: the client covers the interior
 * whole days with one query and each partial boundary day with an hourly one. A bound that is not
 * on a Meta bucket edge moves to the nearest one, so an exact assertion is only meaningful for an
 * interval whose bounds are already on bucket edges in that zone. `META_TEST_START_EPOCH_SECONDS`
 * and `META_TEST_END_EPOCH_SECONDS` are supplied directly rather than derived from dates, so the
 * caller controls that precisely.
 */
@RunWith(JUnit4::class)
class MetaMarketingApiInsightsClientRealTest {

  private val accessToken: String? = System.getenv("META_ACCESS_TOKEN")
  private val appSecret: String? = System.getenv("META_APP_SECRET")
  private val entityId: String? = System.getenv("META_TEST_ENTITY_ID")
  private val entityType: String = System.getenv("META_TEST_ENTITY_TYPE") ?: "campaign"
  private val startEpochSeconds: String? = System.getenv("META_TEST_START_EPOCH_SECONDS")
  private val endEpochSeconds: String? = System.getenv("META_TEST_END_EPOCH_SECONDS")

  /** Optional. When set, the returned count must equal it exactly. */
  private val expectedImpressions: String? = System.getenv("META_TEST_EXPECTED_IMPRESSIONS")

  @Before
  fun requireCredentialsAndTarget() {
    assumeTrue(
      "Skipping live Meta test. Set META_ACCESS_TOKEN, META_APP_SECRET, META_TEST_ENTITY_ID, " +
        "META_TEST_START_EPOCH_SECONDS, and META_TEST_END_EPOCH_SECONDS to run it.",
      !accessToken.isNullOrEmpty() &&
        !appSecret.isNullOrEmpty() &&
        !entityId.isNullOrEmpty() &&
        !startEpochSeconds.isNullOrEmpty() &&
        !endEpochSeconds.isNullOrEmpty(),
    )
  }

  @Test
  fun `queries live Meta and reconstructs the requested interval exactly`() {
    val entityLevel =
      checkNotNull(MetaEntityLevels[entityType]) {
        "META_TEST_ENTITY_TYPE '$entityType' is not a supported entity type"
      }
    val target =
      MetaInsightsTarget(nodeId = "${entityLevel.nodeIdPrefix}$entityId", level = entityLevel.level)
    val timeInterval = interval {
      startTime = timestamp { seconds = startEpochSeconds!!.toLong() }
      endTime = timestamp { seconds = endEpochSeconds!!.toLong() }
    }

    val client =
      MetaMarketingApiInsightsClient(accessToken = accessToken!!, appSecret = appSecret!!)

    val count: Long =
      client.queryImpressions(listOf(target), timeInterval, MetaDemographicFilter.UNFILTERED)

    // Logged so a manual run shows what was queried. The count itself is deliberately omitted:
    // an impression total for an identified entity is advertiser data, and this output can end up
    // in a CI log. Assert against META_TEST_EXPECTED_IMPRESSIONS instead of reading it back.
    logger.info(
      "Live Meta query: node=${target.nodeId} level=${target.level} " +
        "interval=[${Instant.ofEpochSecond(startEpochSeconds!!.toLong())}, " +
        "${Instant.ofEpochSecond(endEpochSeconds!!.toLong())})"
    )

    // A campaign with no delivery in the window legitimately returns 0, so only assert
    // non-negativity unless an expected total was supplied.
    assertThat(count).isAtLeast(0L)
    if (!expectedImpressions.isNullOrEmpty()) {
      assertThat(count).isEqualTo(expectedImpressions.toLong())
    }
  }

  companion object {
    private val logger: Logger =
      Logger.getLogger(MetaMarketingApiInsightsClientRealTest::class.java.name)
  }
}
