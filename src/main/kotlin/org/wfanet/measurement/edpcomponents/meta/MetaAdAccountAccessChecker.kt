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

/** Checks whether the configured Meta credentials can read an ad account. */
interface MetaAdAccountAccessChecker {
  /**
   * Returns whether the credentials can read the ad account [adAccountId], given with or without
   * its `act_` prefix.
   *
   * @throws MetaAuthException if Meta rejects the credentials themselves.
   * @throws MetaRateLimitException if Meta throttles the request.
   * @throws MetaApiException for any other Marketing API failure.
   */
  fun checkAdAccountAccess(adAccountId: String): MetaAdAccountAccess
}

/** Outcome of one ad account access check. */
data class MetaAdAccountAccess(
  val result: Result,
  /** Meta's error `code` when the check was refused, otherwise null. */
  val errorCode: Long?,
  /** Meta's `error_subcode` when the check was refused and Meta supplied one, otherwise null. */
  val errorSubcode: Long?,
) {
  /** The access state of an ad account, with the label it is recorded under. */
  enum class Result(val label: String) {
    /** The credentials can read the account. */
    ENABLED("enabled"),
    /** The token is valid but has not been granted the account: error `code` 10 or 200–299. */
    PERMISSION_DENIED("permission_denied"),
    /**
     * The account does not exist or the token cannot reach it: error `code` 100 with subcode 33,
     * which Meta uses for both, or HTTP 404.
     */
    INACCESSIBLE("inaccessible"),
  }
}
