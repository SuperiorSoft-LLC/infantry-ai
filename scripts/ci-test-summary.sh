#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# CI-TESTS-1: summarise JUnit XML under the given dirs into GITHUB_STEP_SUMMARY (pass/fail counts, failed test names).
# A failing class listed in docs/KNOWN-FAILURES.md is reported as "known", not counted as new. Exit 1 only on NEW failures.
# Usage: scripts/ci-test-summary.sh <label> <results-dir>...
label="$1"; shift
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
tot=0; fail=0; skip=0; new=""; known=""
for f in $(find "$@" -name 'TEST-*.xml' 2>/dev/null); do
  t=$(grep -o '<testsuite [^>]*' "$f" | head -1)
  n=$(echo "$t" | sed -n 's/.* tests="\([0-9]*\)".*/\1/p'); s=$(echo "$t" | sed -n 's/.* skipped="\([0-9]*\)".*/\1/p')
  fl=$(echo "$t" | sed -n 's/.* failures="\([0-9]*\)".*/\1/p'); er=$(echo "$t" | sed -n 's/.* errors="\([0-9]*\)".*/\1/p')
  tot=$((tot + ${n:-0})); skip=$((skip + ${s:-0})); bad=$(( ${fl:-0} + ${er:-0} ))
  if [ "$bad" -gt 0 ]; then
    fail=$((fail + bad)); cls=$(echo "$t" | sed -n 's/.* name="\([^"]*\)".*/\1/p'); short="${cls##*.}"
    if grep -q "\`[A-Za-z.]*$short\`" docs/KNOWN-FAILURES.md 2>/dev/null; then known="$known $cls"; else new="$new $cls"; fi
  fi
done
{
  echo "### $label: $tot tests, $fail failed, $skip skipped"
  if [ -n "$known" ]; then echo "Known failures (docs/KNOWN-FAILURES.md), not failing the job:"; for c in $known; do echo "- \`$c\`"; done; fi
  if [ -n "$new" ]; then echo "**NEW failing classes:**"; for c in $new; do echo "- \`$c\`"; done; fi
} >> "$out"
[ -z "$new" ]
