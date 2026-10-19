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
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
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

@RunWith(JUnit4::class)
class MetaAccountAccessCheckFunctionTest {
  private val metricReader = InMemoryMetricReader.create()
  private val meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build()
  private val metrics = MetaAccountAccessMetrics(meterProvider.get("test"))
  private var flushes = 0

  /** Answers each account from [outcomes], throwing it when the outcome is a [Throwable]. */
  private class FakeAccessChecker(private val outcomes: Map<String, Any>) :
    MetaAdAccountAccessChecker {
    val checked = mutableListOf<String>()

    override fun checkAdAccountAccess(adAccountId: String): MetaAdAccountAccess {
      checked.add(adAccountId)
      return when (val outcome = outcomes.getValue(adAccountId)) {
        is Throwable -> throw outcome
        else -> outcome as MetaAdAccountAccess
      }
    }
  }

  /** Minimal [HttpRequest] for a scheduler trigger; its body is ignored. */
  private class FakeHttpRequest : HttpRequest {
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getReader(): BufferedReader = getInputStream().bufferedReader()

    override fun getMethod(): String = "POST"

    override fun getUri(): String = "/"

    override fun getPath(): String = "/"

    override fun getQuery(): Optional<String> = Optional.empty()

    override fun getQueryParameters(): Map<String, List<String>> = emptyMap()

    override fun getParts(): Map<String, HttpRequest.HttpPart> = emptyMap()

    override fun getContentType(): Optional<String> = Optional.empty()

    override fun getContentLength(): Long = 0

    override fun getCharacterEncoding(): Optional<String> = Optional.empty()

    override fun getHeaders(): Map<String, List<String>> = emptyMap()
  }

  /** Minimal [HttpResponse] capturing the status. */
  private class FakeHttpResponse : HttpResponse {
    var statusCode: Int? = null
      private set

    private val output = ByteArrayOutputStream()
    private val writer = output.bufferedWriter()

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

    override fun getOutputStream(): OutputStream = output

    override fun getWriter(): BufferedWriter = writer
  }

  private fun run(checker: MetaAdAccountAccessChecker, adAccountIds: List<String>): Int? {
    val function = MetaAccountAccessCheckFunction(checker, adAccountIds, metrics) { flushes++ }
    val response = FakeHttpResponse()
    function.service(FakeHttpRequest(), response)
    return response.statusCode
  }

  /** Check counts recorded, by attribute set. */
  private fun recordedChecks(): Map<Attributes, Long> =
    metricReader
      .collectAllMetrics()
      .filter { it.name == "meta.account_access_checks_total" }
      .flatMap { it.longSumData.points }
      .associate { point: LongPointData -> point.attributes to point.value }

  private fun attributes(
    accountId: String,
    result: String,
    errorCode: Long? = null,
    errorSubcode: Long? = null,
  ): Attributes {
    val builder =
      Attributes.builder()
        .put(MetaAccountAccessMetrics.ACCOUNT_ID, accountId)
        .put(MetaAccountAccessMetrics.RESULT, result)
    errorCode?.let { builder.put(MetaAccountAccessMetrics.ERROR_CODE, it) }
    errorSubcode?.let { builder.put(MetaAccountAccessMetrics.ERROR_SUBCODE, it) }
    return builder.build()
  }

  @Test
  fun `service records one check per account and responds 200`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null),
          "act_222" to
            MetaAdAccountAccess(MetaAdAccountAccess.Result.PERMISSION_DENIED, 200L, 1815066L),
          "333" to MetaAdAccountAccess(MetaAdAccountAccess.Result.INACCESSIBLE, 100L, 33L),
        )
      )

    val status = run(checker, listOf("111", "act_222", "333"))

    assertThat(status).isEqualTo(200)
    assertThat(checker.checked).containsExactly("111", "act_222", "333").inOrder()
    assertThat(recordedChecks())
      .containsExactly(
        attributes("111", "enabled"),
        1L,
        attributes("222", "permission_denied", 200L, 1815066L),
        1L,
        attributes("333", "inaccessible", 100L, 33L),
        1L,
      )
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service checks the remaining accounts when one fails and responds 500`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaApiException("unexpected"),
          "222" to MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null),
        )
      )

    val status = run(checker, listOf("111", "222"))

    assertThat(status).isEqualTo(500)
    assertThat(recordedChecks()).containsExactly(attributes("222", "enabled"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service stops and responds 502 when Meta rejects the credentials`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAuthException("rejected"),
          "222" to MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null),
        )
      )

    val status = run(checker, listOf("111", "222"))

    assertThat(status).isEqualTo(502)
    assertThat(checker.checked).containsExactly("111")
    assertThat(recordedChecks()).isEmpty()
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service stops and responds 429 when Meta throttles`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null),
          "222" to MetaRateLimitException("throttled"),
          "333" to MetaAdAccountAccess(MetaAdAccountAccess.Result.ENABLED, null, null),
        )
      )

    val status = run(checker, listOf("111", "222", "333"))

    assertThat(status).isEqualTo(429)
    assertThat(checker.checked).containsExactly("111", "222").inOrder()
    assertThat(recordedChecks()).containsExactly(attributes("111", "enabled"), 1L)
    assertThat(flushes).isEqualTo(1)
  }
}
