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

import com.google.cloud.functions.HttpFunction
import com.google.cloud.functions.HttpRequest
import com.google.cloud.functions.HttpResponse
import java.util.logging.Level
import java.util.logging.Logger
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryRequest
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponse.SkipDetail.SkipReason
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponseKt.impressionCount
import org.wfanet.measurement.api.v2alpha.DataProviderImpressionQueryResponseKt.skipDetail
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryResponse

/**
 * Meta's implementation of the `DataProviderImpressionQuery` cloud function (Cloud Functions Gen
 * 2).
 *
 * A **dumb publisher-API adapter** (design doc §7): it parses a
 * [DataProviderImpressionQueryRequest] (binary proto), routes each entity key to a Meta Insights
 * target by its `entity_type` (via [MetaEntityModules]), translates the CEL filter to a Meta
 * demographic breakdown, queries the Marketing API Insights endpoint for the raw impression count
 * over the interval, and returns a [DataProviderImpressionQueryResponse]. It performs no
 * comparison, no verdict, and no callback — that all lives in the Reporting Server's
 * `EdpValidationPostProcessor`.
 *
 * The Reporting Server authenticates to this function with a GCP OIDC ID token (handled by the
 * platform / the `ValidationCloudFunctionClient`); Meta credentials live only in this function's
 * environment (Secret Manager) and never reach the Reporting Server.
 */
class MetaImpressionQueryFunction(
  private val insightsClient: MetaInsightsClient = defaultInsightsClient()
) : HttpFunction {

  override fun service(request: HttpRequest, response: HttpResponse) {
    val queryRequest = request.inputStream.use { DataProviderImpressionQueryRequest.parseFrom(it) }
    val queryResponse = handle(queryRequest)
    response.setContentType(PROTOBUF_CONTENT_TYPE)
    response.outputStream.use { queryResponse.writeTo(it) }
  }

  /** Request → response, free of servlet types so it can be unit-tested directly. */
  fun handle(request: DataProviderImpressionQueryRequest): DataProviderImpressionQueryResponse {
    val demographics =
      translateFilter(request.query.filter.expression)
        ?: return skip(
          request.requestId,
          SkipReason.FILTER_NOT_SUPPORTED,
          "CEL filter cannot be expressed via Meta age/gender breakdowns: " +
            request.query.filter.expression,
        )

    // Route each entity to its Meta Insights target by entity_type. An unsupported type skips the
    // whole request: a partial sum over only the supported entities would be a wrong number, not a
    // valid smaller one.
    val targets = ArrayList<MetaInsightsTarget>(request.query.entityKeysList.size)
    for (entityKey in request.query.entityKeysList) {
      val module =
        MetaEntityModules[entityKey.entityType]
          ?: return skip(
            request.requestId,
            SkipReason.FILTER_NOT_SUPPORTED,
            "unsupported entity_type: ${entityKey.entityType}",
          )
      targets += MetaInsightsTarget(module.nodeId(entityKey.entityId), module.level)
    }

    return try {
      val count = insightsClient.queryImpressions(targets, request.query.timeInterval, demographics)
      dataProviderImpressionQueryResponse {
        requestId = request.requestId
        result = impressionCount { value = count }
      }
    } catch (e: MetaIntervalNotSupportedException) {
      skip(
        request.requestId,
        SkipReason.FILTER_NOT_SUPPORTED,
        e.message ?: "interval not supported",
      )
    } catch (e: MetaEntityNotFoundException) {
      skip(request.requestId, SkipReason.ENTITY_NOT_FOUND, e.message ?: "entity not found")
    } catch (e: MetaApiException) {
      logger.log(Level.WARNING, "Marketing API error for request ${request.requestId}", e)
      skip(request.requestId, SkipReason.API_ERROR, e.message ?: "Marketing API error")
    }
  }

  /**
   * Translates the CEL [expression] to a Meta demographic breakdown, or returns null if it cannot
   * be expressed (→ `FILTER_NOT_SUPPORTED`). An empty expression — or the trivially-true `true`
   * tautology — means "no filter" (all impressions).
   *
   * TODO(@jojijacob): Parse `age_group` + `gender` predicates into [MetaDemographicFilter]. CMM age
   *   groups are unions of Meta's fixed brackets, so map each covered group to its brackets; a
   *   boundary off Meta's bracket edges, or any non-demographic predicate, → null (unsupported).
   *   Until implemented, only the unfiltered case is handled; filtered queries return
   *   FILTER_NOT_SUPPORTED, which the post-processor treats as a skip (safe, not a false failure).
   */
  private fun translateFilter(expression: String): MetaDemographicFilter? =
    if (expression.isEmpty() || expression.trim() == "true") MetaDemographicFilter.UNFILTERED
    else null

  private fun skip(requestId: String, reason: SkipReason, detail: String) =
    dataProviderImpressionQueryResponse {
      this.requestId = requestId
      skipped = skipDetail {
        this.reason = reason
        this.detail = detail
      }
    }

  companion object {
    private val logger = Logger.getLogger(MetaImpressionQueryFunction::class.java.name)
    private const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"

    /**
     * Builds the production client with the Meta System User token and app secret.
     *
     * Both env vars are populated from Secret Manager at deploy time — no Kotlin changes needed —
     * via the Cloud Functions Gen 2 `--set-secrets` flag:
     * ```
     * gcloud functions deploy ... \
     *   --set-secrets=META_ACCESS_TOKEN=meta-access-token:latest,META_APP_SECRET=meta-app-secret:latest
     * ```
     *
     * https://cloud.google.com/functions/docs/configuring/secrets
     */
    private fun defaultInsightsClient(): MetaInsightsClient {
      val token =
        System.getenv("META_ACCESS_TOKEN")
          ?: error(
            "META_ACCESS_TOKEN not set (populated from Secret Manager at deploy via --set-secrets)"
          )
      val appSecret =
        System.getenv("META_APP_SECRET")
          ?: error(
            "META_APP_SECRET not set (populated from Secret Manager at deploy via --set-secrets)"
          )
      return MetaMarketingApiInsightsClient(accessToken = token, appSecret = appSecret)
    }
  }
}
