#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-UPD-2: picks the updater signing key for a CI build and writes a Tauri config override that carries its public key.
#   updater-key.sh <override.json>
# With the secret TAURI_SIGNING_PRIVATE_KEY set (the OWNER's key, UPDATER.md), it is used as is, and tauri.conf.json must
# already hold the matching public key (not the placeholder). Without the secret, a THROWAWAY key pair is made for this run
# and its public key goes into the override, so the build is self-consistent but no installed app will trust it.
# Writes SIGNING=real|test to $GITHUB_OUTPUT and the key to $GITHUB_ENV when they exist. A key is never printed or committed.
set -euo pipefail
out="${1:?usage: updater-key.sh <override.json>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${TAURI_SIGNING_PRIVATE_KEY:-}" ]; then
  if grep -q 'OWNER-SETS-THE-UPDATER-PUBLIC-KEY' "$here/tauri.conf.json"; then
    echo "TAURI_SIGNING_PRIVATE_KEY is set but tauri.conf.json still has the public-key placeholder: commit the matching public key first" >&2
    exit 1
  fi
  echo '{}' > "$out"
  kind=real
else
  tmp="$(mktemp -d)"
  ( cd "$here/.." && pnpm tauri signer generate --ci -p "" -w "$tmp/test.key" >/dev/null )
  key="$(cat "$tmp/test.key")"; pub="$(cat "$tmp/test.key.pub")"
  python - "$out" "$pub" <<'PY'
import json, sys
json.dump({"plugins": {"updater": {"pubkey": sys.argv[2]}}}, open(sys.argv[1], "w"))
PY
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "::add-mask::$key"
    { echo "TAURI_SIGNING_PRIVATE_KEY<<KEYEOF"; echo "$key"; echo "KEYEOF"; echo "TAURI_SIGNING_PRIVATE_KEY_PASSWORD="; } >> "$GITHUB_ENV"
  else
    echo "export TAURI_SIGNING_PRIVATE_KEY='$key' TAURI_SIGNING_PRIVATE_KEY_PASSWORD=''"
  fi
  echo "::warning::No TAURI_SIGNING_PRIVATE_KEY secret: signed with a THROWAWAY test key. This build is for the owner's test only; no installed app trusts it."
  kind=test
fi
[ -z "${GITHUB_OUTPUT:-}" ] || echo "signing=$kind" >> "$GITHUB_OUTPUT"
echo "updater signing: $kind"
