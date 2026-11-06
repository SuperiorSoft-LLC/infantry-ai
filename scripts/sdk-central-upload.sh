#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# SDK-CENTRAL-1: uploads build/central-bundle.zip to the Sonatype Central Portal publisher API and waits for the result.
# Only the owner-approved `sdk-release` job runs this. Needs MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD (a Portal user
# token). Never prints them: the derived bearer token is masked in GitHub Actions too.
# Settings (environment, with defaults):
#   CENTRAL_PUBLISHING_TYPE   AUTOMATIC (publish once validated) or USER_MANAGED (stop at VALIDATED, release in the Portal UI)
#   CENTRAL_POLL_SECONDS      seconds between status checks (default 15)
#   CENTRAL_TIMEOUT_SECONDS   give up waiting after this long (default 1800); the deployment keeps going in the Portal
#   CENTRAL_API               API base (default https://central.sonatype.com/api/v1/publisher)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bundle="${1:-$root/build/central-bundle.zip}"
api="${CENTRAL_API:-https://central.sonatype.com/api/v1/publisher}"
type="${CENTRAL_PUBLISHING_TYPE:-AUTOMATIC}"
poll="${CENTRAL_POLL_SECONDS:-15}"
timeout="${CENTRAL_TIMEOUT_SECONDS:-1800}"

[ -n "${MAVEN_CENTRAL_USERNAME:-}" ] && [ -n "${MAVEN_CENTRAL_PASSWORD:-}" ] \
  || { echo "sdk-central-upload: Central credentials missing (MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD)" >&2; exit 1; }
[ -f "$bundle" ] || { echo "sdk-central-upload: $bundle not found; run scripts/sdk-central-bundle.sh first" >&2; exit 1; }

token="$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
[ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$token"
# The header goes through a file so it never shows on a command line.
hdr="$(mktemp)"; trap 'rm -f "$hdr"' EXIT
printf 'Authorization: Bearer %s\n' "$token" > "$hdr"

name="infantry-sdk-$(sed -n 's/^infantryVersion=//p' "$root/gradle.properties" | tr -d '\r')"
echo "sdk-central-upload: uploading $(basename "$bundle") as $name ($type)"
id="$(curl -fsS -X POST -H @"$hdr" -F "bundle=@$bundle" "$api/upload?name=$name&publishingType=$type")"
echo "sdk-central-upload: deployment $id"

waited=0
while :; do
  status="$(curl -fsS -X POST -H @"$hdr" "$api/status?id=$id")"
  state="$(printf '%s' "$status" | sed -n 's/.*"deploymentState"[[:space:]]*:[[:space:]]*"\([A-Z_]*\)".*/\1/p')"
  echo "sdk-central-upload: $state"
  case "$state" in
    PUBLISHED) echo "sdk-central-upload: published"; exit 0 ;;
    VALIDATED) [ "$type" = "USER_MANAGED" ] && { echo "sdk-central-upload: validated; release it in the Central Portal"; exit 0; } ;;
    FAILED) echo "sdk-central-upload: deployment failed:" >&2; printf '%s\n' "$status" >&2; exit 1 ;;
  esac
  [ "$waited" -ge "$timeout" ] && { echo "sdk-central-upload: still $state after ${timeout}s; check deployment $id in the Portal" >&2; exit 1; }
  sleep "$poll"; waited=$((waited + poll))
done
