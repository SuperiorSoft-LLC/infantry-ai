#!/usr/bin/env bash
# DOCS-ENV-1: fail when a staged plugin TEXT file (module.json, ui js/css/html, md, any non-binary file) carries a local
# or staging address: 127.0.0.1, localhost or "(staging)". A prod package that ships one makes a broken call or link for
# the user (Infantry_Sites shipped http://127.0.0.1:18953 in 0.8.4 and 0.8.5). Binary files (wasm components, exes) are
# skipped: their strings are compiled in from source, and Infantry_Docs embeds the wiki prose that documents local
# addresses on purpose (read by docs_show, never called). Usage: check-staged-endpoints.sh <staged plugins dir>
set -euo pipefail
dir="${1:?usage: check-staged-endpoints.sh <staged plugins dir>}"
[ -d "$dir" ] || { echo "check-staged-endpoints: no such dir: $dir" >&2; exit 2; }
bad="$(grep -rlIE '127\.0\.0\.1|localhost|\(staging\)' "$dir" || true)"
if [ -n "$bad" ]; then
  echo "::error::staged plugin text files with a local or staging address:"
  echo "$bad"
  grep -rnIoE '.{0,40}(127\.0\.0\.1|localhost|\(staging\)).{0,40}' "$dir" | head -20
  exit 1
fi
echo "check-staged-endpoints: no local or staging address in $(grep -rlI '' "$dir" | wc -l) text files under $dir"
