#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# UPDATER-1 release gate: a server archive is published only when it starts.
#
#   1. unpack the archive into a fresh folder;
#   2. scripts/split-packages.sh on its lib/ (no package of ours in two jars);
#   3. start bin/infantry-server on a free port with a throwaway state dir and no INFANTRY_* from the caller;
#   4. require GET /health = 200 within the timeout, and no "[plugins] daemon half of ... refused" in its log;
#   5. stop it.
#
# Usage: scripts/release-smoke.sh <InfantryAI-server-<v>-<os>-<arch>.zip|.tar.gz> [timeout seconds, default 180]
# Runs on the release runners (Linux, macOS, Windows Git Bash). Never binds the owner's ports (8787/8788).
set -euo pipefail
archive="${1:?usage: release-smoke.sh <server archive> [timeout s]}"
timeout="${2:-180}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
pid=""
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        # Git Bash: stop the Windows process tree (cmd -> java), not only the shell's view of it.
        if [ -r "/proc/$pid/winpid" ]; then taskkill //T //F //PID "$(cat "/proc/$pid/winpid")" >/dev/null 2>&1 || true; fi
        kill "$pid" 2>/dev/null || true
        sleep 2
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -rf "$work" 2>/dev/null || true
}
trap cleanup EXIT

echo "smoke: unpacking $(basename "$archive")"
mkdir -p "$work/app"
case "$archive" in
    *.zip) unzip -q "$archive" -d "$work/app" ;;
    *.tar.gz|*.tgz) tar -xzf "$archive" -C "$work/app" ;;
    *) echo "smoke: unknown archive type: $archive" >&2; exit 2 ;;
esac
home="$(find "$work/app" -mindepth 1 -maxdepth 2 -type d -name lib -exec dirname {} \; | head -n1)"
[ -n "$home" ] || { echo "smoke: the archive has no lib/ folder" >&2; exit 1; }

"$here/split-packages.sh" "$home/lib"

# A port nothing answers on, from 20000-59999 (never 8787/8788); INFANTRY_PORT_STRICT=1 makes a race fail loudly, not move.
port=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
    p=$(( 20000 + (RANDOM * 32768 + RANDOM) % 40000 ))
    if ! curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$p/" 2>/dev/null; then port="$p"; break; fi
done
[ -n "$port" ] || { echo "smoke: found no free port" >&2; exit 2; }
state="$work/state"; mkdir -p "$state"
log="$work/daemon.log"

# A clean environment: nothing of the caller's INFANTRY_* / AGENTHQ_* (a token, a URL, a state dir) reaches the daemon.
for v in $(env | sed -n 's/^\(\(INFANTRY\|AGENTHQ\)_[A-Za-z0-9_]*\)=.*/\1/p'); do unset "$v"; done
export INFANTRY_STATE_DIR="$state" INFANTRY_PORT="$port" INFANTRY_PORT_STRICT=1

if [ -f "$home/bin/infantry-server.sh" ] && [ "${OS:-}" != "Windows_NT" ]; then
    sh "$home/bin/infantry-server.sh" > "$log" 2>&1 &
else
    cmd //c "$(cygpath -w "$home/bin/infantry-server.bat")" > "$log" 2>&1 &
fi
pid=$!
echo "smoke: started on 127.0.0.1:$port (state $state)"

code=""
end=$(( $(date +%s) + timeout ))
while [ "$(date +%s)" -lt "$end" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then echo "smoke: the daemon exited before it answered /health" >&2; tail -n 40 "$log" >&2; exit 1; fi
    code="$(curl -s -o "$work/health.json" -w '%{http_code}' "http://127.0.0.1:$port/health" || true)"
    [ "$code" = "200" ] && break
    sleep 2
done
if [ "$code" != "200" ]; then
    echo "smoke: /health did not answer 200 within ${timeout}s (last: ${code:-none})" >&2
    tail -n 40 "$log" >&2
    exit 1
fi
echo "smoke: /health 200: $(head -c 300 "$work/health.json")"

# Give the plugin host a moment to finish loading, then look for refusals in its output and its own log files.
sleep 5
refused="$( { cat "$log"; find "$state" -name '*.log' -type f -exec cat {} + 2>/dev/null; } | grep -F '[plugins] daemon half of' | grep -F 'refused' || true)"
if [ -n "$refused" ]; then
    echo "smoke: plugins were refused at start:" >&2
    echo "$refused" >&2
    exit 1
fi
echo "smoke: passed"
