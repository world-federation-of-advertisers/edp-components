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
sealed interface MetaAdAccountAccess {
  /**
   * The credentials can read the account. This says nothing about Meta's `account_status`: a
   * disabled or closed account is still readable.
   */
  data object Accessible : MetaAdAccountAccess

  /**
   * Meta refused the read for [reason], with its error `code` and `error_subcode` when it supplied
   * them. Both are null for a bare HTTP 404.
   */
  data class Refused(val reason: Reason, val errorCode: Long?, val errorSubcode: Long?) :
    MetaAdAccountAccess

  /** Why Meta refused an ad account read. */
  enum class Reason {
    /** The token is valid but has not been granted the account: error `code` 10 or 200–299. */
    PERMISSION_DENIED,
    /**
     * The account does not exist or the token cannot reach it: error `code` 100 with subcode 33,
     * which Meta uses for both, or HTTP 404.
     */
    INACCESSIBLE,
  }
}
