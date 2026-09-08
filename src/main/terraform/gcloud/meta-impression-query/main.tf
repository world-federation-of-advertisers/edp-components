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

  triggers_replace = [
    var.uber_jar_path,
    var.extra_env_vars,
    var.timeout_seconds,
    var.max_instances,
    local.secret_mappings,
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

      if [[ -n "$EXTRA_ENV_VARS" ]]; then
        args+=("--set-env-vars=$EXTRA_ENV_VARS")
      fi
      if [[ -n "$TIMEOUT_SECONDS" ]]; then
        args+=("--timeout=$TIMEOUT_SECONDS")
      fi
      if [[ -n "$MAX_INSTANCES" ]]; then
        args+=("--max-instances=$MAX_INSTANCES")
      fi

      gcloud $${args[@]}
    EOT
  }
}

resource "google_cloudfunctions2_function_iam_member" "invoker" {
  for_each = toset(var.invoker_service_accounts)

  depends_on = [terraform_data.deploy]

  project        = var.project_id
  location       = var.region
  cloud_function = var.function_name
  role           = "roles/run.invoker"
  member         = "serviceAccount:${each.value}"
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
