#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# UI-DRIVER-1: start, drive and stop a TEST instance of the desktop app through its local API (docs/wiki/UI-driver.md).
# The app renders offscreen (no window), on an in-process test daemon with temp state; actions are Compose semantics
# actions inside that scene, never OS mouse/keyboard input.
#   scripts/ui-driver.sh up    [--port <18900-18999>] [--size 1400x860] [--timeout <s>]   (prints the port when ready)
#   scripts/ui-driver.sh down  --instance <port>                                          (quit; kill by PID if it hangs)
#   scripts/ui-driver.sh list
#   scripts/ui-driver.sh <verb> --instance <port> [selector] [options] [--json '<object>']
#     verbs:    health | tree | query | click | type | key | scroll | wait | shot | quit
#     selector: --tag <t> --tag-prefix <p> --text <t> --desc <d> [--substring] [--unmerged] [--index <n>]
#     options:  --input <text> (type) --replace --ime | --key Escape|Enter|Tab|... (key) | --to-index <n> (scroll)
#               --gone --count <n> --timeout-ms <n> (wait)
#               --ms <n> (wait without a selector: a real-time pause) | --name <shot name> | --limit <n> (query)
# Ports: 18900-18999 only; 8787/8788/8797/8798 are refused. The per-run token is read from the instance file and handed
# to curl on stdin; it is never printed or put on a command line.
set -u
TMPD="${TEMP:-${TMPDIR:-/tmp}}"; command -v cygpath >/dev/null 2>&1 && TMPD="$(cygpath -u "$TMPD")"
REG="$TMPD/infantry-ui-driver"
die() { echo "ui-driver: $*" >&2; exit 1; }
esc() { local s="$1"; s="${s//\\/\\\\}"; s="${s//\"/\\\"}"; s="${s//$'\n'/\\n}"; s="${s//$'\r'/}"; s="${s//$'\t'/\\t}"; printf '%s' "$s"; }
field() { sed -n "s/.*\"$2\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" "$1"; }
cmd="${1:-}"; shift || true
instance=""; port=""; size="1400x860"; tmo=600; json=""; pairs=()
add_s() { pairs+=("\"$1\":\"$(esc "$2")\""); }
add_r() { pairs+=("\"$1\":$2"); }
num() { case "$2" in ''|*[!0-9]*) die "$1 needs a number, got '$2'";; esac; }
while [ $# -gt 0 ]; do case "$1" in
  --instance) instance="$2"; shift 2;; --port) port="$2"; shift 2;; --size) size="$2"; shift 2;; --timeout) tmo="$2"; shift 2;;
  --json) json="$2"; shift 2;;
  --tag) add_s tag "$2"; shift 2;; --tag-prefix) add_s tagPrefix "$2"; shift 2;; --text) add_s text "$2"; shift 2;;
  --desc) add_s desc "$2"; shift 2;; --input) add_s input "$2"; shift 2;; --name) add_s name "$2"; shift 2;; --key) add_s key "$2"; shift 2;;
  --index) num "$1" "$2"; add_r index "$2"; shift 2;; --to-index) num "$1" "$2"; add_r toIndex "$2"; shift 2;;
  --count) num "$1" "$2"; add_r count "$2"; shift 2;; --timeout-ms) num "$1" "$2"; add_r timeoutMs "$2"; shift 2;;
  --ms) num "$1" "$2"; add_r ms "$2"; shift 2;; --limit) num "$1" "$2"; add_r limit "$2"; shift 2;;
  --substring) add_r substring true; shift;; --unmerged) add_r unmerged true; shift;; --gone) add_r gone true; shift;;
  --replace) add_r replace true; shift;; --ime) add_r ime true; shift;;
  *) die "unknown flag '$1' (see the header of $0)";; esac; done
owner_port() { case "$1" in 8787|8788|8797|8798) return 0;; esac; return 1; }
in_range() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 18900 ] && [ "$1" -le 18999 ]; }
inuse() { [ -f "$REG/$1.json" ] || (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
alive() { local pid; pid="$(field "$1" pid)"; [ -n "$pid" ] && { kill -0 "$pid" 2>/dev/null || tasklist //FI "PID eq $pid" 2>/dev/null | grep -q " $pid "; }; }
call() { # <verb> <body> [accept]
  local file="$REG/$instance.json" tok
  [ -f "$file" ] || die "no instance on port $instance (scripts/ui-driver.sh list)"
  tok="$(field "$file" token)"; [ -n "$tok" ] || die "instance file $file has no token"
  printf 'header = "Authorization: Bearer %s"\n' "$tok" | curl -sS --fail-with-body -K - -X POST \
    -H "Content-Type: application/json" ${3:+-H "Accept: $3"} --data-binary "$2" "http://127.0.0.1:$instance/ui/$1"
  local rc=$?; echo; return $rc
}
case "$cmd" in
up)
  mkdir -p "$REG"
  if [ -z "$port" ]; then for p in $(seq 18900 18999); do inuse "$p" || { port=$p; break; }; done; fi
  [ -n "$port" ] || die "no free port in 18900-18999"
  owner_port "$port" && die "port $port belongs to the owner"
  in_range "$port" || die "port $port is outside 18900-18999"
  inuse "$port" && die "port $port is taken"
  root="$(cd "$(dirname "$0")/.." && pwd)"; log="$REG/$port.log"
  # Fully detached: every std stream is redirected BEFORE the fork, so the caller's pipe is never held open (a caller
  # such as `up | tail` or an agent's shell gets EOF as soon as `up` returns). setsid where the shell has it.
  sid=""; command -v setsid >/dev/null 2>&1 && sid="setsid"
  ( cd "$root" || exit 1; exec </dev/null >"$log" 2>&1
    INFANTRY_UI_DRIVER=1 $sid nohup ./gradlew :superiorSoftLibrary:infantryUi:runUiDriver -PuiDriver=1 \
      -PuiDriverPort="$port" -PuiDriverSize="$size" --console=plain & )
  echo "ui-driver: starting on $port (log $log); the first start compiles the test classes"
  end=$(( $(date +%s) + tmo ))
  while [ "$(date +%s)" -lt "$end" ]; do
    [ -f "$REG/$port.json" ] && grep -q "ui-driver: ready" "$log" 2>/dev/null && { echo "ui-driver: ready on $port"; exit 0; }
    grep -qE "refused to start|BUILD FAILED|FAILURE:" "$log" 2>/dev/null && { tail -n 15 "$log" >&2; die "start failed"; }
    sleep 2
  done
  tail -n 15 "$log" >&2; die "not ready after ${tmo}s";;
down)
  [ -n "$instance" ] || die "down needs --instance <port>"
  file="$REG/$instance.json"; [ -f "$file" ] || die "no instance on port $instance"
  pid="$(field "$file" pid)"
  call quit '{}' >/dev/null 2>&1 || true
  for _ in $(seq 1 15); do [ -f "$file" ] || break; sleep 1; done
  if [ -f "$file" ] && [ -n "$pid" ]; then taskkill //PID "$pid" //T //F >/dev/null 2>&1 || kill "$pid" 2>/dev/null; rm -f "$file"; fi
  echo "ui-driver: stopped $instance";;
list)
  for f in "$REG"/*.json; do [ -f "$f" ] || continue; p="$(basename "$f" .json)"
    if alive "$f"; then echo "$p pid=$(field "$f" pid) out=$(field "$f" out)"; else echo "$p (stale: process gone)"; fi; done;;
health|tree|query|click|type|key|scroll|wait|shot|quit)
  [ -n "$instance" ] || die "$cmd needs --instance <port>"
  in_range "$instance" || die "--instance must be a port in 18900-18999"
  if [ -n "$json" ]; then body="$json"; else body="{$(IFS=,; echo "${pairs[*]:-}")}"; fi
  if [ "$cmd" = tree ]; then call tree "$body" "text/plain"; else call "$cmd" "$body"; fi;;
*) die "usage: $0 up|down|list|health|tree|query|click|type|key|scroll|wait|shot|quit (see the header)";;
esac
