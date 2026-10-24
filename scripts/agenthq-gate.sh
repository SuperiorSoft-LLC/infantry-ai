#!/bin/sh
# Bulk QA gate: runs every test stage uncached, one Gradle at a time, in a throwaway worktree.
# Usage: scripts/agenthq-gate.sh <commit-or-branch>   (see docs/GATE.md)
# Env: GATE_MIN_FREE_GB (default 5), GATE_LOCK (lock dir), GATE_KEEP=1 keeps the worktree.

set -u
REF="${1:-}"
[ -n "$REF" ] || { echo "usage: $0 <commit-or-branch>" >&2; exit 2; }

REPO=$(git rev-parse --show-toplevel) || exit 2
TMPROOT="${TEMP:-${TMP:-/tmp}}"
MIN_GB="${GATE_MIN_FREE_GB:-5}"
LOCK="${GATE_LOCK:-$TMPROOT/agenthq-gate.lock}"

# --- machine-wide lock (mkdir is atomic); stale locks from dead PIDs are cleaned ---
acquire_lock() {
  if mkdir "$LOCK" 2>/dev/null; then echo $$ > "$LOCK/pid"; return 0; fi
  holder=$(cat "$LOCK/pid" 2>/dev/null || true)
  if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
    echo "GATE REFUSED: another gate run is active (pid $holder, lock $LOCK). One Gradle at a time, machine-wide." >&2
    exit 3
  fi
  echo "removing stale gate lock (pid ${holder:-unknown} is dead)" >&2
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || { echo "GATE REFUSED: lost the lock race" >&2; exit 3; }
  echo $$ > "$LOCK/pid"
}
acquire_lock

WT=""
cleanup() {
  if [ -n "$WT" ] && [ -z "${GATE_KEEP:-}" ]; then
    git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1 || rm -rf "$WT"
    git -C "$REPO" worktree prune >/dev/null 2>&1
  fi
  rm -rf "$LOCK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

free_gb() { df -Pk "$TMPROOT" | awk 'NR==2{printf "%d", $4/1048576}'; }
check_disk() {
  f=$(free_gb)
  if [ "$f" -lt "$MIN_GB" ]; then
    echo "GATE ABORT: only ${f} GB free on $TMPROOT (floor ${MIN_GB} GB)" >&2
    return 1
  fi
}

check_disk || exit 4
SHA=$(git -C "$REPO" rev-parse --verify "$REF^{commit}") || { echo "unknown ref: $REF" >&2; exit 2; }
OUT="$TMPROOT/agenthq-gate-$(echo "$SHA" | cut -c1-8)-$$"
mkdir -p "$OUT"
WT="$OUT/wt"
STATUS="$OUT/status.log"
note() { echo "$(date '+%H:%M:%S') $*" | tee -a "$STATUS"; }

git -C "$REPO" worktree add --detach "$WT" "$SHA" >/dev/null 2>&1 || { echo "worktree add failed" >&2; exit 2; }
note "gate $REF = $SHA; worktree $WT; logs $OUT"

# count tests from the JUnit XML a stage produced: "tests failures errors skipped|failing names"
tally() {
  find "$WT/$1" -path '*test-results*' -name 'TEST-*.xml' 2>/dev/null | while read -r x; do
    head -n 5 "$x" | grep -o '<testsuite [^>]*' | head -n 1 | sed -e 's/.* tests="\([0-9]*\)".*skipped="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/\1 \2 \3 \4/'
  done | awk '{t+=$1;s+=$2;f+=$3;e+=$4} END{printf "%d %d %d %d", t,s,f,e}'
}
failing() {
  find "$WT/$1" -path '*test-results*' -name 'TEST-*.xml' 2>/dev/null | while read -r x; do
    awk '/<testcase /{n=$0; sub(/.*classname="/,"",n); c=n; sub(/".*/,"",c); sub(/.*name="/,"",n); sub(/".*/,"",n); cur=c "." n}
         /<failure|<error/{print cur}' "$x"
  done | sort -u
}

SUMMARY=""; FAILED=0; FAILNAMES=""
stage() { # name gradle-task project-dir
  name=$1; task=$2; dir=$3
  if ! check_disk; then SUMMARY="$SUMMARY $name=ABORT(disk)"; FAILED=1; return; fi
  note "stage $name: gradlew $task --rerun-tasks --no-daemon"
  (cd "$WT" && ./gradlew "$task" --rerun-tasks --no-daemon --console=plain) > "$OUT/$name.log" 2>&1
  rc=$?
  set -- $(tally "$dir")
  names=$(failing "$dir" | tr '\n' ',' | sed 's/,$//')
  if [ $rc -eq 0 ] && [ "$3" -eq 0 ] && [ "$4" -eq 0 ] && [ "$1" -gt 0 ]; then verdict=ok; else verdict=FAIL; FAILED=1; fi
  # Not a test failure: say who broke the run, so nobody chases a flaky test that never ran.
  if [ $rc -ne 0 ]; then
    if grep -q 'stop command received' "$OUT/$name.log"; then
      verdict=EXTERNAL_STOP; note "stage $name: killed by a 'gradlew --stop' from another session (not a test failure); re-run the gate"
    elif grep -q 'TEST RUN REFUSED' "$OUT/$name.log"; then
      verdict=REFUSED_BUSY; note "stage $name: another Gradle build holds this build dir's test lock"
    fi
  fi
  [ -n "$names" ] && FAILNAMES="$FAILNAMES [$name: $names]"
  note "stage $name: rc=$rc tests=$1 skipped=$2 failures=$3 errors=$4 $verdict"
  SUMMARY="$SUMMARY $name=$verdict($1 tests,$3 fail,$4 err)"
  sync; sleep 2   # let file handles from the finished JVM release before the next stage
}

stage agentHq       :superiorSoftLibrary:agentHq:test            superiorSoftLibrary/agentHq/build/test-results/test
stage uiCore        :superiorSoftLibrary:agenthqUiCore:jvmTest   superiorSoftLibrary/agenthqUiCore/build/test-results/jvmTest
stage agentHqUi     :superiorSoftLibrary:agentHqUi:test          superiorSoftLibrary/agentHqUi/build/test-results/test
stage ownerSmoke    :superiorSoftLibrary:agentHqUi:ownerSmoke    superiorSoftLibrary/agentHqUi/build/test-results/ownerSmoke

if [ $FAILED -eq 0 ]; then R=PASS; else R=FAIL; fi
echo "GATE $R $SHA:$SUMMARY${FAILNAMES:+ failing:$FAILNAMES} logs=$OUT"
[ $FAILED -eq 0 ]
