#!/usr/bin/env bash
# launch-auth.sh: like launch.sh, plus a throwaway CLAUDE_CONFIG_DIR (never the owner's login) and THIS worktree's plugins.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
QA="${QA_DIR:-C:/t83}"
for v in $(env | cut -d= -f1 | grep -E '^(INFANTRY_|AGENTHQ_|CLAUDE|ANTHROPIC)'); do unset "$v"; done
rm -rf "$QA/qa-state" "$QA/claude-cfg"; mkdir -p "$QA/qa-state" "$QA/claude-cfg"
export INFANTRY_STATE_DIR="$(cygpath -w "$QA/qa-state")" INFANTRY_HOME="$(cygpath -w "$QA/qa-state")"
export CLAUDE_CONFIG_DIR="$(cygpath -w "$QA/claude-cfg")"
export INFANTRY_PORT=18841 INFANTRY_PORT_STRICT=true INFANTRY_LIMIT_RUNNERS_LINK_PORT=18842
export INFANTRY_PLUGINS_ROOT="$(cygpath -w "$ROOT/plugins")"
cd "$ROOT"
exec ./gradlew :superiorSoftLibrary:infantryUi:runRealDaemon -I "$QA/isolate.init.gradle" --console=plain > "$QA/auth.log" 2>&1
