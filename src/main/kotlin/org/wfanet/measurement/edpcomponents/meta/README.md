# Meta Impression-Query Cloud Function

Meta's implementation of the `DataProviderImpressionQuery` contract for Halo EDP impression-count
validation. It is a **dumb publisher-API adapter** (per the EDP Impression Count Validation design,
§7): it answers "how many impressions did Meta record for these entities, over this interval,
matching this filter?" — nothing more. All comparison/verdict logic lives in Results Fulfiller's
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

- Results Fulfiller → function: GCP OIDC ID token (handled upstream by `ValidationCloudFunctionClient`).
- Meta System User token: **Secret Manager** only; never reaches Results Fulfiller.

## Testing against live Meta

`MetaMarketingApiInsightsClientRealTest` runs the client against the real Marketing API. It is
tagged `manual`, so `bazel test //...` never picks it up, and it self-skips when its environment
variables are unset.

**The interval must be whole days in the ad account's own timezone.** Meta answers only
account-midnight-aligned day ranges, so a UTC-midnight window on a non-UTC account is rejected
before any request is sent (see
[#4](https://github.com/world-federation-of-advertisers/edp-components/issues/4)). Look the account
timezone up first:

```bash
PROOF=$(printf '%s' "$META_ACCESS_TOKEN" | openssl dgst -sha256 -hmac "$META_APP_SECRET" | sed 's/^.*= *//')

# campaign -> account
curl -sG "https://graph.facebook.com/v25.0/<CAMPAIGN_ID>" \
  --data-urlencode "fields=account_id" \
  --data-urlencode "access_token=$META_ACCESS_TOKEN" \
  --data-urlencode "appsecret_proof=$PROOF"

# account -> timezone
curl -sG "https://graph.facebook.com/v25.0/act_<ACCOUNT_ID>" \
  --data-urlencode "fields=timezone_name" \
  --data-urlencode "access_token=$META_ACCESS_TOKEN" \
  --data-urlencode "appsecret_proof=$PROOF"
```

Then convert local midnights to epoch seconds in that zone:

```bash
python3 - <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo
tz = ZoneInfo("America/New_York")   # from the call above
start = datetime(2026, 5, 13, 0, 0, tzinfo=tz)
end   = datetime(2026, 6,  1, 0, 0, tzinfo=tz)   # exclusive; Meta's `until` is inclusive
print(int(start.timestamp()), int(end.timestamp()))
PY
```

| Variable | Required | Meaning |
| --- | --- | --- |
| `META_ACCESS_TOKEN` | yes | System User token with `ads_read` on the account |
| `META_APP_SECRET` | yes | App secret, for `appsecret_proof` |
| `META_TEST_ENTITY_ID` | yes | Campaign / ad / ad set / account ID |
| `META_TEST_ENTITY_TYPE` | no | Defaults to `campaign` |
| `META_TEST_START_EPOCH_SECONDS` | yes | Interval start — local midnight in the account's zone |
| `META_TEST_END_EPOCH_SECONDS` | yes | Interval end, exclusive — local midnight |
| `META_TEST_EXPECTED_IMPRESSIONS` | no | When set, the count must equal it exactly |

It can also be run from CI by dispatching the **Live Meta test** workflow against a GitHub
environment holding `META_ACCESS_TOKEN` and `META_APP_SECRET` as secrets, and the target as a
`META_TEST_CONFIG_CONTENT` variable. That workflow is dispatch-only; it never runs on push or pull
request, and it validates every field before invoking Bazel, because the test skips rather than
fails when one is missing.

```json
{
  "entity_id": "<CAMPAIGN_ID>",
  "entity_type": "campaign",
  "start_epoch_seconds": 1778644800,
  "end_epoch_seconds": 1780286400,
  "expected_impressions": 1234
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `entity_id` | string | Campaign / ad / ad set / account ID |
| `entity_type` | string | One of `campaign`, `ad`, `creative`, `ad_set`, `adset`, `account`, `ad_account` |
| `start_epoch_seconds` | integer | Interval start — local midnight in the account's timezone |
| `end_epoch_seconds` | integer | Interval end, exclusive — local midnight, after the start |
| `expected_impressions` | integer | The count the query must return exactly |

Locally:

```bash
bazel test \
  //src/test/kotlin/org/wfanet/measurement/edpcomponents/meta:MetaMarketingApiInsightsClientRealTest \
  --test_env=META_ACCESS_TOKEN \
  --test_env=META_APP_SECRET \
  --test_env=META_TEST_ENTITY_ID --test_env=META_TEST_ENTITY_TYPE \
  --test_env=META_TEST_START_EPOCH_SECONDS \
  --test_env=META_TEST_END_EPOCH_SECONDS \
  --test_output=all
```

Prefer a window that closed at least a month ago: Meta's impression figures can continue to move
for some weeks after delivery, so recent windows are a poor basis for an exact assertion.

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
  --timeout=25s \
  --run-service-account=<service-account-email> \
  --set-secrets=META_ACCESS_TOKEN=meta-access-token:latest,META_APP_SECRET=meta-app-secret:latest
```

`--timeout` must stay below the caller's deadline. `EdpValidationPostProcessor` stops waiting
after 30 seconds, so relying on the platform default leaves this function running and consuming
Meta quota after the caller has already abandoned the response.

`--no-allow-unauthenticated` is the entire auth story: Google rejects any caller without a valid
OIDC ID token before this code runs. Grant the Results Fulfiller service account
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
