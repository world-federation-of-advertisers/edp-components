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
2. `MetaEntityLevels` — route each `entity_key` by `entity_type` to a Graph node ID and Insights
   `level`: `campaign`, `ad`/`creative` → `ad`, `ad_set`/`adset` → `adset`, `account`/`ad_account` →
   `account` (node prefixed `act_`). An unknown type skips the request.
3. `translateFilter` — unfiltered only for now (empty or `true` expression); everything else →
   `FILTER_NOT_SUPPORTED`. Age/gender breakdown translation is tracked as a TODO (see Status).
4. `MetaMarketingApiInsightsClient` — resolve the ad-account timezone, build the day-granular
   `time_range`, query Graph API Insights (`breakdowns=age,gender`), follow paging, sum matching
   buckets.
5. Return the count, or a skip reason.

## Auth

- Reporting Server → function: GCP OIDC ID token (handled upstream by `ValidationCloudFunctionClient`).
- Meta System User token: **Secret Manager** only; never reaches the Reporting Server.

## Building and deploying

There is no `main()`. The Cloud Functions Gen 2 runtime supplies the functions-framework server and
calls `MetaImpressionQueryFunction.service()` per request; the class is a handler, not a program.
The `--entry-point` flag below is how the runtime finds it, and it is **not validated at deploy
time** — a typo deploys successfully and then 500s on every request.

Build the uber jar:

```bash
bazel build //src/main/kotlin/org/wfanet/measurement/edpcomponents/meta:MetaImpressionQueryFunction_deploy.jar
```

`gcloud` uploads the whole `--source` **directory**, so stage the jar on its own:

```bash
STAGING="$(mktemp -d)"
cp bazel-bin/src/main/kotlin/org/wfanet/measurement/edpcomponents/meta/MetaImpressionQueryFunction_deploy.jar \
   "$STAGING/"

gcloud functions deploy meta-impression-query \
  --gen2 \
  --runtime=java17 \
  --entry-point=org.wfanet.measurement.edpcomponents.meta.MetaImpressionQueryFunction \
  --source="$STAGING" \
  --trigger-http \
  --no-allow-unauthenticated \
  --region=<region> \
  --run-service-account=<service-account-email> \
  --set-secrets=META_ACCESS_TOKEN=meta-access-token:latest,META_APP_SECRET=meta-app-secret:latest
```

`--no-allow-unauthenticated` is the entire auth story: Google rejects any caller without a valid
OIDC ID token before this code runs. Grant the Reporting Server's service account
`roles/run.invoker` on the deployed function, and grant the function's own service account
`roles/secretmanager.secretAccessor` on both secrets.

Smoke-test a deployment:

```bash
curl -X POST "$(gcloud functions describe meta-impression-query --gen2 --region=<region> --format='value(serviceConfig.uri)')" \
  -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  -H "Content-Type: application/x-protobuf" \
  --data-binary @request.pb --output response.pb
```

where `request.pb` is a serialized `DataProviderImpressionQueryRequest`. Note the interval must be
whole days in the ad account's timezone — see the alignment note above.

For a throwaway dev deployment, `--set-env-vars=META_ACCESS_TOKEN=...,META_APP_SECRET=...` avoids
provisioning Secret Manager. Never do this outside a disposable test project: the values are
visible in the function's configuration.

Managed deployment (Terraform, mirroring the `http-cloud-function` module used by the EDP
Aggregator functions) is not wired up yet.

## Status

Implemented: request/response handling, entity-type routing for all four Meta Insights levels,
Insights JSON parsing with paging, ad-account-timezone `time_range` conversion, `appsecret_proof` on
every request, and skip-reason mapping.

Open TODOs (tracked in code):
- **CEL → breakdown** translation for `age_group` + `gender` — only the unfiltered case is supported
  today; filtered queries return `FILTER_NOT_SUPPORTED`.
- **Async Insights** report-run path for large queries.
- **Terraform deploy config** — the `java_binary` exists (see above); managed deployment does not.
- **Sandbox integration test** — world-federation-of-advertisers/edp-components#3.
