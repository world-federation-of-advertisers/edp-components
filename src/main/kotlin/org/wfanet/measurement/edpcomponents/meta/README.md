# Meta Impression-Query Cloud Function

Meta's implementation of the `DataProviderImpressionQuery` contract for Halo EDP impression-count
validation. It is a **dumb publisher-API adapter** (per the EDP Impression Count Validation design,
§7): it answers "how many impressions did Meta record for these entities, over this interval,
matching this filter?" — nothing more. All comparison/verdict logic lives in Results Fulfiller's
`EdpValidationPostProcessor` (cross-media-measurement `#3962`).

## Contract

- **Request** `DataProviderImpressionQueryRequest` (binary proto): `request_id`, `data_provider`,
  `query { entities[] { entity_key, event_group }, time_interval, filter.expression (CEL) }`.
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
4. `MetaMarketingApiInsightsClient` — resolve the ad-account timezone, split the interval into the
   Insights queries that cover the effective interval exactly (one daily query for the interior
   whole days, one hourly query per partial boundary day), query Graph API Insights, follow paging,
   sum matching buckets.
5. Return the count, or a skip reason.

## Auth

- Results Fulfiller → function: GCP OIDC ID token (handled upstream by `ValidationCloudFunctionClient`).
- Meta System User token: **Secret Manager** only; never reaches Results Fulfiller.

## Testing against live Meta

`MetaMarketingApiInsightsClientRealTest` runs the client against the real Marketing API. It is
tagged `manual`, so `bazel test //...` never picks it up, and it self-skips when its environment
variables are unset.

**The interval need not be day-aligned in the ad account's own timezone.** Meta answers whole days,
and whole hours within one day, in that timezone, so a query for the interior whole days plus the
hourly buckets of each partial boundary day covers the effective interval exactly; the client may
first move either requested bound to the nearest Meta bucket edge, as below. A UTC-midnight window
on a non-UTC account works.

**The count is exact for the *effective* interval, which is not always the one requested.** Meta
cannot split a bucket, so a bound that does not land on a bucket edge moves to the nearest one, ties
resolving earlier, and the run logs both intervals. Two things put a bound off an edge: a zone at a
half-hour offset (`Asia/Kolkata` puts every UTC whole-hour bound mid-bucket), and a bound inside the
hour a daylight-saving fall-back repeats, which Meta reports as one bucket covering both
occurrences. Only an interval left empty by that adjustment is rejected.

For a run whose count you intend to assert exactly, pick bounds already on bucket edges — whole
hours in the account's zone, away from a fall-back. Look the timezone up first:

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

Then convert the bounds to epoch seconds in that zone. Bounds that are whole hours but not
midnight exercise the hourly boundary path as well as the daily one:

```bash
python3 - <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo
tz = ZoneInfo("America/New_York")   # from the call above
start = datetime(2026, 5, 13, 15, 0, tzinfo=tz)
end   = datetime(2026, 6,  1,  9, 0, tzinfo=tz)   # exclusive
print(int(start.timestamp()), int(end.timestamp()))
PY
```

| Variable | Required | Meaning |
| --- | --- | --- |
| `META_ACCESS_TOKEN` | yes | System User token with `ads_read` on the account |
| `META_APP_SECRET` | yes | App secret, for `appsecret_proof` |
| `META_TEST_ENTITY_ID` | yes | Campaign / ad / ad set / account ID |
| `META_TEST_ENTITY_TYPE` | no | Defaults to `campaign` |
| `META_TEST_START_EPOCH_SECONDS` | yes | Interval start — a whole hour in the account's zone |
| `META_TEST_END_EPOCH_SECONDS` | yes | Interval end, exclusive — a whole hour |
| `META_TEST_EXPECTED_IMPRESSIONS` | yes | The count the query must return exactly |

It can also be run from CI by dispatching the **Live Meta test** workflow against a GitHub
environment holding `META_ACCESS_TOKEN` and `META_APP_SECRET` as secrets, and the target as a
`META_TEST_CONFIG_CONTENT` variable. That workflow is dispatch-only; it never runs on push or pull
request, and it validates every field before invoking Bazel, because the test skips rather than
fails when one is missing.

```json
{
  "entity_id": "<CAMPAIGN_ID>",
  "entity_type": "campaign",
  "start_epoch_seconds": 1778698800,
  "end_epoch_seconds": 1780318800,
  "expected_impressions": 1234
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `entity_id` | string | Campaign / ad / ad set / account ID |
| `entity_type` | string | One of `campaign`, `ad`, `creative`, `ad_set`, `adset`, `account`, `ad_account` |
| `start_epoch_seconds` | integer | Interval start — a whole hour in the account's timezone |
| `end_epoch_seconds` | integer | Interval end, exclusive — a whole hour, after the start |
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
  --test_env=META_TEST_EXPECTED_IMPRESSIONS \
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

where `request.pb` is a serialized `DataProviderImpressionQueryRequest`. The interval need not be
whole days; a bound inside a Meta bucket moves to the nearest edge, as the alignment note above
describes.

For a throwaway dev deployment, `--set-env-vars=META_ACCESS_TOKEN=...,META_APP_SECRET=...` avoids
provisioning Secret Manager. Never do this outside a disposable test project: the values are
visible in the function's configuration.

Managed deployment lives in cross-media-measurement's Terraform: the edp-aggregator module deploys
the function with its `http-cloud-function` module, from the version pinned in each environment's
tfvars.

## Metrics

`MetaImpressionQueryFunction` records each query in the `meta.impression_query.queries` counter,
with attributes `outcome` (`success`, a skip reason such as `entity_not_found`, or
`permission_denied`, `rate_limited`, `auth_failed` or `error`) and `entity_type` (the Meta level
queried).

Each query also records the access it observed to its ad accounts in the
`meta.account_access.last_observed` gauge: the time, in seconds since the epoch, each
(`account_id`, `result`) pair was last seen, where `result` is `accessible`, `permission_denied` or
`inaccessible`. An account's current access is the result with the latest time. An account is known
from an account-level entity, or once the client has resolved the entity's account. Meta does not
reveal the account behind an entity it hides from the token, so such a refusal is counted only by
outcome unless the entity names its `EventGroup`: then the function looks the `EventGroup` up with
`GetEventGroup` and records the `ad_account_id` in its `entity_metadata`. Throttles, rejected
credentials and other failures leave an account's access unchanged, and an account no report has
queried yet has no points. Refusals are not cached, so a denied account is queried again on its next
report and newly granted access shows up.

Each of those lookups is counted in the `meta.ad_account_lookup.lookups` counter, with attribute
`outcome`: `resolved`, `unresolved` (the `EventGroup` names no account, is for another entity, or
belongs to another `DataProvider`) or `failed` (for example, the Kingdom was unreachable). Alert on
`failed`: a failed lookup only skips attribution, so the query outcome does not show it.

Set `OTEL_METRICS_EXPORTER=google_cloud_monitoring` and `OTEL_SERVICE_NAME` (for example
`meta.impression_query`), and grant the service account `roles/monitoring.metricWriter`, to export
them. OpenTelemetry exporters default to `none`, so without `OTEL_METRICS_EXPORTER` nothing is
exported. The metric names and attributes are also used by cross-media-measurement's
`meta_impression_query_dashboard.json`; change both together.

## Resolving accounts from EventGroups

To attribute entities Meta hides, the function calls the CMMS `GetEventGroup` method as Meta's
`DataProvider` over mutual TLS. Set `KINGDOM_TARGET` (the Kingdom public API, `host:port`) and
mount the `DataProvider`'s TLS certificate, PKCS #8 private key and trusted root certificates as
files named by `CMMS_TLS_CERT_FILE`, `CMMS_TLS_KEY_FILE` and `CMMS_TRUSTED_CERTS_FILE`, for example
with `--set-secrets=/etc/cmms/tls.pem=<secret>:latest,...`. `KINGDOM_CERT_HOST` optionally
overrides the host name expected in the Kingdom's certificate, and `CMMS_REQUEST_TIMEOUT_SECONDS`
(default 5) the deadline of each lookup. When `KINGDOM_TARGET` is unset or blank, no lookups
are made. When it is set, a missing TLS variable or unreadable file fails the function's startup.
Only `EventGroup`s of the request's `data_provider` are looked up. A failed lookup only skips
attribution; it never fails the query.

## Status

Implemented: request/response handling, entity-type routing for all four Meta Insights levels,
Insights JSON parsing with paging, ad-account-timezone `time_range` conversion, `appsecret_proof` on
every request, skip-reason mapping, query and per-account access metrics, and attribution of
hidden entities to ad accounts through their `EventGroup`s (see Resolving accounts from
EventGroups).

Open TODOs (tracked in code):
- **CEL → breakdown** translation for `age_group` + `gender` — only the unfiltered case is supported
  today; filtered queries return `FILTER_NOT_SUPPORTED`. Meta does not allow the hourly breakdown
  alongside `age` or `gender`, so a filtered interval needing hourly boundary queries cannot be
  answered at all (world-federation-of-advertisers/edp-components#16).
- **Async Insights** report-run path for large queries.
- **Sandbox integration test** — world-federation-of-advertisers/edp-components#3.
