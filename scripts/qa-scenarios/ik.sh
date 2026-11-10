#!/usr/bin/env bash
# ik.sh <args>: this worktree's compiled infantry CLI pinned to an isolated daemon, env scrubbed.
# IK_PORT (default 18841, the QA window's daemon) and IK_STATE (default C:/t83b/qa-state) pick the daemon; IK_LINK_PORT the runner link port.
# Needs `:superiorSoftLibrary:infantry:installDist` (the launcher in build/install/infantry/bin).
W="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/superiorSoftLibrary"
for v in $(env | cut -d= -f1 | grep -E '^(INFANTRY_|AGENTHQ_|CLAUDE)'); do unset "$v"; done
S="$(cygpath -w "${IK_STATE:-C:/t83b/qa-state}")"
export INFANTRY_URL="ws://127.0.0.1:${IK_PORT:-18841}/ws" INFANTRY_PORT="${IK_PORT:-18841}" INFANTRY_STATE_DIR="$S" INFANTRY_HOME="$S"
[ -n "$IK_LINK_PORT" ] && export INFANTRY_LIMIT_RUNNERS_LINK_PORT="$IK_LINK_PORT"
exec bash "$W/infantry/build/install/infantry/bin/infantry" "$@"
