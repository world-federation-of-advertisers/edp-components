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
import org.wfanet.measurement.api.v2alpha.ImpressionQuery
import org.wfanet.measurement.api.v2alpha.dataProviderImpressionQueryResponse

/**
 * Meta's implementation of the `DataProviderImpressionQuery` cloud function (Cloud Functions Gen
 * 2).
 *
 * A **dumb publisher-API adapter** (design doc §7): it parses a
 * [DataProviderImpressionQueryRequest] (binary proto), resolves the entity keys to Meta campaign
 * IDs, translates the CEL filter to a Meta demographic breakdown, queries the Marketing API
 * Insights endpoint for the raw impression count over the interval, and returns a
 * [DataProviderImpressionQueryResponse]. It performs no comparison, no verdict, and no callback —
 * that all lives in the Reporting Server's `EdpValidationPostProcessor`.
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

    val campaignIds = resolveCampaignIds(request.query.entityKeysList)
    return try {
      val count =
        insightsClient.queryImpressions(campaignIds, request.query.timeInterval, demographics)
      dataProviderImpressionQueryResponse {
        requestId = request.requestId
        result = impressionCount { value = count }
      }
    } catch (e: MetaEntityNotFoundException) {
      skip(request.requestId, SkipReason.ENTITY_NOT_FOUND, e.message ?: "entity not found")
    } catch (e: MetaApiException) {
      logger.log(Level.WARNING, "Marketing API error for request ${request.requestId}", e)
      skip(request.requestId, SkipReason.API_ERROR, e.message ?: "Marketing API error")
    }
  }

  /**
   * Resolves the request entity keys to Meta campaign IDs.
   *
   * For Meta, the `event_group_reference_id` set at onboarding *is* the Meta campaign ID, so the
   * `entity_id` maps 1:1 to a campaign.
   *
   * TODO(@jojijacob): Confirm a single `event_group_reference_id` never fans out to multiple Meta
   *   campaigns (a campaign group). If it can, decode the composite here and return all of them;
   *   the caller already sums impressions across the returned campaign IDs.
   */
  private fun resolveCampaignIds(entityKeys: List<ImpressionQuery.EntityKey>): List<String> =
    entityKeys.map { it.entityId }

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
     * Builds the production client with the Meta System User token.
     *
     * TODO(@jojijacob): Load the token and app secret from Secret Manager (resource names via env
     *   vars) instead of the process environment. The `META_ACCESS_TOKEN` / `META_APP_SECRET` env
     *   fallbacks are for local/testing only; credentials must never leave this function's
     *   environment.
     */
    private fun defaultInsightsClient(): MetaInsightsClient {
      val token =
        System.getenv("META_ACCESS_TOKEN")
          ?: error(
            "META_ACCESS_TOKEN not set (TODO: load the System User token from Secret Manager)"
          )
      val appSecret =
        System.getenv("META_APP_SECRET")
          ?: error("META_APP_SECRET not set (TODO: load the app secret from Secret Manager)")
      return MetaMarketingApiInsightsClient(accessToken = token, appSecret = appSecret)
    }
  }
}
