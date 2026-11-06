#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
#
# NIGHTLY-1 safety guard: a stable release ships only a commit that was shipped as a nightly first.
#
#   scripts/stable-guard.sh <commit>
#
# Passes (exit 0) when <commit> carries a nightly tag (v*-nightly.*), OR its first parent does and <commit> changes nothing
# but the release bump: the `infantryVersion=` line of gradle.properties and the `version:` line of docs/shared/product.md
# (what the release-cut script commits). Otherwise exits 1 with the reason. Needs the history and the tags (fetch-depth 0).
# Callers: release.yml's version job (on a stable tag) and the release-cut script; both have a force override
# (docs/RELEASING.md, "The stable safety guard").
set -euo pipefail

NIGHTLY_ID="${NIGHTLY_ID:-nightly}"
# The files a release bump may touch, and the one line it may change in each (an ERE on the changed line).
BUMP_LINES=(
  "gradle.properties=^infantryVersion="
  "docs/shared/product.md=^version:"
)

commit="$(git rev-parse --verify "${1:?usage: stable-guard.sh <commit>}^{commit}")"
nightly_on() { git tag -l "v*-$NIGHTLY_ID.*" --points-at "$1" | head -n1; }

t="$(nightly_on "$commit")"
if [ -n "$t" ]; then echo "stable-guard: OK, $commit was shipped as nightly $t"; exit 0; fi

parent="$(git rev-parse -q --verify "$commit^1" || true)"
t="${parent:+$(nightly_on "$parent")}"
if [ -z "$t" ]; then
  echo "stable-guard: REFUSED, $commit has no nightly (no v*-$NIGHTLY_ID.* tag on it or on its parent)." >&2
  echo "stable-guard: let tonight's nightly ship it (or run .github/workflows/nightly.yml by hand), then cut the release." >&2
  exit 1
fi

# The parent was a nightly: the commit may only bump the version.
bad=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  pattern=""
  for entry in "${BUMP_LINES[@]}"; do [ "${entry%%=*}" = "$f" ] && pattern="${entry#*=}"; done
  if [ -z "$pattern" ]; then bad+=" $f"; continue; fi
  # Every added or removed line (not the +++/--- headers) must be the version line.
  if git diff -U0 "$parent" "$commit" -- "$f" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---) ' | sed 's/^[+-]//' | grep -vqE "$pattern"; then
    bad+=" $f(more than the version line)"
  fi
done < <(git diff --name-only "$parent" "$commit")
if [ -n "$bad" ]; then
  echo "stable-guard: REFUSED, $commit is not only a version bump over nightly $t; it changes:$bad" >&2
  exit 1
fi
echo "stable-guard: OK, $commit is the version bump over nightly $t ($parent)"
