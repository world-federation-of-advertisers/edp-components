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

/**
 * Builds the production Meta client with the Meta System User token and app secret, shared by the
 * Meta Cloud Functions.
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
 * `META_GRAPH_API_BASE` optionally overrides the Graph API origin. Production leaves it unset; the
 * function-boundary tests point it at a fake Graph API.
 */
internal fun metaMarketingApiClientFromEnvironment(): MetaMarketingApiInsightsClient {
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
