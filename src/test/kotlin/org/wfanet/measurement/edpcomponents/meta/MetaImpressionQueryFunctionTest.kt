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

import com.google.cloud.functions.HttpRequest
import com.google.cloud.functions.HttpResponse
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.timestamp
import com.google.type.Interval
import com.google.type.interval
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Optional
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse.SkipDetail.SkipReason
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entityKey
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.eventFilter
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.impressionQuery

@RunWith(JUnit4::class)
class MetaImpressionQueryFunctionTest {
  private val metricReader = InMemoryMetricReader.create()
  private val metrics =
    MetaImpressionQueryMetrics(
      SdkMeterProvider.builder().registerMetricReader(metricReader).build().get("test")
    )
  private var flushes = 0

  private fun newFunction(insightsClient: MetaInsightsClient) =
    MetaImpressionQueryFunction(insightsClient, metrics) { flushes++ }

  /** Query counts recorded, by (outcome, entity type). */
  private fun recordedQueries(): Map<Pair<String?, String?>, Long> =
    metricReader
      .collectAllMetrics()
      .filter { it.name == "meta.impression_query.queries" }
      .flatMap { it.longSumData.points }
      .associate { point ->
        Pair(
          point.attributes.get(MetaImpressionQueryMetrics.OUTCOME),
          point.attributes.get(MetaImpressionQueryMetrics.ENTITY_TYPE),
        ) to point.value
      }

  /** Points of `meta.impression_query.inaccessible_entities`, by attribute set. */
  private fun recordedInaccessibleEntities(): Map<Attributes, Long> =
    metricReader
      .collectAllMetrics()
      .filter { it.name == "meta.impression_query.inaccessible_entities" }
      .flatMap { it.longSumData.points }
      .associate { point -> point.attributes to point.value }

  private fun serve(insightsClient: MetaInsightsClient, body: ByteArray): FakeHttpResponse {
    val httpResponse = FakeHttpResponse()
    newFunction(insightsClient).service(FakeHttpRequest(body), httpResponse)
    return httpResponse
  }

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

  /** Minimal [HttpRequest] exposing a fixed binary body; unused accessors return empty defaults. */
  private class FakeHttpRequest(private val body: ByteArray) : HttpRequest {
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)

    override fun getReader(): BufferedReader = ByteArrayInputStream(body).bufferedReader()

    override fun getMethod(): String = "POST"

    override fun getUri(): String = "/"

    override fun getPath(): String = "/"

    override fun getQuery(): Optional<String> = Optional.empty()

    override fun getQueryParameters(): Map<String, List<String>> = emptyMap()

    override fun getParts(): Map<String, HttpRequest.HttpPart> = emptyMap()

    override fun getContentType(): Optional<String> = Optional.of("application/x-protobuf")

    override fun getContentLength(): Long = body.size.toLong()

    override fun getCharacterEncoding(): Optional<String> = Optional.empty()

    override fun getHeaders(): Map<String, List<String>> = emptyMap()
  }

  /** Minimal [HttpResponse] capturing status, content type, and written bytes. */
  private class FakeHttpResponse : HttpResponse {
    var statusCode: Int? = null
      private set

    var contentTypeValue: String? = null
      private set

    private val output = ByteArrayOutputStream()

    val body: ByteArray
      get() = output.toByteArray()

    override fun setStatusCode(code: Int) {
      statusCode = code
    }

    override fun setStatusCode(code: Int, message: String) {
      statusCode = code
    }

    override fun setContentType(contentType: String) {
      contentTypeValue = contentType
    }

    override fun getContentType(): Optional<String> = Optional.ofNullable(contentTypeValue)

    override fun appendHeader(header: String, value: String) {}

    override fun getHeaders(): Map<String, List<String>> = emptyMap()

    override fun getOutputStream(): OutputStream = output

    override fun getWriter(): BufferedWriter = output.bufferedWriter()
  }

  private data class RoutingCase(
    val entityType: String,
    val expectedNodeId: String,
    val expectedLevel: String,
  )

  @Test
  fun `service parses the request, sets the protobuf content type, and writes the response bytes`() {
    val fake = FakeMetaInsightsClient(result = 999L)
    val httpResponse = FakeHttpResponse()

    newFunction(fake)
      .service(FakeHttpRequest(request(entityIds = listOf("111")).toByteArray()), httpResponse)

    assertThat(httpResponse.contentTypeValue).isEqualTo("application/x-protobuf")
    val parsed = DataProviderImpressionQueryResponse.parseFrom(httpResponse.body)
    assertThat(parsed.requestId).isEqualTo(REQUEST_ID)
    assertThat(parsed.result.value).isEqualTo(999L)
  }

  @Test
  fun `service returns 500 on a malformed request body`() {
    val httpResponse = FakeHttpResponse()

    // First byte 0x6E ('n') decodes to an invalid protobuf wire type, so parseFrom throws.
    newFunction(FakeMetaInsightsClient())
      .service(FakeHttpRequest("not a valid proto".toByteArray()), httpResponse)

    assertThat(httpResponse.statusCode).isEqualTo(500)
  }

  @Test
  fun `service returns 429 when Meta throttles`() {
    // A skip would tell the caller we looked and found nothing; we could not look. A status makes
    // EdpValidationPostProcessor record http_429 rather than treating a transient throttle as an
    // ordinary skipped row.
    val httpResponse = FakeHttpResponse()

    newFunction(FakeMetaInsightsClient(throwable = MetaRateLimitException("throttled")))
      .service(FakeHttpRequest(request(entityIds = listOf("111")).toByteArray()), httpResponse)

    assertThat(httpResponse.statusCode).isEqualTo(429)
  }

  @Test
  fun `service returns 502 when Meta rejects the credentials`() {
    // Distinct from a throttle: waiting does not fix a revoked token, and as a skip it would
    // disable validation indefinitely without failing anything.
    val httpResponse = FakeHttpResponse()

    newFunction(FakeMetaInsightsClient(throwable = MetaAuthException("expired")))
      .service(FakeHttpRequest(request(entityIds = listOf("111")).toByteArray()), httpResponse)

    assertThat(httpResponse.statusCode).isEqualTo(502)
  }

  @Test
  fun `service returns 403 when Meta denies access to the ad account`() {
    // Distinct from a rejected token: one account missing its grant is routine during onboarding
    // and must not read as a credential outage.
    val httpResponse = FakeHttpResponse()

    newFunction(
        FakeMetaInsightsClient(throwable = MetaPermissionDeniedException("no access", "act_999"))
      )
      .service(FakeHttpRequest(request(entityIds = listOf("111")).toByteArray()), httpResponse)

    assertThat(httpResponse.statusCode).isEqualTo(403)
  }

  @Test
  fun `returns the impression count and routes campaign entities to campaign-level targets`() {
    val fake = FakeMetaInsightsClient(result = 12_345L)

    val response = newFunction(fake).handle(request(entityIds = listOf("111", "222")))

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
  fun `routes supported entity types to their Meta Insights level`() {
    // Accounts prefix "act_".
    val cases =
      listOf(
        RoutingCase("campaign", expectedNodeId = "111", expectedLevel = "campaign"),
        RoutingCase("ad", expectedNodeId = "111", expectedLevel = "ad"),
        RoutingCase("creative", expectedNodeId = "111", expectedLevel = "ad"),
        RoutingCase("ad_set", expectedNodeId = "111", expectedLevel = "adset"),
        RoutingCase("adset", expectedNodeId = "111", expectedLevel = "adset"),
        RoutingCase("account", expectedNodeId = "act_111", expectedLevel = "account"),
        RoutingCase("ad_account", expectedNodeId = "act_111", expectedLevel = "account"),
      )
    for ((entityType, expectedNodeId, expectedLevel) in cases) {
      val fake = FakeMetaInsightsClient(result = 1L)

      newFunction(fake).handle(request(entityType = entityType, entityIds = listOf("111")))

      assertThat(fake.lastTargets)
        .containsExactly(MetaInsightsTarget(nodeId = expectedNodeId, level = expectedLevel))
    }
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED for a non-empty filter`() {
    val response =
      newFunction(FakeMetaInsightsClient(result = 1L))
        .handle(request(filter = "person.age_group == 1", entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED for an unsupported entity_type without calling the client`() {
    val fake = FakeMetaInsightsClient(result = 1L)

    val response =
      newFunction(fake).handle(request(entityType = "playlist", entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
    assertThat(response.skipped.detail).contains("playlist")
    assertThat(fake.lastTargets).isNull()
  }

  @Test
  fun `skips with FILTER_NOT_SUPPORTED when the interval cannot be a Meta time_range`() {
    val response =
      newFunction(
          FakeMetaInsightsClient(throwable = MetaIntervalNotSupportedException("not day-aligned"))
        )
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  @Test
  fun `skips with ENTITY_NOT_FOUND when the client cannot find the entity`() {
    val response =
      newFunction(FakeMetaInsightsClient(throwable = MetaEntityNotFoundException("nope", "111")))
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.ENTITY_NOT_FOUND)
  }

  @Test
  fun `skips with API_ERROR on a Marketing API failure`() {
    val response =
      newFunction(FakeMetaInsightsClient(throwable = MetaApiException("boom")))
        .handle(request(entityIds = listOf("111")))

    assertThat(response.hasSkipped()).isTrue()
    assertThat(response.skipped.reason).isEqualTo(SkipReason.API_ERROR)
  }

  @Test
  fun `service records a successful query with its entity type`() {
    serve(FakeMetaInsightsClient(result = 1L), request(entityIds = listOf("111")).toByteArray())

    assertThat(recordedQueries()).containsExactly(Pair("success", "campaign"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records an entity Meta cannot find or the token cannot see`() {
    serve(
      FakeMetaInsightsClient(throwable = MetaEntityNotFoundException("nope", "111")),
      request(entityType = "ad_set", entityIds = listOf("111")).toByteArray(),
    )

    assertThat(recordedQueries()).containsExactly(Pair("entity_not_found", "adset"), 1L)
    assertThat(recordedInaccessibleEntities())
      .containsExactly(
        Attributes.builder()
          .put(MetaImpressionQueryMetrics.REASON, "entity_not_found")
          .put(MetaImpressionQueryMetrics.ENTITY_TYPE, "adset")
          .put(MetaImpressionQueryMetrics.NODE_ID, "111")
          .build(),
        1L,
      )
  }

  @Test
  fun `service records a query refused for lack of permission`() {
    val httpResponse =
      serve(
        FakeMetaInsightsClient(throwable = MetaPermissionDeniedException("no access", "act_999")),
        request(entityIds = listOf("111")).toByteArray(),
      )

    assertThat(httpResponse.statusCode).isEqualTo(403)
    assertThat(recordedQueries()).containsExactly(Pair("permission_denied", "campaign"), 1L)
    // The refused node is an ad account, so its ID is recorded too.
    assertThat(recordedInaccessibleEntities())
      .containsExactly(
        Attributes.builder()
          .put(MetaImpressionQueryMetrics.REASON, "permission_denied")
          .put(MetaImpressionQueryMetrics.ENTITY_TYPE, "campaign")
          .put(MetaImpressionQueryMetrics.NODE_ID, "act_999")
          .put(MetaImpressionQueryMetrics.ACCOUNT_ID, "999")
          .build(),
        1L,
      )
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records an unsupported entity type`() {
    serve(
      FakeMetaInsightsClient(result = 1L),
      request(entityType = "playlist", entityIds = listOf("111")).toByteArray(),
    )

    assertThat(recordedQueries()).containsExactly(Pair("filter_not_supported", "unsupported"), 1L)
  }

  @Test
  fun `service records a malformed request as an error of unknown entity type`() {
    serve(FakeMetaInsightsClient(), "not a valid proto".toByteArray())

    assertThat(recordedQueries()).containsExactly(Pair("error", "unknown"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records a throttled query`() {
    serve(
      FakeMetaInsightsClient(throwable = MetaRateLimitException("throttled")),
      request(entityIds = listOf("111")).toByteArray(),
    )

    assertThat(recordedQueries()).containsExactly(Pair("rate_limited", "campaign"), 1L)
    assertThat(recordedInaccessibleEntities()).isEmpty()
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records a query whose credentials Meta rejected`() {
    serve(
      FakeMetaInsightsClient(throwable = MetaAuthException("expired")),
      request(entityIds = listOf("111")).toByteArray(),
    )

    assertThat(recordedQueries()).containsExactly(Pair("auth_failed", "campaign"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records a Marketing API failure`() {
    serve(
      FakeMetaInsightsClient(throwable = MetaApiException("boom")),
      request(entityIds = listOf("111")).toByteArray(),
    )

    assertThat(recordedQueries()).containsExactly(Pair("api_error", "campaign"), 1L)
  }

  @Test
  fun `service records entities at several levels as mixed`() {
    val mixed =
      request(entityIds = listOf("111"))
        .toBuilder()
        .apply {
          queryBuilder.addEntityKeys(
            entityKey {
              entityType = "ad_set"
              entityId = "222"
            }
          )
        }
        .build()

    serve(FakeMetaInsightsClient(result = 1L), mixed.toByteArray())

    assertThat(recordedQueries()).containsExactly(Pair("success", "mixed"), 1L)
  }

  @Test
  fun `service records a request naming no entities as unknown`() {
    serve(FakeMetaInsightsClient(result = 1L), request(entityIds = emptyList()).toByteArray())

    assertThat(recordedQueries()).containsExactly(Pair("success", "unknown"), 1L)
  }

  @Test
  fun `service records an error when writing the response fails`() {
    val httpResponse = UnwritableHttpResponse()

    newFunction(FakeMetaInsightsClient(result = 1L))
      .service(FakeHttpRequest(request(entityIds = listOf("111")).toByteArray()), httpResponse)

    assertThat(httpResponse.statusCode).isEqualTo(500)
    assertThat(recordedQueries()).containsExactly(Pair("error", "campaign"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  /** [HttpResponse] whose body cannot be written; captures the status. */
  private class UnwritableHttpResponse : HttpResponse {
    var statusCode: Int? = null
      private set

    override fun setStatusCode(code: Int) {
      statusCode = code
    }

    override fun setStatusCode(code: Int, message: String) {
      statusCode = code
    }

    override fun setContentType(contentType: String) {}

    override fun getContentType(): Optional<String> = Optional.empty()

    override fun appendHeader(header: String, value: String) {}

    override fun getHeaders(): Map<String, List<String>> = emptyMap()

    override fun getOutputStream(): OutputStream = throw java.io.IOException("connection reset")

    override fun getWriter(): BufferedWriter = throw java.io.IOException("connection reset")
  }

  private fun request(
    filter: String = "",
    entityType: String = "campaign",
    entityIds: List<String>,
  ) = dataProviderImpressionQueryRequest {
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
