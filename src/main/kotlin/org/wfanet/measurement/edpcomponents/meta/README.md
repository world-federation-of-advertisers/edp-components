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
2. `resolveCampaignIds` — `event_group_reference_id` (the `entity_id`) → Meta campaign ID(s).
3. `translateFilter` — CEL `age_group`/`gender` → Meta `age`/`gender` breakdown buckets, or
   `FILTER_NOT_SUPPORTED`.
4. `MetaInsightsClient` — Graph API Insights query (`breakdowns=age,gender`), sum matching buckets.
5. Return the count, or a skip reason.

## Auth

- Reporting Server → function: GCP OIDC ID token (handled upstream by `ValidationCloudFunctionClient`).
- Meta System User token: **Secret Manager** only; never reaches the Reporting Server.

## Status — scaffold (not yet production)

Implemented: request/response handling, skip-reason mapping, the `MetaInsightsClient` seam, the Graph
API request skeleton.

Open TODOs (tracked in code):
- **`event_group_reference_id` → campaign ID** decode — needs the onboarding encoding (currently a
  pass-through).
- **CEL → breakdown** translation for `age_group` + `gender` — currently only the unfiltered case is
  supported; filtered queries return `FILTER_NOT_SUPPORTED`.
- **Insights JSON parsing** + paging, and a **JSON dependency** decision.
- **Secret Manager** token loading (currently an env-var fallback for local/testing).
- **Async Insights** report-run path for large queries (design "future work").
- **Bazel/deploy wiring** — `MODULE.bazel` deps and the Gen-2 deploy target need a build pass and
  reconciliation with edp-components' conventions.
