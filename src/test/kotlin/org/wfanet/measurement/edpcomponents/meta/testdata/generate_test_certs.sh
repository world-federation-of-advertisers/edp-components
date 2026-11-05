#!/usr/bin/env bash
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

# Regenerates the testing-only certificates used by MetaImpressionQueryFunctionInvokerTest: a root,
# a server certificate for kingdom.example.com and a client certificate, both signed by the root.
# Keys are unencrypted PKCS #8, as the function expects. Run from this directory.
set -euo pipefail

days=36500
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days "$days" \
  -subj "/CN=Test Root" -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign" -keyout root.key -out root.pem

issue() {
  local name="$1" subject="$2" extensions="$3"
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "$subject" \
    -keyout "$name.key" -out "$name.csr"
  openssl x509 -req -in "$name.csr" -CA root.pem -CAkey root.key -CAcreateserial \
    -days "$days" -extfile <(printf "%s" "$extensions") -out "$name.pem"
  rm "$name.csr"
}

issue kingdom_tls "/CN=kingdom.example.com" \
  "subjectAltName=DNS:kingdom.example.com
extendedKeyUsage=serverAuth"
issue edp_tls "/CN=Test DataProvider" "extendedKeyUsage=clientAuth"
rm -f root.srl root.key
