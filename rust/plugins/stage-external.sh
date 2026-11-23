#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
# SVC-8: stages the official plugins listed in bundled-external.json (built in the infantry-plugins repo) the way
# stage.sh lays out the others: <out>/official/<Module>/module.json and its binary. Each module's own stage.sh builds it
# and renders its endpoints for the build environment: INFANTRY_BUILD_ENV (the release workflow sets prod), else staging.
# Usage: rust/plugins/stage-external.sh <out-dir> [release]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:?usage: stage-external.sh <out-dir> [release]}"
profile="${2:-debug}"
env_name="${INFANTRY_BUILD_ENV:-staging}"
case "$env_name" in prod|staging|dev) ;; *) echo "refuse: INFANTRY_BUILD_ENV '$env_name' is not prod, staging or dev" >&2; exit 1;; esac
list="$here/bundled-external.json"
# bundled-external.json is flat enough to read without a JSON tool (no python needed, so a test can run this script).
field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$list" | head -1; }
repo_env="$(field repoEnv)"
repo="${!repo_env:-}"
[ -n "$repo" ] || repo="$here/../../$(field repoDefault)"
mods="$(sed -n 's/.*"path": *"\([^"]*\)".*/\1/p' "$list")"
for p in $mods; do
  case "$p" in *..*|/*) echo "refuse: module path '$p' is outside the plugins repo" >&2; exit 1;; esac
  [ -f "$repo/$p/stage.sh" ] || { echo "refuse: $repo/$p/stage.sh not found (set $repo_env to a checkout of the infantry-plugins repo)" >&2; exit 1; }
  extra=(); [ "$profile" = "release" ] && extra=(--release)
  bash "$repo/$p/stage.sh" "$out" --env "$env_name" ${extra[@]+"${extra[@]}"}
done
