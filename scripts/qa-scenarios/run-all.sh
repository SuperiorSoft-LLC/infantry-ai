#!/usr/bin/env bash
# run-all.sh: run every scenario against the window from launch.sh; exit 1 if any FAIL.
cd "$(dirname "$0")"; export PID="${PID:-$(bash pid.sh)}"; rc=0
for f in s[1-7]-*.sh; do echo "== $f"; bash "$f" || rc=1; done; exit $rc
