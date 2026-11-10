#!/usr/bin/env bash
# launch.sh [logname]: Gradle runRealDaemon window from THIS worktree, isolated state dir + ports, env scrubbed.
# Needs the in-window driver (C:/t83/wd, ticket 691a3a9e). Kill the window by PID only (never by image name).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
QA="${QA_DIR:-C:/t83}"
for v in $(env | cut -d= -f1 | grep -E '^(INFANTRY_|AGENTHQ_|CLAUDE)'); do unset "$v"; done
rm -rf "$QA/qa-state"; mkdir -p "$QA/qa-state"
export INFANTRY_STATE_DIR="$(cygpath -w "$QA/qa-state")" INFANTRY_HOME="$(cygpath -w "$QA/qa-state")"
export INFANTRY_PORT=18841 INFANTRY_PORT_STRICT=true INFANTRY_LIMIT_RUNNERS_LINK_PORT=18842
[ -d "$QA/plugins" ] && export INFANTRY_PLUGINS_ROOT="$QA/plugins"
cd "$ROOT"
exec ./gradlew :superiorSoftLibrary:infantryUi:runRealDaemon -I "$QA/isolate.init.gradle" --console=plain > "$QA/${1:-qa}.log" 2>&1
