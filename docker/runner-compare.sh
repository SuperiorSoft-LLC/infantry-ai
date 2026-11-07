#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# DOCKER-IMAGE-1 (ticket 98add1f4): the same pinned-ticket scenario against a CONTAINER runner and a NATIVE `--mode runner`
# daemon, each paired with a test orchestrator daemon on the host; prints a native-vs-container table.
#   docker/runner-compare.sh <image> <context-dir>   (context-dir = superiorSoftLibrary/infantry/build/docker; its infantry/ is the native build)
# Test ports only (189xx), temp state dirs, container name prefix infantry-test-, no mount of any real state dir or ~/.claude.
# Exit 1 when a step fails. A difference between the two runners is shown in the table, not hidden.
set -uo pipefail
IMAGE="${1:?image}"; CTX="${2:?context dir}"
BIN="$CTX/infantry/bin/infantry"
T="$(mktemp -d)"; ORCH_PORT=18931; NATIVE_PORT=18932; CONT_PORT=18933; NLINK=18934; CLINK=18935
CN="infantry-test-runner-$$"
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; docker rm -f "$CN" >/dev/null 2>&1 || true; rm -rf "$T"; }
trap cleanup EXIT
clean_env() { env -u INFANTRY_URL -u INFANTRY_TOKEN -u INFANTRY_TOKEN_FILE -u INFANTRY_MODE -u INFANTRY_AGENT_ID -u INFANTRY_SESSION_ID "$@"; }
wait_http() { for _ in $(seq 1 90); do curl -fsS "$1" >/dev/null 2>&1 && return 0; sleep 2; done; echo "timeout: $1" >&2; return 1; }
FAKE='{"id":"fake","name":"Fake","command":"sh","args":["-c","echo fake-harness-ok"],"capabilities":[],"output":{"format":"text"}}'
mkrepo() { mkdir -p "$1" && git -C "$1" init -q -b main && git -C "$1" config user.email t@example.com && git -C "$1" config user.name t \
  && echo x > "$1/README.md" && git -C "$1" add . && git -C "$1" commit -q -m init; }
declare -A R   # results: R[<runner>.<check>]

# ---- orchestrator (native, --mode both)
mkdir -p "$T/orch/harnesses"; echo "$FAKE" > "$T/orch/harnesses/fake.json"; echo orchtoken > "$T/orch/tok"
clean_env INFANTRY_STATE_DIR="$T/orch" INFANTRY_HOME="$T/orch" INFANTRY_PORT=$ORCH_PORT INFANTRY_TOKEN_FILE="$T/orch/tok" \
  "$BIN" daemon --mode both >"$T/orch.log" 2>&1 & PIDS+=($!)
wait_http "http://127.0.0.1:$ORCH_PORT/health" || { tail -n 20 "$T/orch.log"; exit 1; }
OAH() { clean_env "$BIN" --url "ws://127.0.0.1:$ORCH_PORT/ws" --token-file "$T/orch/tok" "$@"; }

# ---- native runner
mkdir -p "$T/nat/harnesses"; echo "$FAKE" > "$T/nat/harnesses/fake.json"; echo nattoken > "$T/nat/tok"; mkrepo "$T/nat-repo"
clean_env INFANTRY_STATE_DIR="$T/nat" INFANTRY_HOME="$T/nat" INFANTRY_PORT=$NATIVE_PORT INFANTRY_TOKEN_FILE="$T/nat/tok" \
  INFANTRY_LIMIT_RUNNERS_LINKPORT=$NLINK "$BIN" daemon --mode runner >"$T/nat.log" 2>&1 & PIDS+=($!)
wait_http "http://127.0.0.1:$NATIVE_PORT/health" || { tail -n 20 "$T/nat.log"; exit 1; }
NAH() { clean_env "$BIN" --url "ws://127.0.0.1:$NATIVE_PORT/ws" --token-file "$T/nat/tok" "$@"; }

# ---- container runner (no host mounts; the link port is published on loopback only)
docker run -d --name "$CN" -e INFANTRY_MODE=runner -e INFANTRY_LIMIT_RUNNERS_LINKPORT=8790 \
  -p 127.0.0.1:$CONT_PORT:8787 -p 127.0.0.1:$CLINK:8790 "$IMAGE" >/dev/null || exit 1
wait_http "http://127.0.0.1:$CONT_PORT/health" || { docker logs "$CN" | tail -n 20; exit 1; }
CAH() { docker exec "$CN" infantry --url ws://127.0.0.1:8787/ws --token-file /data/access-token "$@"; }
docker exec "$CN" sh -c "mkdir -p /data/harnesses /work/repo && printf '%s' '$FAKE' > /data/harnesses/fake.json \
  && cd /work/repo && git init -q -b main && git config user.email t@example.com && git config user.name t \
  && echo x > README.md && git add . && git commit -q -m init" || exit 1
docker restart "$CN" >/dev/null; wait_http "http://127.0.0.1:$CONT_PORT/health" || exit 1

# ---- pair (the runner side blocks serving the link until paired; capture its code and fingerprint)
pair() { # <name> <runner-cli function> <link port> <log>
  "$2" runner pair --name "$1" >"$4" 2>&1 & PIDS+=($!)
  for _ in $(seq 1 30); do grep -qi 'fingerprint' "$4" && break; sleep 1; done
  local code fp
  code="$(grep -oiE 'code[: ]+[A-Za-z0-9-]{6,}' "$4" | head -1 | awk '{print $NF}')"; fp="$(grep -oiE '[0-9a-f:]{40,}' "$4" | head -1)"
  if OAH runner add "$1" "wss://127.0.0.1:$3" --code "$code" --fingerprint "$fp" >"$4.add" 2>&1; then R[$1.pair]=ok; else R[$1.pair]="FAIL: $(tail -n 1 "$4.add")"; fi
}
pair native NAH $NLINK "$T/pair-native.log"; pair container CAH $CLINK "$T/pair-container.log"
OAH settings set runners.enabled true >/dev/null 2>&1 || true

# ---- the scenario, once per runner
scenario() { # <runner name> <repo path on the runner> <command prefix that runs a shell command on the runner...>
  local n="$1" repo="$2"; shift 2
  local id out
  out="$(OAH ticket create --title "compare $n" --desc "say hello" --role backend --provider fake --runner "$n" 2>&1)"
  id="$(grep -oE '[0-9a-f]{8}' <<<"$out" | head -1)"
  [ -n "$id" ] || { R[$n.start]="FAIL: no ticket id"; return; }
  R[$n.id]="$id"
  OAH ticket start "$id" >/dev/null 2>&1 || { R[$n.start]="FAIL: ticket start"; return; }
  for _ in $(seq 1 45); do OAH ticket show "$id" 2>/dev/null | grep -qiE 'in_progress|done|review' && break; sleep 2; done
  R[$n.start]="$(OAH ticket show "$id" 2>/dev/null | grep -oiE 'in_progress|done|review|todo' | head -1)"
  sleep 5
  R[$n.events]="harness output lines: $(OAH ticket show "$id" 2>&1 | grep -c 'fake-harness-ok')"
  R[$n.branch]="$("$@" git -C "$repo" branch --list 'ticket/*' 2>/dev/null | wc -l) ticket branch(es) on the runner repo"
  R[$n.usage]="$(OAH usage 2>&1 | head -1 | cut -c1-60)"
}
scenario native "$T/nat-repo"
scenario container /work/repo docker exec "$CN"

# ---- resume: restart the container; the daemon must come back and still know the ticket
docker restart "$CN" >/dev/null
if wait_http "http://127.0.0.1:$CONT_PORT/health"; then
  R[container.resume]="daemon back; ticket ${R[container.id]:-?}: $(OAH ticket show "${R[container.id]:-x}" 2>/dev/null | grep -oiE 'in_progress|done|review|todo' | head -1)"
else R[container.resume]="FAIL: daemon did not return"; fi
R[native.resume]="not driven (same jar)"

SIZE="$(docker image inspect "$IMAGE" --format '{{.Size}}' 2>/dev/null | awk '{printf "%.0f MB", $1/1048576}')"
echo; echo "| check | native runner | container runner |"; echo "|---|---|---|"
for c in pair start events branch usage resume; do echo "| $c | ${R[native.$c]:--} | ${R[container.$c]:--} |"; done
echo; echo "image size: $SIZE"
bad=0; for k in "${!R[@]}"; do case "${R[$k]}" in FAIL*) bad=1;; esac; done
exit $bad
