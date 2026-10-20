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

/** Reads the Meta Cloud Functions' configuration from their environment. */
internal object MetaEnvironment {
  /**
   * Builds the production Meta client with the Meta System User token and app secret.
   *
   * Both env vars are populated from Secret Manager at deploy time — no Kotlin changes needed — via
   * the Cloud Functions Gen 2 `--set-secrets` flag:
   * ```
   * gcloud functions deploy ... \
   *   --set-secrets=META_ACCESS_TOKEN=meta-access-token:latest,META_APP_SECRET=meta-app-secret:latest
   * ```
   *
   * https://cloud.google.com/functions/docs/configuring/secrets
   *
   * `META_GRAPH_API_BASE` optionally overrides the Graph API origin. Production leaves it unset;
   * the function-boundary tests point it at a fake Graph API.
   */
  fun marketingApiClient(): MetaMarketingApiInsightsClient {
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
    val graphApiBase = System.getenv("META_GRAPH_API_BASE")
    return if (graphApiBase == null) {
      MetaMarketingApiInsightsClient(accessToken = token, appSecret = appSecret)
    } else {
      MetaMarketingApiInsightsClient(
        accessToken = token,
        appSecret = appSecret,
        graphApiBase = graphApiBase,
      )
    }
  }

  /**
   * The ad accounts in `META_EXPECTED_AD_ACCOUNT_IDS`, a comma-separated list of IDs with or
   * without their `act_` prefix, normalized to bare IDs with duplicates removed.
   */
  fun expectedAdAccountIds(): List<String> {
    val value =
      System.getenv("META_EXPECTED_AD_ACCOUNT_IDS")
        ?: error("META_EXPECTED_AD_ACCOUNT_IDS not set (comma-separated ad account IDs)")
    val ids =
      value
        .split(",")
        .map { it.trim().removePrefix(ACCOUNT_PREFIX) }
        .filter { it.isNotEmpty() }
        .distinct()
    require(ids.isNotEmpty()) { "META_EXPECTED_AD_ACCOUNT_IDS lists no ad accounts" }
    return ids
  }

  private const val ACCOUNT_PREFIX = "act_"
}
