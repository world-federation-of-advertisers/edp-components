# Meta Impression-Query Cloud Function

Meta's implementation of the `DataProviderImpressionQuery` contract for Halo EDP impression-count
validation. It is a **dumb publisher-API adapter** (per the EDP Impression Count Validation design,
§7): it answers "how many impressions did Meta record for these entities, over this interval,
matching this filter?" — nothing more. All comparison/verdict logic lives in the Reporting Server's
`EdpValidationPostProcessor` (cross-media-measurement `#3962`).

## Contract

- **Request** `DataProviderImpressionQueryRequest` (binary proto): `request_id`, `data_provider`,
  `query { entity_keys[], time_interval, filter.expression (CEL) }`.
- **Response** `DataProviderImpressionQueryResponse`: `request_id` + `oneof { result (ImpressionCount)
  | skipped (SkipDetail{reason, detail}) }`.
- **Skip reasons**: `FILTER_NOT_SUPPORTED`, `ENTITY_NOT_FOUND`, `API_ERROR`.

Proto source: `cross-media-measurement-api`
`src/main/proto/wfa/measurement/api/v2alpha/data_provider_impression_query.proto` (pulled via the
Bazel registry).

## Flow

1. Parse the binary-proto request.
2. `MetaEntityModules` — route each `entity_key` by `entity_type` to a Graph node ID and Insights
   `level`: `campaign`, `ad`/`creative` → `ad`, `ad_set`/`adset` → `adset`, `account`/`ad_account` →
   `account` (node prefixed `act_`). An unknown type skips the request.
3. `translateFilter` — CEL `age_group`/`gender` → Meta `age`/`gender` breakdown buckets, or
   `FILTER_NOT_SUPPORTED`.
4. `MetaMarketingApiInsightsClient` — resolve the ad-account timezone, build the day-granular
   `time_range`, query Graph API Insights (`breakdowns=age,gender`), follow paging, sum matching
   buckets.
5. Return the count, or a skip reason.

## Auth

- Reporting Server → function: GCP OIDC ID token (handled upstream by `ValidationCloudFunctionClient`).
- Meta System User token: **Secret Manager** only; never reaches the Reporting Server.

## Status

Implemented: request/response handling, entity-type routing for all four Meta Insights levels,
Insights JSON parsing with paging, ad-account-timezone `time_range` conversion, `appsecret_proof` on
every request, and skip-reason mapping.

Open TODOs (tracked in code):
- **CEL → breakdown** translation for `age_group` + `gender` — only the unfiltered case is supported
  today; filtered queries return `FILTER_NOT_SUPPORTED`.
- **Async Insights** report-run path for large queries.
- **Gen-2 deploy target** — `java_binary` + container image.
- **Sandbox integration test** — world-federation-of-advertisers/edp-components#3.
