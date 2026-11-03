#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# Smoke test for the headless daemon image + docker/compose.yaml.
#   container up -> /health -> a ticket started with a fake harness reaches a session -> restart keeps the data.
# Usage:  docker/smoke.sh [image]        (default: INFANTRY_IMAGE or infantryai/infantry-ai:dev; build it first, see docker/README.md)
# Uses a throwaway compose project, a random token and port 18787; removes its containers and volumes on exit.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"   # pwd -W: a Windows path under Git Bash (MSYS_NO_PATHCONV below)
export INFANTRY_IMAGE="${1:-${INFANTRY_IMAGE:-infantryai/infantry-ai:dev}}"
export COMPOSE_PROJECT_NAME="infantry-smoke-$$"
export INFANTRY_HOST_PORT="${SMOKE_PORT:-18787}"
SECRETS="$(mktemp -d)"
export INFANTRY_SECRETS_DIR="$(cd "$SECRETS" && (pwd -W 2>/dev/null || pwd))"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows must not rewrite /data, /work
DC=(docker compose -f "$HERE/compose.yaml")
URL="http://127.0.0.1:${INFANTRY_HOST_PORT}"

fail() { echo "SMOKE FAIL: $*" >&2; "${DC[@]}" logs --tail 80 infantry-ai >&2 || true; exit 1; }
cleanup() { "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$SECRETS"; }
trap cleanup EXIT

# Secrets: random, outside the repo, readable by the container user (uid 10001) only through the bind mount.
if command -v openssl >/dev/null; then
  openssl rand -hex 32 > "$SECRETS/infantry_token"; openssl rand -hex 32 > "$SECRETS/infantry_keystore_key"
else
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$SECRETS/infantry_token"
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$SECRETS/infantry_keystore_key"
fi
chmod 644 "$SECRETS"/infantry_*

# infantry inside the container, talking to the daemon over loopback with the mounted token.
ah() { "${DC[@]}" exec -T infantry-ai infantry --url ws://127.0.0.1:8787/ws --token-file /run/secrets/infantry_token "$@"; }
sh_in() { "${DC[@]}" exec -T infantry-ai sh -c "$1"; }

wait_health() {
  for _ in $(seq 1 60); do
    if curl -fsS "$URL/health" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "/health did not answer within 120 s"
}

echo "== 1. start without a token must refuse when generation is off (strict token rule)"
if docker run --rm -e INFANTRY_HOST=0.0.0.0 -e INFANTRY_TOKEN_AUTO=false "$INFANTRY_IMAGE" >/tmp/smoke-notoken.$$ 2>&1 & pid=$!; then
  for _ in $(seq 1 30); do kill -0 $pid 2>/dev/null || break; sleep 1; done
  if kill -0 $pid 2>/dev/null; then kill $pid 2>/dev/null || true; fail "daemon kept running with no token"; fi
  rm -f /tmp/smoke-notoken.$$
fi

echo "== 2. compose up, /health"
"${DC[@]}" up -d
wait_health
curl -fsS "$URL/health" | grep -q . || fail "empty /health body"
code="$(curl -s -o /dev/null -w '%{http_code}' "$URL/projects" || true)"
[ "$code" != "200" ] || fail "an authenticated route answered 200 without a token"

echo "== 3. runs as non-root"
[ "$(sh_in 'id -u')" = "10001" ] || fail "container is not uid 10001"

echo "== 4. fake harness + a git repo, then restart so the descriptor is read"
sh_in 'mkdir -p /data/harnesses /work/repo &&
  printf "%s" "{\"id\":\"fake\",\"name\":\"Fake\",\"command\":\"sh\",\"args\":[\"-c\",\"echo fake-harness-ok\"],\"capabilities\":[],\"output\":{\"format\":\"text\"}}" > /data/harnesses/fake.json &&
  cd /work/repo && git init -q -b main && git config user.email smoke@example.com && git config user.name smoke &&
  echo smoke > README.md && git add README.md && git commit -q -m init'
"${DC[@]}" restart infantry-ai
wait_health

echo "== 5. ticket with the fake harness starts a session"
ah harnesses | grep -qi fake || fail "the fake harness is not listed by 'infantry harnesses'"
ah ticket create --title "docker smoke" --desc "say hello" --role backend --provider fake >/tmp/smoke-ticket.$$ 2>&1 || { cat /tmp/smoke-ticket.$$ >&2; fail "ticket create"; }
TID="$(grep -oE '[0-9a-f]{8}' /tmp/smoke-ticket.$$ | head -1)"; rm -f /tmp/smoke-ticket.$$
[ -n "$TID" ] || fail "could not read the ticket id from 'ticket create'"
ah ticket start "$TID" || fail "ticket start"
for _ in $(seq 1 30); do
  ah ticket show "$TID" 2>/dev/null | grep -qiE 'in_progress|done' && break
  sleep 2
done
ah ticket show "$TID" | grep -qiE 'in_progress|done' || fail "ticket never left todo"

echo "== 6. data survives a container re-create (the volume, not the container, holds the state)"
"${DC[@]}" down            # keeps volumes
"${DC[@]}" up -d
wait_health
ah ticket show "$TID" | grep -qi 'docker smoke' || fail "ticket lost after down/up"

echo "SMOKE OK ($INFANTRY_IMAGE)"
