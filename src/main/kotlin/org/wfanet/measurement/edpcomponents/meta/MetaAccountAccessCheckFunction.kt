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

/**
 * Checks whether the configured Meta credentials can read each expected ad account, run hourly by
 * Cloud Scheduler. Each check and each run is recorded in [MetaAccountAccessMetrics], so operators
 * can see per account which advertisers are onboarded and which still lack access, including
 * accounts that no report has queried yet. It is separate from [MetaImpressionQueryFunction] so
 * that validating a report makes no extra Meta calls.
 *
 * Responds 200 when every account was checked, whatever the results; 502 when Meta rejects the
 * credentials; 429 when Meta throttles; and 500 when any other check failed.
 */
class MetaAccountAccessCheckFunction(
  private val checker: MetaAdAccountAccessChecker,
  private val adAccountIds: List<String>,
  private val metrics: MetaAccountAccessMetrics,
  private val flushTelemetry: () -> Unit,
) : HttpFunction {
  init {
    require(adAccountIds.isNotEmpty()) { "adAccountIds must not be empty" }
  }

  /**
   * Builds the function from its environment, as the Functions Framework does: the Meta credentials
   * (see [MetaEnvironment.marketingApiClient]) and `META_EXPECTED_AD_ACCOUNT_IDS` (see
   * [MetaEnvironment.expectedAdAccountIds]).
   */
  constructor() :
    this(
      checker = MetaEnvironment.marketingApiClient(),
      adAccountIds = MetaEnvironment.expectedAdAccountIds(),
      metrics = MetaAccountAccessMetrics(MetaTelemetry.meter),
      flushTelemetry = { MetaTelemetry.flush() },
    )

  override fun service(request: HttpRequest, response: HttpResponse) {
    var outcome = MetaAccountAccessMetrics.RunOutcome.ERROR
    try {
      var failures = 0
      for (adAccountId in adAccountIds) {
        try {
          val access = checker.checkAdAccountAccess(adAccountId)
          metrics.recordCheck(adAccountId, access)
          if (access is MetaAdAccountAccess.Refused) {
            logger.warning(
              "Meta refused ad account $adAccountId: ${access.reason} " +
                "(code=${access.errorCode} subcode=${access.errorSubcode})"
            )
          }
        } catch (e: MetaAuthException) {
          throw e
        } catch (e: MetaRateLimitException) {
          throw e
        } catch (e: MetaApiException) {
          // One account's unexpected failure should not hide the others' state.
          failures++
          metrics.recordCheckError(adAccountId)
          logger.log(Level.WARNING, "Access check failed for Meta ad account $adAccountId", e)
        }
      }
      outcome =
        if (failures == 0) MetaAccountAccessMetrics.RunOutcome.SUCCESS
        else MetaAccountAccessMetrics.RunOutcome.ERROR
      response.setStatusCode(if (failures == 0) HTTP_OK else HTTP_INTERNAL_SERVER_ERROR)
      response.writer.write("Checked ${adAccountIds.size} ad accounts; $failures failed\n")
    } catch (e: MetaRateLimitException) {
      logger.log(Level.WARNING, "Meta throttled the ad account access checks", e)
      outcome = MetaAccountAccessMetrics.RunOutcome.RATE_LIMITED
      response.setStatusCode(HTTP_TOO_MANY_REQUESTS)
    } catch (e: MetaAuthException) {
      // Every account would fail identically, so stop rather than record per-account noise.
      logger.log(Level.SEVERE, "Meta rejected the configured credentials", e)
      outcome = MetaAccountAccessMetrics.RunOutcome.AUTH_FAILED
      response.setStatusCode(HTTP_BAD_GATEWAY)
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Ad account access checks failed", e)
      outcome = MetaAccountAccessMetrics.RunOutcome.ERROR
      response.setStatusCode(HTTP_INTERNAL_SERVER_ERROR)
    } finally {
      metrics.recordRun(outcome)
      flushTelemetry()
    }
  }

  companion object {
    private val logger = Logger.getLogger(MetaAccountAccessCheckFunction::class.java.name)

    private const val HTTP_OK = 200
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_INTERNAL_SERVER_ERROR = 500
    private const val HTTP_BAD_GATEWAY = 502
  }
}
