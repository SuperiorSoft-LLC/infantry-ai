#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# TEST-DAEMON-1: ONE command for a clean test daemon with the official plugins (the Lua project script `test-daemon` calls this).
#   scripts/test-daemon.sh start [--worktree <dir>] [--dist <dir>] [--name <n>]   -> JSON line {port,url,stateDir,pid,name,env}
#   scripts/test-daemon.sh stop  --name <n> | --pid <pid>                       (by PID only, deletes the state dir)
#   scripts/test-daemon.sh status
# start: --worktree builds installDist there (official plugins bundled as modules/); else --dist; else the newest packaged build
# in agenthq-dist/builds. Scrubbed env, temp state dir, free 189xx port (8787/8788/8797/8798 refused), exec enabled on that daemon only.
set -u
here="$(cd "$(dirname "$0")" && pwd)"; td="$here/testdaemon.sh"
act="${1:-}"; shift || true; wt=""; dist=""; name=""; pid=""
while [ $# -gt 0 ]; do case "$1" in --worktree) wt="$2"; shift 2;; --dist) dist="$2"; shift 2;; --name) name="$2"; shift 2;; --pid) pid="$2"; shift 2;; *) echo "test-daemon: unknown flag $1" >&2; exit 1;; esac; done
REG="${TESTDAEMON_REG:-${TEMP:-/tmp}/testdaemon}"; command -v cygpath >/dev/null && REG="$(cygpath -u "$REG")"
case "$act" in
start)
  name="${name:-td$$}"
  if [ -n "$wt" ]; then ( cd "$wt" && ./gradlew :superiorSoftLibrary:infantry:installDist --console=plain -q ) >&2 || { echo "test-daemon: installDist failed in $wt" >&2; exit 2; }
    dist="$wt/superiorSoftLibrary/infantry/build/install/infantry"
  elif [ -z "$dist" ]; then
    b="${AGENTHQ_BUILDS:-/c/Users/antho/agenthq-dist/builds}"
    dist="$(ls -dt "$b"/2026*/ 2>/dev/null | grep -v -e optional -e -calendar | head -1)"; dist="${dist%/}"
    [ -n "$dist" ] || { echo "test-daemon: no packaged build in $b" >&2; exit 2; }
  fi
  out="$("$td" up --name "$name" --dist "$dist" 2>&1)" || { echo "$out" >&2; exit 2; }
  . "$REG/$name.env"
  "$td" cli --name "$name" -- settings set scripting.exec.enabled true >&2 || echo "test-daemon: warning: could not enable exec" >&2
  printf '{"name":"%s","port":%s,"url":"ws://127.0.0.1:%s/ws","stateDir":"%s","pid":%s,"dist":"%s","env":["INFANTRY_URL=ws://127.0.0.1:%s/ws","INFANTRY_STATE_DIR=%s"],"cli":"scripts/testdaemon.sh cli --name %s -- <args>"}\n' "$name" "$PORT" "$PORT" "$STATE" "$WINPID" "$dist" "$PORT" "$STATE" "$name";;
stop)
  if [ -z "$name" ] && [ -n "$pid" ]; then for f in "$REG"/*.env; do [ -f "$f" ] && ( . "$f"; [ "$WINPID" = "$pid" ] || [ "$PID" = "$pid" ] ) && name="$(. "$f"; echo $NAME)"; done; fi
  [ -n "$name" ] || { echo "test-daemon: stop needs --name or a pid we started" >&2; exit 1; }
  . "$REG/$name.env" 2>/dev/null || { echo "test-daemon: '$name' unknown" >&2; exit 1; }
  st="$STATE"; "$td" down --name "$name" || exit 2
  case "$st" in *td-*) rm -rf "$st";; esac; echo "stopped $name, state dir removed";;
status) "$td" list;;
*) echo "usage: $0 start|stop|status" >&2; exit 1;;
esac
