# Copyright 2026 The Cross-Media Measurement Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Deploys the Meta impression-query Cloud Function (Gen 2).
#
# Mirrors the http-cloud-function module used by the EDP Aggregator functions in
# cross-media-measurement, with two deliberate differences, both discussed in the PR:
#
#   1. Secrets are NOT created here. That module creates each secret from a file on the operator's
#      disk (`secret_data = file(var.secret_path)`). The Meta System User token is a Meta
#      production credential; it should be provisioned directly into Secret Manager and never pass
#      through a Terraform operator's filesystem or state. This configuration only grants read
#      access to secrets that already exist.
#
#   2. Secrets are injected as environment variables rather than mounted as files. The EDPA
#      functions mount PEM certificates, which suits files; these are opaque strings that the
#      function reads with System.getenv. `--set-secrets` supports both forms.

data "google_client_config" "default" {}

resource "google_service_account" "function" {
  project      = var.project_id
  account_id   = var.service_account_name
  display_name = "Meta impression-query Cloud Function"
}

resource "google_service_account_iam_member" "terraform_can_attach_service_account" {
  service_account_id = google_service_account.function.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${var.terraform_service_account}"
}

# Read access to the two pre-provisioned Meta credentials.
resource "google_secret_manager_secret_iam_member" "secret_accessor" {
  for_each = toset([var.meta_access_token_secret_id, var.meta_app_secret_secret_id])

  project   = var.project_id
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.function.email}"
}

resource "terraform_data" "deploy" {
  depends_on = [
    google_service_account.function,
    google_service_account_iam_member.terraform_can_attach_service_account,
    google_secret_manager_secret_iam_member.secret_accessor,
  ]

  # Every value the provisioner reads. A creation-time provisioner does not re-run when one of
  # its arguments changes, so anything omitted here can drift in Terraform while the deployed
  # function keeps its previous configuration. depends_on orders resources; it does not do this.
  triggers_replace = [
    var.project_id,
    var.function_name,
    var.region,
    var.uber_jar_path,
    var.extra_env_vars,
    var.timeout_seconds,
    var.max_instances,
    local.entry_point,
    local.secret_mappings,
    google_service_account.function.email,
  ]

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    environment = {
      PROJECT_ID          = var.project_id
      FUNCTION_NAME       = var.function_name
      ENTRY_POINT         = local.entry_point
      CLOUD_REGION        = var.region
      RUN_SERVICE_ACCOUNT = google_service_account.function.email
      UBER_JAR_DIRECTORY  = dirname(var.uber_jar_path)
      SECRET_MAPPINGS     = local.secret_mappings
      EXTRA_ENV_VARS      = var.extra_env_vars
      TIMEOUT_SECONDS     = tostring(var.timeout_seconds)
      MAX_INSTANCES       = var.max_instances == null ? "" : tostring(var.max_instances)
    }
    command = <<-EOT
      #!/bin/bash
      set -euo pipefail

      args=(
        "functions" "deploy" "$FUNCTION_NAME"
        "--gen2"
        "--project=$PROJECT_ID"
        "--runtime=java17"
        # Not validated at deploy time. A typo here deploys successfully and then fails every
        # request at runtime.
        "--entry-point=$ENTRY_POINT"
        "--memory=512MB"
        "--region=$CLOUD_REGION"
        "--run-service-account=$RUN_SERVICE_ACCOUNT"
        # gcloud uploads this whole directory, not a single file.
        "--source=$UBER_JAR_DIRECTORY"
        "--trigger-http"
        # Google rejects callers without a valid OIDC ID token before the function runs. This is
        # the entire authentication story; the function performs no token checks of its own.
        "--no-allow-unauthenticated"
        "--set-secrets=$SECRET_MAPPINGS"
      )

      # Each of these needs an explicit clear on the transition back to empty: supplying neither
      # flag makes gcloud preserve the previous value, so Terraform would report a successful
      # deployment while the function kept stale configuration.
      if [[ -n "$EXTRA_ENV_VARS" ]]; then
        args+=("--set-env-vars=$EXTRA_ENV_VARS")
      else
        args+=("--clear-env-vars")
      fi
      if [[ -n "$TIMEOUT_SECONDS" ]]; then
        args+=("--timeout=$TIMEOUT_SECONDS")
      fi
      if [[ -n "$MAX_INSTANCES" ]]; then
        args+=("--max-instances=$MAX_INSTANCES")
      else
        args+=("--clear-max-instances")
      fi

      # Quoted: an unquoted expansion word-splits, so an environment-variable value containing
      # whitespace would arrive as several arguments.
      gcloud "$${args[@]}"
    EOT
  }
}

# Deletion is a separate resource from the deploy so that it is not caught up in replacement.
# terraform_data.deploy replaces whenever any deployment input changes; a destroy provisioner on it
# would therefore delete the live function before every redeploy, leaving it absent entirely if the
# subsequent deploy failed. This resource is keyed only on identity, so it is created once and
# destroyed once.
resource "terraform_data" "function_lifecycle" {
  depends_on = [terraform_data.deploy]

  # A destroy provisioner cannot read var.* or other resources, only its own state.
  input = {
    project_id    = var.project_id
    function_name = var.function_name
    region        = var.region
  }

  triggers_replace = [var.project_id, var.function_name, var.region]

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["/bin/bash", "-c"]
    environment = {
      PROJECT_ID    = self.input.project_id
      FUNCTION_NAME = self.input.function_name
      CLOUD_REGION  = self.input.region
    }
    # Only an already-absent function is tolerated. Permission and network failures must fail the
    # destroy rather than reporting success while the function is still deployed.
    command = <<-EOT
      #!/bin/bash
      set -uo pipefail

      stderr="$(gcloud functions delete "$FUNCTION_NAME" \
        --gen2 --project="$PROJECT_ID" --region="$CLOUD_REGION" --quiet 2>&1 >/dev/null)"
      status=$?

      if [[ $status -eq 0 ]]; then
        exit 0
      fi
      if grep -qiE 'NOT_FOUND|does not exist|could not be found' <<<"$stderr"; then
        echo "Function already absent; nothing to delete."
        exit 0
      fi
      echo "$stderr" >&2
      exit "$status"
    EOT
  }
}

# Bound on the Cloud Run service rather than the Cloud Functions resource: Gen-2 invocation
# checks run.routes.invoke on the underlying service, so binding through the function resource can
# leave the configured caller receiving 403.
resource "google_cloud_run_service_iam_member" "invoker" {
  for_each = toset(var.invoker_service_accounts)

  depends_on = [terraform_data.deploy]

  project  = var.project_id
  location = var.region
  service  = var.function_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${each.value}"
}

locals {
  entry_point = "org.wfanet.measurement.edpcomponents.meta.MetaImpressionQueryFunction"

  # ENV_VAR=secret:version form. --set-secrets also accepts /path=secret:version for file mounts,
  # which is what the EDPA functions use for PEM material.
  secret_mappings = join(",", [
    "META_ACCESS_TOKEN=${var.meta_access_token_secret_id}:latest",
    "META_APP_SECRET=${var.meta_app_secret_secret_id}:latest",
  ])
}
