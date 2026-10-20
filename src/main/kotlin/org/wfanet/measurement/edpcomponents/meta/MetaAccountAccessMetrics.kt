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

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * Metrics for [MetaAccountAccessCheckFunction]:
 * - `meta.account_access.checks`, one per ad account checked, keyed by [ACCOUNT_ID] and [RESULT],
 *   plus [ERROR_CODE] and [ERROR_SUBCODE] when Meta refused the read. Grouping the latest points by
 *   account shows which expected accounts are accessible and which still lack access.
 * - `meta.account_access.runs`, one per invocation, keyed by [OUTCOME], so a run that could not
 *   check any account (rejected credentials, a throttle) is visible and alertable.
 *
 * The names and attributes are also used by the Meta impression-query dashboard in
 * cross-media-measurement (`meta_impression_query_dashboard.json`); change both together.
 */
class MetaAccountAccessMetrics(meter: Meter) {
  private val checks: LongCounter =
    meter
      .counterBuilder("meta.account_access.checks")
      .setDescription("Meta ad account access checks, by account and result")
      .setUnit("{check}")
      .build()

  private val runs: LongCounter =
    meter
      .counterBuilder("meta.account_access.runs")
      .setDescription("Meta ad account access check runs, by outcome")
      .setUnit("{run}")
      .build()

  /** Records one check of [adAccountId] with outcome [access]. */
  fun recordCheck(adAccountId: String, access: MetaAdAccountAccess) {
    val attributes = Attributes.builder().put(ACCOUNT_ID, adAccountId.removePrefix(ACCOUNT_PREFIX))
    when (access) {
      MetaAdAccountAccess.Accessible -> attributes.put(RESULT, ACCESSIBLE)
      is MetaAdAccountAccess.Refused -> {
        attributes.put(RESULT, labelOf(access.reason))
        if (access.errorCode != null) attributes.put(ERROR_CODE, access.errorCode)
        if (access.errorSubcode != null) attributes.put(ERROR_SUBCODE, access.errorSubcode)
      }
    }
    checks.add(1, attributes.build())
  }

  /** Records a check of [adAccountId] that failed for a reason unrelated to the account. */
  fun recordCheckError(adAccountId: String) {
    checks.add(
      1,
      Attributes.of(ACCOUNT_ID, adAccountId.removePrefix(ACCOUNT_PREFIX), RESULT, ERROR),
    )
  }

  /** Records one run with [outcome]. */
  fun recordRun(outcome: RunOutcome) {
    runs.add(1, Attributes.of(OUTCOME, outcome.label))
  }

  /** How a run ended, with the label it is recorded under. */
  enum class RunOutcome(val label: String) {
    /** Every account was checked, whatever the results. */
    SUCCESS("success"),
    /** Meta rejected the credentials, so no further account was checked. */
    AUTH_FAILED("auth_failed"),
    /** Meta throttled the run, so no further account was checked. */
    RATE_LIMITED("rate_limited"),
    /** At least one check, or the run itself, failed for another reason. */
    ERROR("error"),
  }

  companion object {
    /** The ad account checked, without its `act_` prefix. */
    val ACCOUNT_ID: AttributeKey<String> = AttributeKey.stringKey("account_id")
    /** `accessible`, `permission_denied`, `inaccessible` or `error`. */
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")
    /** Meta's error `code`, present only when Meta refused the read. */
    val ERROR_CODE: AttributeKey<Long> = AttributeKey.longKey("error_code")
    /** Meta's `error_subcode`, present only when Meta refused the read and supplied one. */
    val ERROR_SUBCODE: AttributeKey<Long> = AttributeKey.longKey("error_subcode")
    /** [RunOutcome.label] of a run. */
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")

    private const val ACCOUNT_PREFIX = "act_"
    private const val ACCESSIBLE = "accessible"
    private const val ERROR = "error"

    private fun labelOf(reason: MetaAdAccountAccess.Reason): String =
      when (reason) {
        MetaAdAccountAccess.Reason.PERMISSION_DENIED -> "permission_denied"
        MetaAdAccountAccess.Reason.INACCESSIBLE -> "inaccessible"
      }
  }
}
