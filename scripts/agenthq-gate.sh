#!/bin/sh
# Bulk QA gate: runs every test stage (test tasks forced, never cached), one Gradle at a time, in a throwaway worktree.
# Usage: scripts/agenthq-gate.sh [--full] <commit-or-branch>   (see docs/GATE.md)
#   --full: the promotion run. Nothing from the build cache (--no-build-cache): every module compiles fresh, once.
# Env: GATE_MIN_FREE_GB (default 5), GATE_LOCK (lock dir), GATE_KEEP=1 keeps the worktree.
# Build limits (ticket 66153313): inside an Infantry AI worker session the daemon already runs this whole tree at the
# configured priority and puts AGENTHQ_BUILD_PRIORITY / AGENTHQ_BUILD_MAX_WORKERS in the env (settings limits.build.*).
# Each Gradle stage also runs under `nice` at that priority and with --max-workers, so a gate started OUTSIDE a session
# (no AGENTHQ_BUILD_PRIORITY) still runs below normal with logical cores - 2 workers. AGENTHQ_BUILD_PRIORITY=normal
# turns the nice off; inside a session a missing AGENTHQ_BUILD_MAX_WORKERS means the setting is 0 (unlimited).
# Progress (Infantry AI, docs/PROGRESS.md): the stages are declared as `##infantry plan/step` marker lines, read when the
# gate runs under `agenthq run` (which sets AGENTHQ_PROGRESS_PIPE=1). Otherwise, when `agenthq` is on PATH, the same
# reports go out through `agenthq progress`. The test totals are reported once, as a count, after the last stage.
# Outside Infantry AI all of this is inert: the markers are plain lines and `agenthq progress` does nothing.

set -u
FULL=""
if [ "${1:-}" = "--full" ]; then FULL=1; shift; fi
REF="${1:-}"
[ -n "$REF" ] || { echo "usage: $0 [--full] <commit-or-branch>" >&2; exit 2; }
# TEST-SPEED-2: tests are always forced (--rerun). Only --full also bypasses the build cache for compilation.
CACHE_FLAG=""; [ -n "$FULL" ] && CACHE_FLAG="--no-build-cache"

case "${AGENTHQ_BUILD_PRIORITY:-belownormal}" in
  normal) NICE_N=0 ;;
  idle) NICE_N=19 ;;
  *) NICE_N=10 ;;
esac
NICE_CMD=""
if [ "$NICE_N" -gt 0 ] && command -v nice >/dev/null 2>&1; then NICE_CMD="nice -n $NICE_N"; fi
if [ -n "${AGENTHQ_BUILD_PRIORITY:-}" ]; then
  MAX_WORKERS="${AGENTHQ_BUILD_MAX_WORKERS:-0}"
else
  cores=$(nproc 2>/dev/null || echo 0)
  MAX_WORKERS=$((cores > 3 ? cores - 2 : 1))
fi
WORKERS_FLAG=""; [ "$MAX_WORKERS" -gt 0 ] 2>/dev/null && WORKERS_FLAG="--max-workers=$MAX_WORKERS"

REPO=$(git rev-parse --show-toplevel) || exit 2
TMPROOT="${TEMP:-${TMP:-/tmp}}"
MIN_GB="${GATE_MIN_FREE_GB:-5}"
LOCK="${GATE_LOCK:-$TMPROOT/agenthq-gate.lock}"

# --- machine-wide lock (mkdir is atomic); stale locks from dead PIDs are cleaned ---
# Ticket 7e9bed2a: owner.txt says who holds the lock (ticket=<id> session=<id> pid=<n>), so the daemon can release it when that session ends.
write_owner() {
  {
    [ -n "${AGENTHQ_TICKET_ID:-}" ] && printf 'ticket=%s ' "$AGENTHQ_TICKET_ID"
    [ -n "${AGENTHQ_SESSION_ID:-}" ] && printf 'session=%s ' "$AGENTHQ_SESSION_ID"
    printf 'pid=%s\n' "$$"
  } > "$LOCK/owner.txt" 2>/dev/null || true
}
acquire_lock() {
  if mkdir "$LOCK" 2>/dev/null; then echo $$ > "$LOCK/pid"; write_owner; return 0; fi
  holder=$(cat "$LOCK/pid" 2>/dev/null || true)
  if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
    echo "GATE REFUSED: another gate run is active (pid $holder, lock $LOCK). One Gradle at a time, machine-wide." >&2
    exit 3
  fi
  echo "removing stale gate lock (pid ${holder:-unknown} is dead)" >&2
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || { echo "GATE REFUSED: lost the lock race" >&2; exit 3; }
  echo $$ > "$LOCK/pid"
  write_owner
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
note "gate $REF = $SHA${FULL:+ (FULL: --no-build-cache)}; worktree $WT; logs $OUT"

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

# --- progress: a marker line always; a direct report only when nothing reads the markers and the CLI exists ---
STAGES="agentHq uiCore agentHqUi ownerSmoke"
direct_progress() { [ -z "${AGENTHQ_PROGRESS_PIPE:-}" ] && command -v agenthq >/dev/null 2>&1; }
progress() { # verb args... (CLI form)
  direct_progress && agenthq progress "$@" >/dev/null 2>&1 || true
}
progress_plan() {
  echo "##infantry plan $(echo $STAGES | tr ' ' '|')"
  # shellcheck disable=SC2086
  progress plan $STAGES --label "gate $(echo "$SHA" | cut -c1-8)"
}
progress_step() { echo "##infantry step $1"; progress step "$1"; }
progress_count() { echo "##infantry count $1 $2 $3"; progress count "$1" "$2" "$3"; }
progress_end() { echo "##infantry $1 $2"; progress "$1" "$2"; }

SUMMARY=""; FAILED=0; FAILNAMES=""; TOTAL_TESTS=0; TOTAL_BAD=0
progress_plan
stage() { # name gradle-task project-dir
  name=$1; task=$2; dir=$3
  if ! check_disk; then SUMMARY="$SUMMARY $name=ABORT(disk)"; FAILED=1; return; fi
  # TEST-SPEED-2: --rerun forces THIS test task to execute (never up-to-date, never FROM-CACHE), so every test really
  # runs; compilation is reused (build cache, then up-to-date) instead of recompiling all modules once per stage.
  note "stage $name: ${NICE_CMD:+$NICE_CMD }gradlew $task --rerun --no-daemon $CACHE_FLAG $WORKERS_FLAG"
  # shellcheck disable=SC2086
  (cd "$WT" && $NICE_CMD ./gradlew "$task" --rerun --no-daemon $CACHE_FLAG $WORKERS_FLAG --console=plain) > "$OUT/$name.log" 2>&1
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
  TOTAL_TESTS=$((TOTAL_TESTS + $1)); TOTAL_BAD=$((TOTAL_BAD + $3 + $4))
  progress_step "$name"
  sync; sleep 2   # let file handles from the finished JVM release before the next stage
}

stage agentHq       :superiorSoftLibrary:agentHq:test            superiorSoftLibrary/agentHq/build/test-results/test
stage uiCore        :superiorSoftLibrary:agenthqUiCore:jvmTest   superiorSoftLibrary/agenthqUiCore/build/test-results/jvmTest
stage agentHqUi     :superiorSoftLibrary:agentHqUi:test          superiorSoftLibrary/agentHqUi/build/test-results/test
stage ownerSmoke    :superiorSoftLibrary:agentHqUi:ownerSmoke    superiorSoftLibrary/agentHqUi/build/test-results/ownerSmoke

if [ $FAILED -eq 0 ]; then R=PASS; else R=FAIL; fi
progress_count $((TOTAL_TESTS - TOTAL_BAD)) "$TOTAL_TESTS" "tests passed ($TOTAL_BAD failed)"
if [ $FAILED -eq 0 ]; then progress_end done "gate PASS"; else progress_end fail "gate FAIL"; fi
echo "GATE $R $SHA${FULL:+ (full)}:$SUMMARY${FAILNAMES:+ failing:$FAILNAMES} logs=$OUT"
[ $FAILED -eq 0 ]
