#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RR-12: the Docker E2E of the remote runner. One run pairs a CONTAINER runner (the same infantry-daemon binary in --mode runner),
# gets ping/pong, installs a harness on it, runs a ticket on it, and restarts / recreates / kills it. Prints a PASS/FAIL table and
# exits 1 on any FAIL. Everything is driven through the daemon API/CLI the UI uses; `docker exec` only asserts.
#
#   scripts/runner-e2e.sh                    # image = $E2E_IMAGE (built from docker/Dockerfile.rust when missing or E2E_BUILD=1)
#
# Setup (all test-only, nothing of the owner's is touched or mounted):
#   - a native orchestrator test daemon (--mode both) on a 189xx port with a temp state dir (and a temp CLI state dir: the link
#     keystore lives there, never in ~/.infantry);
#   - container `infantry-test-orch` = the git ORIGIN of the test project (git daemon, seeded), published on loopback only;
#   - container `infantry-test-runner` = the runner (named volume infantry-test-runner-data), link port on loopback only.
#   At most those 2 containers; both are removed and recreated by name. The image is kept.
# Environment:
#   E2E_IMAGE            runner image (default infantry-runner-rust:e2e)       E2E_BUILD=1   rebuild it first
#   E2E_BIN_DIR          dir with infantry-daemon[.exe] and infantry[.exe] (default: the shared owner-test target dir)
#   E2E_PORT_BASE        first of 4 consecutive ports (default 18940; never 8787/8788/8797/8798/18990)
#   E2E_HARNESS/E2E_MODEL  the harness and model of the ticket runs (default opencode / opencode/beam-free: a keyless free model)
#   E2E_COMMANDCODE_KEY_FILE  a file holding a Command Code key: the harness becomes pi on commandcode/<E2E_MODEL> and the key is
#                        lent by the orchestrator (RR-6). The key is read from the file, piped on stdin, never put in argv or logs.
#   E2E_KEEP=1           leave containers and temp dirs after the run (debugging)
#   E2E_ONLY="S0 S1"     run only those scenarios (the setup always runs)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${E2E_IMAGE:-infantry-runner-rust:e2e}"
PORT_BASE="${E2E_PORT_BASE:-18940}"
ORCH_PORT=$PORT_BASE; LINK_PORT=$((PORT_BASE + 1)); ORIGIN_PORT=$((PORT_BASE + 2))
RUNNER_CT="infantry-test-runner"; ORIGIN_CT="infantry-test-orch"; VOLUME="infantry-test-runner-data"
RNAME="e2e-box"
case "$ORCH_PORT $LINK_PORT $ORIGIN_PORT" in *8787*|*8788*|*8797*|*8798*|*18990*) echo "E2E_PORT_BASE collides with an owner port" >&2; exit 2 ;; esac

WIN=0; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WIN=1 ;; esac
EXE=""; [ "$WIN" = 1 ] && EXE=".exe"
if [ -n "${E2E_BIN_DIR:-}" ]; then BIN="$E2E_BIN_DIR"
elif [ "$WIN" = 1 ]; then BIN="${INFANTRY_CARGO_TARGET_DIR:-${LOCALAPPDATA:-$HOME/AppData/Local}/infantry-dev/preview-target}/owner-test"
else BIN="$ROOT/rust/target/debug"; fi
DAEMON="$BIN/infantry-daemon$EXE"; CLI="$BIN/infantry$EXE"
[ -x "$DAEMON" ] && [ -x "$CLI" ] || { echo "missing $DAEMON or $CLI: build them (cargo build -p infantry-distribution -p infantry-cli) or set E2E_BIN_DIR" >&2; exit 2; }
command -v docker >/dev/null && docker info >/dev/null 2>&1 || { echo "docker is not running (Docker Desktop may be started by command)" >&2; exit 2; }
command -v node >/dev/null || { echo "node is needed (scripts/lib/e2e-mcp.mjs)" >&2; exit 2; }
native_path() { if [ "$WIN" = 1 ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

HARNESS="${E2E_HARNESS:-opencode}"; MODEL="${E2E_MODEL:-opencode/beam-free}"
if [ -n "${E2E_COMMANDCODE_KEY_FILE:-}" ]; then HARNESS=pi; MODEL="${E2E_MODEL:-commandcode/poolside/laguna-s-2.1-free}"; fi

T="$(mktemp -d)"; PIDS=()
log() { printf '[runner-e2e] %s\n' "$*"; }
cleanup() {
  [ "${E2E_KEEP:-0}" = 1 ] && { log "E2E_KEEP=1: leaving $T and the containers"; return; }
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  if [ "$WIN" = 1 ]; then  # an MSYS kill does not reach the native daemon: kill by the pid listening on OUR orchestrator port (never by image name)
    local wp; wp="$(netstat -ano 2>/dev/null | tr -d '' | awk -v p=":$ORCH_PORT" '$2 ~ p"$" && /LISTENING/ {print $5; exit}')"
    [ -n "$wp" ] && taskkill //PID "$wp" //F >/dev/null 2>&1; sleep 1
  fi
  docker rm -f "$RUNNER_CT" "$ORIGIN_CT" >/dev/null 2>&1
  docker volume rm "$VOLUME" >/dev/null 2>&1
  rm -rf "$T"
}
trap cleanup EXIT

# ---- results table -------------------------------------------------------------------------------------------------
declare -a ROW_ID ROW_RES ROW_NOTE
row() { ROW_ID+=("$1"); ROW_RES+=("$2"); ROW_NOTE+=("$3"); log "$1 $2: $3"; }
want() { [ -z "${E2E_ONLY:-}" ] || case " $E2E_ONLY " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
redact() { grep -v -E '^(bearer|credential):' ; }

# ---- helpers -------------------------------------------------------------------------------------------------------
clean() { env -u INFANTRY_URL -u INFANTRY_TOKEN -u INFANTRY_TOKEN_FILE -u INFANTRY_PORT -u INFANTRY_MODE -u INFANTRY_AGENT_ID -u INFANTRY_SESSION_ID -u INFANTRY_PROJECT_ID "$@"; }
# The orchestrator CLI: its own state dir (the link keystore of `runner add` lives there), the test daemon's url and token.
I() { clean INFANTRY_STATE_DIR="$T/orch" "$CLI" --url "ws://127.0.0.1:$ORCH_PORT/ws" --token-file "$T/orch/tok" "$@" 2>&1 | grep -v '^hint:'; }
M() { clean INFANTRY_STATE_DIR="$T/orch" node "$ROOT/scripts/lib/e2e-mcp.mjs" "$CLI" "ws://127.0.0.1:$ORCH_PORT/ws" "$T/orch/tok" "$@" 2>/dev/null; }
wait_http() { local n; for n in $(seq 1 "${2:-60}"); do curl -fsS "$1" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
# wait_for <seconds> <description> <command...>: poll a command until it succeeds.
wait_for() { local s="$1"; shift; local what="$1"; shift; local end=$((SECONDS + s)); while [ $SECONDS -lt $end ]; do "$@" && return 0; sleep 3; done; log "timed out after ${s}s waiting for $what"; return 1; }
newest_bearer() { docker logs "$RUNNER_CT" 2>&1 | awk '/^bearer:/ {b=$2} END {print b}'; }
runner_start() { # <extra docker args...>
  docker rm -f "$RUNNER_CT" >/dev/null 2>&1
  docker run -d --name "$RUNNER_CT" -v "$VOLUME:/data" -e INFANTRY_RUNNER_NAME="$RNAME" -e INFANTRY_RUNNER_HOST=127.0.0.1 \
    -e INFANTRY_RUNNER_PUBLIC_PORT="$LINK_PORT" -p "127.0.0.1:$LINK_PORT:8790" "$@" "$IMAGE" >/dev/null
}
runner_up() { wait_for 60 "the runner banner" sh -c "docker logs $RUNNER_CT 2>&1 | grep -q '^bearer:'"; }

# ---- image ---------------------------------------------------------------------------------------------------------
if [ "${E2E_BUILD:-0}" = 1 ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  log "building $IMAGE (docker/Dockerfile.rust)"
  docker build -f "$ROOT/docker/Dockerfile.rust" --build-arg CARGO_PROFILE="${E2E_CARGO_PROFILE:-owner-test}" -t "$IMAGE" "$ROOT" >"$T/build.log" 2>&1 \
    || { tail -n 20 "$T/build.log"; echo "image build failed" >&2; exit 2; }
fi

# ---- the test origin (container) -----------------------------------------------------------------------------------
docker rm -f "$ORIGIN_CT" "$RUNNER_CT" >/dev/null 2>&1; docker volume rm "$VOLUME" >/dev/null 2>&1
docker run -d --name "$ORIGIN_CT" --entrypoint sh -p "127.0.0.1:$ORIGIN_PORT:9418" "$IMAGE" -c '
  set -e; git init -q --bare -b main /work/repo.git; git init -q -b main /tmp/seed; cd /tmp/seed
  git config user.email e2e@example.com; git config user.name e2e; echo "# e2e project" > README.md; git add .; git commit -qm init
  git push -q /work/repo.git main
  exec git daemon --base-path=/work --export-all --enable=receive-pack --reuseaddr /work' >/dev/null || { echo "origin container failed" >&2; exit 2; }
wait_for 30 "the origin" git ls-remote "git://127.0.0.1:$ORIGIN_PORT/repo.git" >/dev/null || exit 2
ORIGIN_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$ORIGIN_CT")"
ORIGIN_URL="git://$ORIGIN_IP:9418/repo.git"     # what the runner container clones from (container to container)
mkdir -p "$T/work"
git clone -q "git://127.0.0.1:$ORIGIN_PORT/repo.git" "$T/work/project" || { echo "clone of the test origin failed" >&2; exit 2; }
git -C "$T/work/project" remote set-url origin "$ORIGIN_URL"
git -C "$T/work/project" config user.email e2e@example.com; git -C "$T/work/project" config user.name e2e
origin_branches() { git ls-remote --heads "git://127.0.0.1:$ORIGIN_PORT/repo.git" 2>/dev/null; }

# ---- the orchestrator (native test daemon) ------------------------------------------------------------------------
mkdir -p "$T/orch"; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$T/orch/tok"
clean INFANTRY_STATE_DIR="$T/orch" INFANTRY_TOKEN_FILE="$T/orch/tok" "$DAEMON" --port "$ORCH_PORT" --mode both >"$T/orch.log" 2>&1 &
PIDS+=($!)
wait_http "http://127.0.0.1:$ORCH_PORT/health" 60 || { tail -n 20 "$T/orch.log"; echo "orchestrator did not start" >&2; exit 2; }
if [ -n "${E2E_COMMANDCODE_KEY_FILE:-}" ]; then
  # lent to every remote start (RR-6); stdin only
  tr -d '\r\n' < "$E2E_COMMANDCODE_KEY_FILE" | I harness key commandcode >/dev/null
fi
# The owner's click on every approval card (the harness asks about each shell command): a node client answering allow_once.
node "$ROOT/scripts/lib/e2e-approver.mjs" "ws://127.0.0.1:$ORCH_PORT/ws" "$(native_path "$T/orch/tok")" 7200 >"$T/approver.log" 2>&1 &
PIDS+=($!)
PROJECT="$(I project create e2e --location "$(native_path "$T/work/project")" | grep -o 'project-[0-9a-f]*' | head -1)"
[ -n "$PROJECT" ] || { echo "project create failed" >&2; exit 2; }

# ---- the runner container, paired ----------------------------------------------------------------------------------
runner_start; runner_up || { docker logs "$RUNNER_CT" 2>&1 | redact | tail -n 15; echo "runner container did not print its banner" >&2; exit 2; }
PAIR_OUT="$(newest_bearer | I runner add "wss://127.0.0.1:$LINK_PORT" --bearer - | redact)"
log "pair: $(printf '%s' "$PAIR_OUT" | head -n 1 | cut -c1-120)"
FP0="$(newest_bearer | sed 's/.*\.\([^.]*\)\.[^.]*$/\1/' | cut -c1-16)"     # the pin part of the bearer (not the secret)
CT_HOST="$(docker inspect -f '{{.Config.Hostname}}' "$RUNNER_CT")"

# ---- ticket helpers ------------------------------------------------------------------------------------------------
# new_ticket <title> <runner> <desc>: prints the 8-char id
new_ticket() {
  I ticket create --project "$PROJECT" --title "$1" --desc "$3" --role backend --provider "$HARNESS" --model "$MODEL" --runner "$2" | grep -oE 'created [0-9a-f]{8}' | awk '{print $2}'
}
start_ticket() { I ticket start --project "$PROJECT" "$1"; }
# session id of a ticket's worker: the first session-looking id on a list line that names the ticket
session_of() { M infantry_list_sessions '{}' | grep -i "$1" | grep -oE '[0-9a-f]{8}-[0-9a-f-]{27}|session-[0-9a-f]+' | head -1; }
transcript() { M infantry_get_session "{\"sessionId\":\"$1\"}"; }
has_marker() { transcript "$1" | grep -q "$2"; }
MARK_DEADLINE="${E2E_TURN_SECONDS:-420}"

# one scenario run: create + start a ticket on a runner, wait for the marker word in the session. Sets TID, SID, TRANSCRIPT.
run_ticket() { # <label> <runner> <extra instruction>
  local label="$1" rn="$2" extra="${3:-}"
  local desc="You are in a git worktree. Run the shell command hostname and write its output to a file named host.txt, then commit the file with git. ${extra} Finish your reply with the single word E2E-DONE-${label}."
  TID="$(new_ticket "e2e $label" "$rn" "$desc")"; [ -n "$TID" ] || { START_ERR="ticket create failed"; return 1; }
  START_ERR="$(start_ticket "$TID")"; case "$START_ERR" in *error*) return 1 ;; esac
  wait_for 120 "the session of $TID" _have_session || { START_ERR="no session appeared for $TID"; return 1; }
  wait_for "$MARK_DEADLINE" "the marker E2E-DONE-$label" _have_marker "$label" || { TRANSCRIPT="$(transcript "$SID")"; START_ERR="no E2E-DONE-$label in the transcript"; return 1; }
  TRANSCRIPT="$(transcript "$SID")"; I ticket move --project "$PROJECT" "$TID" done >/dev/null  # frees the lane (one worker per role)
  return 0
}
_link_ok() { I runner check "$RNAME" | grep -qE '^link +ok'; }
_have_session() { SID="$(session_of "$TID")"; [ -n "$SID" ]; }
_have_marker() { has_marker "$SID" "E2E-DONE-$1"; }

# ============================ scenarios =============================================================================
# S0: install the harness ON the runner through runner_frame {harness_install}; harness_install_status then says installed.
harness_state() { I runner frame "$1" harness_install_status | tr '{' '\n' | grep "\"id\":\"$2\"" | grep -o '"state":"[a-z]*"' | head -1; }
if want S0; then
  I runner frame "$RNAME" harness_install --data "{\"ids\":[\"$HARNESS\"]}" >/dev/null
  ok=0; for _ in $(seq 1 140); do case "$(harness_state "$RNAME" "$HARNESS")" in *installed*) ok=1; break ;; esac; sleep 3; done
  [ $ok = 1 ] && row S0 PASS "$HARNESS installed on $RNAME through runner_frame harness_install" || row S0 FAIL "$HARNESS not installed on $RNAME: $(harness_state "$RNAME" "$HARNESS")"
fi

# S1: ping/pong over the link.
if want S1; then
  CHECK="$(I runner check "$RNAME" --harness "$HARNESS" 2>&1)"
  if printf '%s' "$CHECK" | grep -qE '^link +ok +pong' && printf '%s' "$CHECK" | grep -qE '^contract +ok'; then
    row S1 PASS "$(printf '%s' "$CHECK" | grep -E '^link' | head -1 | cut -c1-80)"
  else row S1 FAIL "$(printf '%s' "$CHECK" | head -n 4 | tr '\n' ' ' | cut -c1-200)"; fi
fi

# S2: a ticket pinned to the runner runs one turn ON the runner: the container's hostname is in the output, and the ticket
# branch is pushed to the test origin.
S2_OK=0
if want S2 || want S3 || want S4 || want S5 || want S6 || want S7; then
  if run_ticket S2 "$RNAME"; then
    BR="$(origin_branches | grep -o 'refs/heads/ticket/.*' | head -1)"
    if printf '%s' "$TRANSCRIPT" | grep -q "$CT_HOST" && [ -n "$BR" ]; then S2_OK=1; row S2 PASS "turn ran on the runner (hostname $CT_HOST); pushed ${BR#refs/heads/}"
    else row S2 FAIL "hostname in output: $(printf '%s' "$TRANSCRIPT" | grep -c "$CT_HOST"); branches on origin: ${BR:-none}"; fi
  else row S2 FAIL "$(printf '%s' "${START_ERR:-turn did not complete}" | head -n 1 | cut -c1-300)"; fi
fi

# S3: an approval card reaches the orchestrator (a command the harness must ask about; the card is answered, allowed).
if want S3; then
  A0="$(grep -c '^approved' "$T/approver.log")"
  if [ $S2_OK = 1 ] && run_ticket S3 "$RNAME" "Then run the shell command: curl -s -o /dev/null http://127.0.0.1:1/ (it needs approval)."; then
    row S3 PASS "turn with an approval-needing command finished; approval cards answered: $(( $(grep -c '^approved' "$T/approver.log") - A0 ))"
  else row S3 FAIL "${START_ERR:-needs S2}"; fi
fi

# S4: an MCP board call from the runner session works (the agent comments on its own ticket through the infantry tools).
if want S4; then
  if [ $S2_OK = 1 ] && run_ticket S4 "$RNAME" "Then use the infantry MCP tool infantry_ticket_comment to post the comment e2e-mcp-ok on this ticket."; then
    M infantry_ticket_comments "{\"ticketId\":\"$TID\"}" | grep -q 'e2e-mcp-ok' && row S4 PASS "comment e2e-mcp-ok on $TID posted from the runner session" || row S4 FAIL "no e2e-mcp-ok comment on $TID"
  else row S4 FAIL "${START_ERR:-needs S2}"; fi
fi

# S5 + S6 share one long-running session.
LONG_TID=""; LONG_SID=""
if want S5 || want S6; then
  if [ $S2_OK = 1 ]; then
    LONG_TID="$(new_ticket "e2e long" "$RNAME" "Run the shell command sleep 900 and wait for it. Do nothing else. When asked, reply with the word given.")"
    start_ticket "$LONG_TID" >/dev/null 2>&1; TID="$LONG_TID"; wait_for 120 "long session" _have_session && LONG_SID="$SID"
    sleep 20
  fi
fi

# S6 first (it needs the session alive): after `docker restart` the session's output continues (RR-8).
if want S6; then
  if [ -n "$LONG_SID" ]; then
    docker restart "$RUNNER_CT" >/dev/null; runner_up
    wait_for 90 "the link after the restart" _link_ok
    M infantry_prompt "{\"sessionId\":\"$LONG_SID\",\"prompt\":\"Reply with exactly E2E-RESUMED-OK\"}" >/dev/null
    ok=0; for _ in $(seq 1 60); do transcript "$LONG_SID" | grep -q 'E2E-RESUMED-OK' && { ok=1; break; }; sleep 3; done
    [ $ok = 1 ] && row S6 PASS "after docker restart the session answered again" || row S6 FAIL "no output after docker restart"
  else row S6 FAIL "needs S2 (no long-running session)"; fi
fi

# S5: kill reaches the runner; no harness process is left in the container.
if want S5; then
  if [ -n "$LONG_SID" ]; then
    M infantry_kill "{\"sessionId\":\"$LONG_SID\"}" >/dev/null
    sleep 8
    LEFT="$(docker exec "$RUNNER_CT" sh -c "ps -eo args 2>/dev/null | grep -E 'opencode|pi-coding-agent|/harness-tools/' | grep -v grep | wc -l" 2>/dev/null || echo '?')"
    [ "$LEFT" = 0 ] && row S5 PASS "kill reached the runner; 0 harness processes left" || row S5 FAIL "$LEFT harness process(es) left in $RUNNER_CT"
  else row S5 FAIL "needs S2 (no long-running session)"; fi
fi

[ -n "$LONG_TID" ] && I ticket move --project "$PROJECT" "$LONG_TID" done >/dev/null

# S7: docker rm + a new run on the same volume: no re-pair, the fingerprint is the same, the harness install (logins) is kept.
if want S7; then
  runner_start; runner_up
  FP1="$(newest_bearer | sed 's/.*\.\([^.]*\)\.[^.]*$/\1/' | cut -c1-16)"
  CHECK="$(I runner check "$RNAME" 2>&1)"
  if [ "$FP0" = "$FP1" ] && printf '%s' "$CHECK" | grep -qE '^link +ok +pong' && case "$(harness_state "$RNAME" "$HARNESS")" in *installed*) true ;; *) false ;; esac; then
    row S7 PASS "recreated on the same volume: same pin, link ok without re-pair, $HARNESS still installed"
  else row S7 FAIL "pin same: $([ "$FP0" = "$FP1" ] && echo yes || echo no); $(printf '%s' "$CHECK" | grep -E '^link' | head -1 | cut -c1-80); $HARNESS: $(harness_state "$RNAME" "$HARNESS")"; fi
fi

# S8: an unreachable runner refuses a start and no local session starts.
if want S8; then
  docker stop "$RUNNER_CT" >/dev/null 2>&1
  BEFORE="$(M infantry_list_sessions '{}' | grep -cE '[0-9a-f]{8}-[0-9a-f-]{27}|session-[0-9a-f]+')"
  U_TID="$(new_ticket "e2e unreachable" "$RNAME" "This must never run.")"
  U_OUT="$(start_ticket "$U_TID" 2>&1)"
  AFTER="$(M infantry_list_sessions '{}' | grep -cE '[0-9a-f]{8}-[0-9a-f-]{27}|session-[0-9a-f]+')"
  if printf '%s' "$U_OUT" | grep -qiE 'error|did not start|unreachable|refus' && [ "$AFTER" -le "$BEFORE" ]; then
    row S8 PASS "start refused ($(printf '%s' "$U_OUT" | head -n 1 | cut -c1-110)); no local session started"
  else row S8 FAIL "start answered: $(printf '%s' "$U_OUT" | head -n 1 | cut -c1-120); sessions $BEFORE -> $AFTER"; fi
  runner_start; runner_up
fi

# S9: the same ticket run on `local` (the embedded runner) passes with the same steps: one code path.
if want S9; then
  I runner frame local harness_install --data "{\"ids\":[\"$HARNESS\"]}" >/dev/null
  ok=0; for _ in $(seq 1 140); do case "$(harness_state local "$HARNESS")" in *installed*) ok=1; break ;; esac; sleep 3; done
  LOCAL_HOST="$(hostname)"
  if [ $ok = 1 ] && run_ticket S9 local && printf '%s' "$TRANSCRIPT" | grep -qi "$LOCAL_HOST"; then
    row S9 PASS "same run on local (hostname $LOCAL_HOST, not the container's $CT_HOST)"
  else row S9 FAIL "${START_ERR:-local run failed (harness installed on local: $ok)}"; fi
fi

# ---- the table -----------------------------------------------------------------------------------------------------
echo
printf '%-4s %-5s %s\n' ID RESULT DETAIL; printf '%-4s %-5s %s\n' ---- ----- ------
FAILS=0
for i in "${!ROW_ID[@]}"; do
  printf '%-4s %-5s %s\n' "${ROW_ID[$i]}" "${ROW_RES[$i]}" "${ROW_NOTE[$i]}"
  [ "${ROW_RES[$i]}" = PASS ] || FAILS=$((FAILS + 1))
done
echo
if [ $FAILS -gt 0 ]; then
  log "$FAILS scenario(s) FAILED. Orchestrator log tail:"; tail -n 8 "$T/orch.log" | cut -c1-200
  docker logs "$RUNNER_CT" 2>&1 | redact | tail -n 8 | cut -c1-200
  exit 1
fi
log "all scenarios passed"
