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

variable "project_id" {
  description = "GCP project the Cloud Function is deployed into."
  type        = string
  nullable    = false
}

variable "region" {
  description = "GCP region for the Cloud Function."
  type        = string
  nullable    = false
}

variable "terraform_service_account" {
  description = <<-EOT
    Service account Terraform runs as. Needs roles/iam.serviceAccountUser on the function's own
    service account in order to attach it at deploy time.
  EOT
  type        = string
  nullable    = false
}

variable "function_name" {
  description = "Deployed Cloud Function name."
  type        = string
  default     = "meta-impression-query"
}

variable "service_account_name" {
  description = <<-EOT
    Account ID (not the full email) of the service account the function runs as. Created by this
    configuration.
  EOT
  type        = string
  default     = "meta-impression-query"
}

variable "uber_jar_path" {
  description = <<-EOT
    Path to MetaImpressionQueryFunction_deploy.jar on the machine running Terraform.

    Terraform does not build this. Produce it first with:

      bazel build //src/main/kotlin/org/wfanet/measurement/edpcomponents/meta:MetaImpressionQueryFunction_deploy.jar

    gcloud uploads the whole directory containing the jar, so stage it somewhere containing
    nothing else rather than pointing at bazel-bin directly.
  EOT
  type        = string
  nullable    = false
}

variable "meta_access_token_secret_id" {
  description = <<-EOT
    Secret Manager secret ID holding the Meta System User access token.

    Provisioned out of band, NOT by this configuration: the token is a Meta production credential
    and should not pass through a Terraform operator's filesystem or state. Terraform only grants
    the function's service account read access to it.
  EOT
  type        = string
  default     = "meta-access-token"
}

variable "meta_app_secret_secret_id" {
  description = <<-EOT
    Secret Manager secret ID holding the Meta app secret, used to compute appsecret_proof.
    Provisioned out of band, as above.
  EOT
  type        = string
  default     = "meta-app-secret"
}

variable "invoker_service_accounts" {
  description = <<-EOT
    Service account emails permitted to invoke the function, granted roles/run.invoker.

    In production this is the Results Fulfiller workload identity, whose
    ValidationCloudFunctionClient presents an audience-scoped OIDC ID token. The function is
    deployed with --no-allow-unauthenticated, so a caller absent from this list is rejected by
    Google before any of our code runs.
  EOT
  type        = list(string)
  default     = []
}

variable "timeout_seconds" {
  description = <<-EOT
    Per-invocation timeout. Must stay below the caller's own deadline: EdpValidationPostProcessor
    stops waiting after 30 seconds, so a longer function timeout leaves this running and consuming
    Meta quota after the caller has already given up on the response.
  EOT
  type        = number
  default     = 25
}

variable "max_instances" {
  description = <<-EOT
    Maximum concurrent instances. Deliberately not 1: this is invoked per report and must scale.

    Note the trade-off with the ad-account timezone cache, which lives in instance memory — more
    instances means more cold caches and therefore more Graph calls to resolve timezones.
  EOT
  type        = number
  nullable    = true
  default     = null
}

variable "extra_env_vars" {
  description = "Additional environment variables, as a comma-separated KEY=VALUE string."
  type        = string
  default     = ""
}
