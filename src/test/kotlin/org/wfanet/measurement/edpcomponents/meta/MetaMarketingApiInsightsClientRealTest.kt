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
 * **The interval must be whole days in the ad account's own timezone.** Meta answers only
 * account-midnight-aligned day ranges, so a UTC-midnight window on a non-UTC account throws
 * [MetaIntervalNotSupportedException] before any request is sent. That is the behavior tracked in
 * edp-components#4; it is not a failure of this test. `META_TEST_START_EPOCH_SECONDS` and
 * `META_TEST_END_EPOCH_SECONDS` are therefore supplied directly rather than derived from dates.
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
  fun `queries live Meta for an account-day-aligned interval`() {
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

    // Logged so a manual run is useful even without an expected value to assert against.
    logger.info(
      "Live Meta query: node=${target.nodeId} level=${target.level} " +
        "interval=[${Instant.ofEpochSecond(startEpochSeconds!!.toLong())}, " +
        "${Instant.ofEpochSecond(endEpochSeconds!!.toLong())}) -> impressions=$count"
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
