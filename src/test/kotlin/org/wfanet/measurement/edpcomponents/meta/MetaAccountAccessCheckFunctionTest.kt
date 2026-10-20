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
import kotlin.test.assertFailsWith
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

  /** Minimal [HttpResponse] capturing the status and the text written. */
  private class FakeHttpResponse : HttpResponse {
    var statusCode: Int? = null
      private set

    private val output = ByteArrayOutputStream()
    private val writer = output.bufferedWriter()

    val text: String
      get() {
        writer.flush()
        return output.toString(Charsets.UTF_8)
      }

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

  private fun run(
    checker: MetaAdAccountAccessChecker,
    adAccountIds: List<String>,
  ): FakeHttpResponse {
    val function = MetaAccountAccessCheckFunction(checker, adAccountIds, metrics) { flushes++ }
    val response = FakeHttpResponse()
    function.service(FakeHttpRequest(), response)
    return response
  }

  /** Points of the counter [name], by attribute set. */
  private fun recorded(name: String): Map<Attributes, Long> =
    metricReader
      .collectAllMetrics()
      .filter { it.name == name }
      .flatMap { it.longSumData.points }
      .associate { point: LongPointData -> point.attributes to point.value }

  private fun recordedChecks() = recorded("meta.account_access.checks")

  private fun recordedRuns() = recorded("meta.account_access.runs")

  private fun check(
    accountId: String,
    result: String,
    errorCode: Long? = null,
    errorSubcode: Long? = null,
  ): Attributes {
    val builder =
      Attributes.builder()
        .put(MetaAccountAccessMetrics.ACCOUNT_ID, accountId)
        .put(MetaAccountAccessMetrics.RESULT, result)
    if (errorCode != null) builder.put(MetaAccountAccessMetrics.ERROR_CODE, errorCode)
    if (errorSubcode != null) builder.put(MetaAccountAccessMetrics.ERROR_SUBCODE, errorSubcode)
    return builder.build()
  }

  private fun run(outcome: String): Attributes =
    Attributes.of(MetaAccountAccessMetrics.OUTCOME, outcome)

  @Test
  fun `service records one check per account and responds 200`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAdAccountAccess.Accessible,
          "act_222" to
            MetaAdAccountAccess.Refused(
              MetaAdAccountAccess.Reason.PERMISSION_DENIED,
              200L,
              1815066L,
            ),
          "333" to MetaAdAccountAccess.Refused(MetaAdAccountAccess.Reason.INACCESSIBLE, 100L, 33L),
          "444" to MetaAdAccountAccess.Refused(MetaAdAccountAccess.Reason.INACCESSIBLE, null, null),
        )
      )

    val response = run(checker, listOf("111", "act_222", "333", "444"))

    assertThat(response.statusCode).isEqualTo(200)
    assertThat(response.text).isEqualTo("Checked 4 ad accounts; 0 failed\n")
    assertThat(checker.checked).containsExactly("111", "act_222", "333", "444").inOrder()
    assertThat(recordedChecks())
      .containsExactly(
        check("111", "accessible"),
        1L,
        check("222", "permission_denied", 200L, 1815066L),
        1L,
        check("333", "inaccessible", 100L, 33L),
        1L,
        check("444", "inaccessible"),
        1L,
      )
    assertThat(recordedRuns()).containsExactly(run("success"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service records an account whose check fails and responds 500`() {
    val checker =
      FakeAccessChecker(
        mapOf("111" to MetaApiException("unexpected"), "222" to MetaAdAccountAccess.Accessible)
      )

    val response = run(checker, listOf("111", "222"))

    assertThat(response.statusCode).isEqualTo(500)
    assertThat(response.text).isEqualTo("Checked 2 ad accounts; 1 failed\n")
    assertThat(recordedChecks())
      .containsExactly(check("111", "error"), 1L, check("222", "accessible"), 1L)
    assertThat(recordedRuns()).containsExactly(run("error"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service stops and responds 502 when Meta rejects the credentials`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAdAccountAccess.Accessible,
          "222" to MetaAuthException("rejected"),
          "333" to MetaAdAccountAccess.Accessible,
        )
      )

    val response = run(checker, listOf("111", "222", "333"))

    assertThat(response.statusCode).isEqualTo(502)
    assertThat(checker.checked).containsExactly("111", "222").inOrder()
    assertThat(recordedChecks()).containsExactly(check("111", "accessible"), 1L)
    assertThat(recordedRuns()).containsExactly(run("auth_failed"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service stops and responds 429 when Meta throttles`() {
    val checker =
      FakeAccessChecker(
        mapOf(
          "111" to MetaAdAccountAccess.Accessible,
          "222" to MetaRateLimitException("throttled"),
          "333" to MetaAdAccountAccess.Accessible,
        )
      )

    val response = run(checker, listOf("111", "222", "333"))

    assertThat(response.statusCode).isEqualTo(429)
    assertThat(checker.checked).containsExactly("111", "222").inOrder()
    assertThat(recordedChecks()).containsExactly(check("111", "accessible"), 1L)
    assertThat(recordedRuns()).containsExactly(run("rate_limited"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `service responds 500 and still flushes on an unexpected exception`() {
    val checker = FakeAccessChecker(mapOf("111" to IllegalStateException("bug")))

    val response = run(checker, listOf("111"))

    assertThat(response.statusCode).isEqualTo(500)
    assertThat(recordedChecks()).isEmpty()
    assertThat(recordedRuns()).containsExactly(run("error"), 1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `constructor rejects an empty account list`() {
    assertFailsWith<IllegalArgumentException> {
      MetaAccountAccessCheckFunction(FakeAccessChecker(emptyMap()), emptyList(), metrics) {}
    }
  }
}
