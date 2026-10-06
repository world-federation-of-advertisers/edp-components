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

output "function_service_account" {
  description = "Service account the Cloud Function runs as."
  value       = google_service_account.function.email
}

output "function_name" {
  description = "Deployed Cloud Function name."
  value       = var.function_name
}

output "function_uri_command" {
  description = <<-EOT
    Command that prints the deployed function's HTTPS URI. Not read directly as an output because
    the function is deployed through gcloud rather than a google_cloudfunctions2_function
    resource, so its URI is not in Terraform state.
  EOT
  value = join(" ", [
    "gcloud functions describe ${var.function_name}",
    "--gen2 --region=${var.region} --project=${var.project_id}",
    "--format='value(serviceConfig.uri)'",
  ])
}
