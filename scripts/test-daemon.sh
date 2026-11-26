#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# TESTDAEMON-RUST-1: ONE command for a clean RUST test daemon (the Lua project script `test-daemon` calls this).
#   scripts/test-daemon.sh start [--worktree <dir>] [--bin <infantry-daemon.exe>] [--plugins <dir>] [--name <n>]
#       -> JSON line {name,port,url,health,stateDir,pid,log,bin,plugins,env}
#   scripts/test-daemon.sh stop  --name <n> | --pid <pid>     (kills by PID only, deletes the temp state dir)
#   scripts/test-daemon.sh status
# Daemon binary: --bin; else --worktree builds `cargo build -p infantry-distribution` there; else the newest packaged
# Rust build (*-rust-*) in agenthq-dist/builds. Detached, loopback, free 189xx port (never 18990/8787/8788/8797/8798),
# fresh temp state dir, env with every INFANTRY_*/AGENTHQ_*/BATTALION_*/CLAUDE* removed, INFANTRY_PLUGINS_DIR = --plugins
# or an empty dir, output to <state>/daemon.log.
set -u
act="${1:-}"; shift || true; wt=""; bin=""; plugins=""; name=""; pid=""
while [ $# -gt 0 ]; do case "$1" in --worktree) wt="$2"; shift 2;; --bin|--dist) bin="$2"; shift 2;; --plugins) plugins="$2"; shift 2;;
  --name) name="$2"; shift 2;; --pid) pid="$2"; shift 2;; *) echo "test-daemon: unknown flag $1" >&2; exit 1;; esac; done
REG="${TESTDAEMON_REG:-${TEMP:-/tmp}/testdaemon-rust}"; command -v cygpath >/dev/null && REG="$(cygpath -u "$REG")"; mkdir -p "$REG"
win() { command -v cygpath >/dev/null && cygpath -m "$1" || echo "$1"; }
alive() { if command -v tasklist >/dev/null; then MSYS2_ARG_CONV_EXCL="*" tasklist /FI "PID eq $1" /NH 2>/dev/null | grep -qw "$1"; else kill -0 "$1" 2>/dev/null; fi; }
case "$act" in
start)
  name="${name:-td$$}"; [ -f "$REG/$name.env" ] && { echo "test-daemon: '$name' already registered; stop it first" >&2; exit 1; }
  if [ -n "$wt" ]; then
    ( cd "$wt/rust" && cargo build -q -p infantry-distribution -p infantry-cli ) >&2 || { echo "test-daemon: cargo build failed in $wt" >&2; exit 2; }
    tgt="${CARGO_TARGET_DIR:-${INFANTRY_CARGO_TARGET_DIR:-$wt/rust/target}}"; bin="$tgt/debug/infantry-daemon.exe"; [ -f "$bin" ] || bin="$tgt/debug/infantry-daemon"
  elif [ -z "$bin" ]; then
    b="${AGENTHQ_BUILDS:-/c/Users/antho/agenthq-dist/builds}"
    d="$(ls -dt "$b"/*-rust-*/ 2>/dev/null | head -1)"; bin="${d%/}/infantry-daemon.exe"
  elif [ -d "$bin" ]; then bin="${bin%/}/infantry-daemon.exe"; fi
  [ -f "$bin" ] || { echo "test-daemon: no daemon binary ($bin); pass --bin or --worktree" >&2; exit 2; }
  port=""; for p in $(seq 18900 18999); do
    case $p in 18990) continue;; esac; grep -qs "^PORT=$p$" "$REG"/*.env && continue
    (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null && continue; port=$p; break; done
  [ -n "$port" ] || { echo "test-daemon: no free port in 18900-18999" >&2; exit 2; }
  tmp="${TEMP:-/tmp}"; command -v cygpath >/dev/null && tmp="$(cygpath -u "$tmp")"; state="$(mktemp -d "$tmp/td-XXXXXX")"; [ -n "$plugins" ] || { plugins="$state/plugins-empty"; mkdir -p "$plugins"; }
  log="$state/daemon.log"
  # TESTENV-1: a worker shell inside BattalionOS carries BATTALION_* (the ready/token/state/app paths of the owner's app);
  # a daemon that keeps them runs in OS-app mode and takes the owner's run files. Removed case-blind (Windows env is), and
  # the start is refused unless the child env is clean and the state dir (where every run/data file goes) is our temp dir.
  scrub=(); for v in $(env | sed -n 's/^\(\(INFANTRY\|AGENTHQ\|BATTALION\|CLAUDE\)[A-Za-z0-9_]*\)=.*/\1/Ip'); do scrub+=(-u "$v"); done
  left="$(env "${scrub[@]}" env | grep -i '^battalion' | cut -d= -f1 | tr '\n' ' ')"
  [ -z "$left" ] || { echo "test-daemon: refused: OS-app variables survive the scrub: $left" >&2; rm -rf "$state"; exit 2; }
  case "$state" in "$tmp"/td-*) ;; *) echo "test-daemon: refused: state dir $state is not inside $tmp" >&2; exit 2;; esac
  env "${scrub[@]}" INFANTRY_STATE_DIR="$(win "$state")" INFANTRY_PLUGINS_DIR="$(win "$plugins")" \
    nohup "$bin" --host 127.0.0.1 --port "$port" --state-dir "$(win "$state")" >"$log" 2>&1 </dev/null &
  bpid=$!; wpid="$(cat /proc/$bpid/winpid 2>/dev/null || echo $bpid)"
  printf 'NAME=%s\nPORT=%s\nSTATE=%s\nPID=%s\nWINPID=%s\nLOG=%s\n' "$name" "$port" "$state" "$bpid" "$wpid" "$log" >"$REG/$name.env"
  ok=""; for _ in $(seq 1 "${TESTDAEMON_TIMEOUT:-60}"); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/health")" = 200 ] && { ok=1; break; }
    kill -0 "$bpid" 2>/dev/null || break; sleep 1; done
  if [ -z "$ok" ]; then echo "test-daemon: daemon not healthy on $port; last log lines:" >&2; tail -n 15 "$log" >&2
    kill "$bpid" 2>/dev/null; taskkill //PID "$wpid" //F >/dev/null 2>&1; rm -f "$REG/$name.env"; exit 2; fi
  # On Windows $! is the MSYS stub; the daemon's own pid is the one listening on our port.
  lp="$(netstat -ano 2>/dev/null | awk -v a="127.0.0.1:$port" '$2==a && $4=="LISTENING"{print $5; exit}')"
  [ -n "$lp" ] && { wpid="$lp"; sed -i "s/^WINPID=.*/WINPID=$wpid/" "$REG/$name.env"; }
  # TESTENV-3: the agents' folders (workspace.root) go inside the temp state dir, never the real ~/Infantry AI (the daemon
  # also derives its home from an explicit --state-dir). Not settable = not isolated: stop it and refuse.
  ws="$(win "$state")/home"; cli="$(dirname "$bin")/infantry.exe"; [ -f "$cli" ] || cli="$(dirname "$bin")/infantry"
  if ! env "${scrub[@]}" INFANTRY_URL="ws://127.0.0.1:$port/ws" INFANTRY_STATE_DIR="$(win "$state")" "$cli" settings set workspace.root "$ws" >&2; then
    echo "test-daemon: refused: could not set workspace.root=$ws on port $port; stopping it" >&2
    kill "$bpid" 2>/dev/null; taskkill //PID "$wpid" //F >/dev/null 2>&1; rm -f "$REG/$name.env"; rm -rf "$state"; exit 2; fi
  printf '{"name":"%s","port":%s,"url":"ws://127.0.0.1:%s/ws","health":"http://127.0.0.1:%s/health","stateDir":"%s","pid":%s,"log":"%s","bin":"%s","plugins":"%s","env":["INFANTRY_URL=ws://127.0.0.1:%s/ws","INFANTRY_STATE_DIR=%s"],"stop":"scripts/test-daemon.sh stop --pid %s"}\n' \
    "$name" "$port" "$port" "$port" "$(win "$state")" "$wpid" "$(win "$log")" "$(win "$bin")" "$(win "$plugins")" "$port" "$(win "$state")" "$wpid";;
stop)
  if [ -z "$name" ] && [ -n "$pid" ]; then for f in "$REG"/*.env; do [ -f "$f" ] && ( . "$f"; [ "$WINPID" = "$pid" ] || [ "$PID" = "$pid" ] ) && name="$(. "$f"; echo "$NAME")"; done; fi
  [ -n "$name" ] || { echo "test-daemon: stop needs --name or a pid this script started" >&2; exit 1; }
  . "$REG/$name.env" 2>/dev/null || { echo "test-daemon: '$name' unknown" >&2; exit 1; }
  kill "$PID" 2>/dev/null; command -v taskkill >/dev/null && taskkill //PID "$WINPID" //F >/dev/null 2>&1
  for _ in 1 2 3 4 5; do alive "$WINPID" || break; sleep 1; done
  alive "$WINPID" && { echo "test-daemon: pid $WINPID still alive" >&2; exit 2; }
  rm -f "$REG/$name.env"; case "$STATE" in *td-*) rm -rf "$STATE";; esac; echo "stopped $name pid=$WINPID port=$PORT, state dir removed";;
status) for f in "$REG"/*.env; do [ -f "$f" ] && ( . "$f"; echo "$NAME port=$PORT pid=$WINPID state=$STATE $(alive "$WINPID" && echo up || echo dead)" ); done; true;;
*) echo "usage: $0 start|stop|status" >&2; exit 1;;
esac
