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
import com.google.common.truth.Truth.assertWithMessage
import com.google.protobuf.timestamp
import com.google.type.interval
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.logging.Logger
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse.SkipDetail.SkipReason
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entityKey
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.impressionQuery

/**
 * Exercises a **deployed** Cloud Function over HTTPS, end to end.
 *
 * [MetaMarketingApiInsightsClientRealTest] proves the client talks to Meta correctly. This proves
 * the layer above it: that the deployed function parses a binary-proto request off the wire,
 * authenticates the caller, runs the query, and serialises a response back. It is the only test
 * that covers the functions-framework entry point, OIDC enforcement, and secret injection, none of
 * which exist outside a real deployment.
 *
 * Tagged `manual` and skipped via [assumeTrue] when unconfigured, so it never runs in CI.
 *
 * The request is built here rather than by hand because the body is binary protobuf — there is
 * otherwise no practical way to produce one for `curl`. Setting `META_TEST_WRITE_REQUEST_TO` writes
 * the serialised bytes to a file so the same request can be replayed outside this test:
 * ```
 * curl -X POST "$URL" \
 *   -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
 *   -H "Content-Type: application/x-protobuf" \
 *   --data-binary @/tmp/request.pb --output /tmp/response.pb
 * ```
 *
 * **The interval must be whole days in the ad account's timezone.** A UTC-aligned window is
 * answered with a `FILTER_NOT_SUPPORTED` skip rather than a count — expected behaviour today (see
 * edp-components#4), and reported as such rather than as a failure.
 */
@RunWith(JUnit4::class)
class DeployedFunctionTest {

  private val functionUrl: String? = System.getenv("META_TEST_FUNCTION_URL")
  private val idToken: String? = System.getenv("META_TEST_ID_TOKEN")
  private val entityId: String? = System.getenv("META_TEST_ENTITY_ID")
  private val entityType: String = System.getenv("META_TEST_ENTITY_TYPE") ?: "campaign"
  private val startEpochSeconds: String? = System.getenv("META_TEST_START_EPOCH_SECONDS")
  private val endEpochSeconds: String? = System.getenv("META_TEST_END_EPOCH_SECONDS")
  private val dataProvider: String =
    System.getenv("META_TEST_DATA_PROVIDER") ?: "dataProviders/meta-test"

  /** Optional. When set, the returned count must equal it exactly. */
  private val expectedImpressions: String? = System.getenv("META_TEST_EXPECTED_IMPRESSIONS")

  /** Optional. Writes the serialised request here so it can be replayed with curl. */
  private val writeRequestTo: String? = System.getenv("META_TEST_WRITE_REQUEST_TO")

  @Before
  fun requireDeploymentAndTarget() {
    assumeTrue(
      "Skipping deployed-function test. Set META_TEST_FUNCTION_URL, META_TEST_ID_TOKEN, " +
        "META_TEST_ENTITY_ID, META_TEST_START_EPOCH_SECONDS and META_TEST_END_EPOCH_SECONDS.",
      !functionUrl.isNullOrEmpty() &&
        !idToken.isNullOrEmpty() &&
        !entityId.isNullOrEmpty() &&
        !startEpochSeconds.isNullOrEmpty() &&
        !endEpochSeconds.isNullOrEmpty(),
    )
  }

  @Test
  fun `deployed function rejects an unauthenticated request`() {
    // Without this, a function accidentally deployed with --allow-unauthenticated would still pass
    // the authenticated test below. Google enforces this before our code runs, so a 200 here means
    // the deployment is open to any caller.
    val response =
      HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
        .send(
          HttpRequest.newBuilder(URI.create(functionUrl!!))
            .header("Content-Type", CONTENT_TYPE_PROTOBUF)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(buildRequest().toByteArray()))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )

    logger.info("Unauthenticated request returned HTTP ${response.statusCode()}")
    assertWithMessage("deployment accepts unauthenticated callers")
      .that(response.statusCode())
      .isIn(listOf(401, 403))
  }

  @Test
  fun `deployed function answers an impression query over HTTPS`() {
    val request: DataProviderImpressionQueryRequest = buildRequest()
    if (!writeRequestTo.isNullOrEmpty()) {
      val bytes = request.toByteArray()
      Files.write(Path.of(writeRequestTo), bytes)
      logger.info("Wrote serialised request to $writeRequestTo (${bytes.size} bytes)")
    }

    val response: HttpResponse<ByteArray> =
      HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
        .send(
          HttpRequest.newBuilder(URI.create(functionUrl!!))
            // Mirrors ValidationCloudFunctionClient: an audience-scoped OIDC ID token, and a
            // binary-proto content type. Google rejects an unauthenticated call before our code
            // runs, so a 401/403 here means IAM, not application logic.
            .header("Authorization", "Bearer $idToken")
            .header("Content-Type", CONTENT_TYPE_PROTOBUF)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray()))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )

    logger.info("HTTP ${response.statusCode()} from $functionUrl")
    assertThat(response.statusCode()).isEqualTo(200)

    val queryResponse = DataProviderImpressionQueryResponse.parseFrom(response.body())
    assertThat(queryResponse.requestId).isEqualTo(request.requestId)

    when {
      queryResponse.hasResult() -> {
        val count = queryResponse.result.value
        logger.info("RESULT: impressions=$count")
        assertThat(count).isAtLeast(0L)
        if (!expectedImpressions.isNullOrEmpty()) {
          assertThat(count).isEqualTo(expectedImpressions.toLong())
        }
      }
      queryResponse.hasSkipped() -> {
        val skipped = queryResponse.skipped
        logger.info("SKIPPED: ${skipped.reason} — ${skipped.detail}")

        // Only the interval limitation is an acceptable skip for this request shape, and reaching
        // it still proves transport, OIDC and secret injection all worked. ENTITY_NOT_FOUND means
        // the configured entity is wrong and API_ERROR means the deployment cannot reach Meta —
        // either would otherwise let this test pass against an unusable function.
        assertWithMessage("deployment is not usable: ${skipped.detail}")
          .that(skipped.reason)
          .isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
        assertWithMessage("expected a count but the function skipped: ${skipped.detail}")
          .that(expectedImpressions.isNullOrEmpty())
          .isTrue()
      }
      else -> throw AssertionError("Response set neither result nor skipped: $queryResponse")
    }
  }

  private fun buildRequest(): DataProviderImpressionQueryRequest {
    val level =
      checkNotNull(MetaEntityLevels[entityType]) {
        "META_TEST_ENTITY_TYPE '$entityType' is not a supported entity type"
      }
    return dataProviderImpressionQueryRequest {
      // Deterministic per (entity, interval) so replaying the same request is idempotent from
      // Meta's perspective, matching how EdpValidationPostProcessor derives its request IDs.
      requestId =
        UUID.nameUUIDFromBytes(
            "$dataProvider|$entityType|$entityId|$startEpochSeconds|$endEpochSeconds"
              .toByteArray(Charsets.UTF_8)
          )
          .toString()
      this.dataProvider = dataProvider
      query = impressionQuery {
        entityKeys += entityKey {
          this.entityType = this@DeployedFunctionTest.entityType
          this.entityId = this@DeployedFunctionTest.entityId!!
        }
        timeInterval = interval {
          startTime = timestamp { seconds = startEpochSeconds!!.toLong() }
          endTime = timestamp { seconds = endEpochSeconds!!.toLong() }
        }
      }
    }
  }

  companion object {
    private val logger: Logger = Logger.getLogger(DeployedFunctionTest::class.java.name)

    private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    // EdpValidationPostProcessor's own deadline. A longer timeout here would let this test pass
    // for a deployment the real caller would already have abandoned.
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
  }
}
