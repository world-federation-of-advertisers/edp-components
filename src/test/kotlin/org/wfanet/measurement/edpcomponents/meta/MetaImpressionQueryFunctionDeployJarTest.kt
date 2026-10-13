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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse.SkipDetail.SkipReason
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.entityKey
import org.wfanet.measurement.api.v2alpha.ImpressionQueryKt.eventFilter
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.impressionQuery
import org.wfanet.measurement.gcloud.testing.FunctionsFrameworkInvokerProcess

/**
 * Serves the deployable `MetaImpressionQueryFunction` JAR through a real Functions Framework
 * invoker, so CI catches a wrong entry point, a missing runtime dependency, a failing default
 * constructor, or a Functions Framework incompatibility.
 *
 * The request carries an unsupported filter, which the function answers before contacting Meta, so
 * dummy credentials suffice.
 */
@RunWith(JUnit4::class)
class MetaImpressionQueryFunctionDeployJarTest {
  private lateinit var functionProcess: FunctionsFrameworkInvokerProcess

  @After
  fun tearDown() {
    if (::functionProcess.isInitialized) {
      functionProcess.close()
    }
  }

  @Test
  fun `deploy JAR serves a binary-protobuf request through the Functions Framework`() {
    functionProcess =
      FunctionsFrameworkInvokerProcess(
        javaBinaryPath = FUNCTION_BINARY_PATH,
        classTarget = FUNCTION_TARGET,
      )
    val port = runBlocking {
      functionProcess.start(
        mapOf("META_ACCESS_TOKEN" to "dummy-token", "META_APP_SECRET" to "dummy-secret")
      )
    }

    val response =
      HttpClient.newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create("http://localhost:$port"))
            .header("Content-Type", PROTOBUF_CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(QUERY_REQUEST.toByteArray()))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(response.headers().firstValue("Content-Type")).hasValue(PROTOBUF_CONTENT_TYPE)
    val queryResponse = DataProviderImpressionQueryResponse.parseFrom(response.body())
    assertThat(queryResponse.requestId).isEqualTo(REQUEST_ID)
    assertThat(queryResponse.skipped.reason).isEqualTo(SkipReason.FILTER_NOT_SUPPORTED)
  }

  companion object {
    private val FUNCTION_BINARY_PATH =
      Paths.get(
        "_main",
        "src",
        "test",
        "kotlin",
        "org",
        "wfanet",
        "measurement",
        "edpcomponents",
        "meta",
        "InvokeMetaImpressionQueryFunction",
      )
    private const val FUNCTION_TARGET =
      "org.wfanet.measurement.edpcomponents.meta.MetaImpressionQueryFunction"
    private const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
    private const val REQUEST_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"

    private val QUERY_REQUEST = dataProviderImpressionQueryRequest {
      requestId = REQUEST_ID
      dataProvider = "dataProviders/edp-meta"
      query = impressionQuery {
        entityKeys += entityKey {
          entityType = "campaign"
          entityId = "111"
        }
        timeInterval = interval {
          startTime = timestamp { seconds = 1_700_000_000 }
          endTime = timestamp { seconds = 1_700_086_400 }
        }
        filter = eventFilter { expression = "person.age_group == 1" }
      }
    }
  }
}
