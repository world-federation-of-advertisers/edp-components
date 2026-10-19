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
 * Records ad account access checks as `meta.account_access_checks_total`, keyed by [ACCOUNT_ID] and
 * [RESULT], plus [ERROR_CODE] and [ERROR_SUBCODE] when Meta refused the check. Grouping the latest
 * points by account shows which expected accounts are enabled and which still lack access.
 */
class MetaAccountAccessMetrics(meter: Meter) {
  private val checks: LongCounter =
    meter
      .counterBuilder("meta.account_access_checks_total")
      .setDescription("Meta ad account access checks, by account and result")
      .setUnit("{check}")
      .build()

  /** Records one check of [adAccountId] with outcome [access]. */
  fun record(adAccountId: String, access: MetaAdAccountAccess) {
    val attributes =
      Attributes.builder()
        .put(ACCOUNT_ID, adAccountId.removePrefix(ACCOUNT_PREFIX))
        .put(RESULT, access.result.label)
    access.errorCode?.let { attributes.put(ERROR_CODE, it) }
    access.errorSubcode?.let { attributes.put(ERROR_SUBCODE, it) }
    checks.add(1, attributes.build())
  }

  companion object {
    /** The ad account checked, without its `act_` prefix. */
    val ACCOUNT_ID: AttributeKey<String> = AttributeKey.stringKey("account_id")
    /** [MetaAdAccountAccess.Result.label] of the check. */
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")
    /** Meta's error `code`, present only when Meta refused the check. */
    val ERROR_CODE: AttributeKey<Long> = AttributeKey.longKey("error_code")
    /** Meta's `error_subcode`, present only when Meta refused the check and supplied one. */
    val ERROR_SUBCODE: AttributeKey<Long> = AttributeKey.longKey("error_subcode")

    private const val ACCOUNT_PREFIX = "act_"
  }
}
