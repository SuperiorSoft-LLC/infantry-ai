#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# TESTDAEMON-START-1: start / stop a scrubbed TEST daemon from a built dist, for live repros (runner pairing etc.).
#   scripts/testdaemon.sh up   --name <n> [--mode both|runner|orchestrator] [--port <p>] [--dist <dir>] [--timeout <s>] [--state <dir>]
#   scripts/testdaemon.sh cli  --name <n> -- <infantry args...>     (the CLI pinned to that daemon + its state dir)
#   scripts/testdaemon.sh down --name <n>                           (kills by PID only, never by image name)
#   scripts/testdaemon.sh list
# `infantry daemon` does NOT exist: the daemon is com.superiorsoftllc.infantry.daemon.DaemonMainKt, launched here.
# --dist: an installDist folder (lib/*) or a packaged dist (app/*, runtime/); default superiorSoftLibrary/infantry/build/install/infantry.
# Ports: a free one in 18900-18999; 8787/8788/8797/8798 are refused. State: a short temp dir, never the owner's.
set -u
TMPD="${TEMP:-/tmp}"; command -v cygpath >/dev/null 2>&1 && TMPD="$(cygpath -u "$TMPD")"
REG="${TESTDAEMON_REG:-$TMPD/testdaemon}"; mkdir -p "$REG"
die() { echo "testdaemon: $*" >&2; exit 1; }
cmd="${1:-}"; shift || true
name=""; mode=""; port=""; dist=""; statearg=""; tmo=90; rest=()
while [ $# -gt 0 ]; do case "$1" in
  --name) name="$2"; shift 2;; --mode) mode="$2"; shift 2;; --port) port="$2"; shift 2;;
  --dist) dist="$2"; shift 2;; --state) statearg="$2"; shift 2;; --timeout) tmo="$2"; shift 2;; --) shift; rest=("$@"); break;;
  *) die "unknown flag '$1' (see the header of $0)";; esac; done
conf="$REG/$name.env"
scrub() { for v in $(env | sed -n 's/^\(INFANTRY_[A-Z_0-9]*\|AGENTHQ_[A-Z_0-9]*\|CLAUDE[A-Z_0-9]*\)=.*/\1/p'); do unset "$v"; done; }
winpath() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || echo "$1"; }
reserved() { grep -qsE "^(PORT|LINK)=$1$" "$REG"/*.env; }  # a sibling test daemon's ports, even before it binds the link one
inuse() { reserved "$1" || (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
case "$cmd" in
up)
  [ -n "$name" ] || die "up needs --name"
  [ ! -f "$conf" ] || die "'$name' already registered ($conf); run: $0 down --name $name"
  case "$mode" in ""|both|runner|orchestrator) ;; *) die "--mode must be both|runner|orchestrator, got '$mode'";; esac
  root="$(cd "$(dirname "$0")/.." && pwd)"; dist="${dist:-$root/superiorSoftLibrary/infantry/build/install/infantry}"
  if [ -d "$dist/lib" ]; then cp_="$(winpath "$dist")/lib/*"; bin="$(winpath "$dist")/bin"
  elif [ -d "$dist/app" ]; then cp_="$(winpath "$dist")/app/*"; bin="$(winpath "$dist")/app/resources"
  else die "no lib/ or app/ under '$dist' (build: ./gradlew :superiorSoftLibrary:infantry:installDist)"; fi
  java=java; [ -x "$dist/runtime/bin/java.exe" ] && java="$dist/runtime/bin/java.exe"
  if [ -z "$port" ]; then for p in $(seq 18900 18999); do inuse "$p" || { port=$p; break; }; done; fi
  [ -n "$port" ] || die "no free port in 18900-18999"
  case "$port" in 8787|8788|8797|8798) die "port $port belongs to the owner";; esac
  inuse "$port" && die "port $port is taken"
  # the runner link listener (pairing + remote starts) defaults to 8790, which would collide with the owner's runner: pin it too
  link=""; for p in $(seq 18900 18999); do [ "$p" != "$port" ] && ! inuse "$p" && { link=$p; break; }; done
  [ -n "$link" ] || die "no free link port in 18900-18999"
  if [ -n "$statearg" ]; then mkdir -p "$statearg"; state="$(winpath "$statearg")"; else state="$(mktemp -d "${TEMP:-/tmp}/td-XXXXXX")"; state="$(winpath "$state")"; fi; log="$state/daemon.log"  # --state: reuse a state dir (plugins/pairing survive a restart); log="$state/daemon.log"
  scrub
  export INFANTRY_STATE_DIR="$state" INFANTRY_HOME="$state" INFANTRY_PORT="$port" INFANTRY_PORT_STRICT=true INFANTRY_BIN="$bin" INFANTRY_LIMIT_RUNNERS_LINK_PORT="$link"
  [ -n "$mode" ] && export INFANTRY_MODE="$mode"
  [ -d "$dist/app/resources/modules" ] && export INFANTRY_BUNDLED_MODULES="$(winpath "$dist")/app/resources/modules"
  # PLUGIN-SCRIPTS-1: an installDist folder bundles its plugins in modules/ (the dev runDaemon task passes the same folder).
  [ -d "$dist/modules" ] && export INFANTRY_BUNDLED_MODULES="$(winpath "$dist")/modules"
  # RUNNER-UPDATE-1: the dist's own build stamp (BUILD-STAMP-1), as its launcher passes it; without it the daemon reports "dev".
  build="$(grep -ho 'Dinfantry\.build=[^"'"'"' ]*' "$dist/bin/infantry" "$dist"/app/*.cfg 2>/dev/null | head -1 | cut -d= -f2)"
  nohup "$java" -Xmx768m --add-opens=java.base/java.util=ALL-UNNAMED -Dinfantry.plugins.devTrust=true ${build:+"-Dinfantry.build=$build"} \
    -cp "$cp_" com.superiorsoftllc.infantry.daemon.DaemonMainKt >"$log" 2>&1 &
  pid=$!; winpid="$(ps -p "$pid" -W 2>/dev/null | awk 'NR==2{print $4}')"; winpid="${winpid:-$pid}"
  printf 'NAME=%s\nPID=%s\nWINPID=%s\nPORT=%s\nLINK=%s\nSTATE=%s\nDIST=%s\nMODE=%s\n' "$name" "$pid" "$winpid" "$port" "$link" "$state" "$dist" "${mode:-both}" >"$conf"
  end=$((SECONDS+tmo)); ok=""
  while [ $SECONDS -lt $end ]; do
    kill -0 "$pid" 2>/dev/null || break
    if curl -fsS -m 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1; then ok=1; break; fi; sleep 1; done
  if [ -z "$ok" ]; then
    echo "testdaemon: FAILED: '$name' is not healthy on port $port after ${tmo}s (process $(kill -0 "$pid" 2>/dev/null && echo alive || echo DEAD)). Last log lines ($log):" >&2
    tail -n 15 "$log" >&2; kill "$pid" 2>/dev/null; rm -f "$conf"; exit 2; fi
  echo "up name=$name mode=${mode:-both} port=$port link_port=$link pid=$winpid state=$state"
  echo "token_file=$state/owner.token (owner token; the CLI reads it from the state dir)"
  echo "cli: $0 cli --name $name -- <infantry args>   down: $0 down --name $name";;
cli)
  [ -f "$conf" ] || die "'$name' is not running (no $conf)"; . "$conf"
  scrub; export INFANTRY_URL="ws://127.0.0.1:$PORT/ws" INFANTRY_STATE_DIR="$STATE"
  if [ -d "$DIST/lib" ]; then exec java --add-opens=java.base/java.util=ALL-UNNAMED -cp "$(winpath "$DIST")/lib/*" com.superiorsoftllc.infantry.cli.CliMainKt "${rest[@]}"
  else exec "$DIST/runtime/bin/java.exe" --add-opens=java.base/java.util=ALL-UNNAMED -cp "$(winpath "$DIST")/app/*" com.superiorsoftllc.infantry.cli.CliMainKt "${rest[@]}"; fi;;
down)
  [ -f "$conf" ] || die "'$name' is not running (no $conf)"; . "$conf"
  # by PID only: the owner's app shares the exe name
  if command -v taskkill >/dev/null 2>&1; then MSYS2_ARG_CONV_EXCL='*' taskkill /PID "$WINPID" /T /F >/dev/null 2>&1; else kill "$PID" 2>/dev/null; fi
  sleep 1
  if curl -fsS -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then die "'$name' (pid $WINPID) still answers on port $PORT"; fi
  rm -f "$conf"; echo "down name=$name pid=$WINPID port=$PORT (state kept: $STATE)";;
list) for f in "$REG"/*.env; do [ -f "$f" ] && ( . "$f"; echo "$NAME mode=$MODE port=$PORT link=$LINK pid=$WINPID state=$STATE" ); done; true;;
*) die "usage: $0 up|cli|down|list (see the header)";;
esac
