#!/usr/bin/env bash
# HARNESS-AUDIT-1 probe: a scrubbed test daemon on port 8812 with a temp state dir, never the owner's.
# usage: test-daemon.sh <dist build dir> <state dir>   (then: cli.sh <state dir> <args...>)
set -eu
DIST="$(cygpath -m "$1")"; STATE="$(cygpath -m "$2")"; PORT="${PORT:-8812}"
mkdir -p "$STATE"
# drop every INFANTRY_*/AGENTHQ_*/CLAUDE* variable (they point at the owner's daemon / break claude transcripts)
for v in $(env | sed -n 's/^\(INFANTRY_[A-Z_]*\|AGENTHQ_[A-Z_]*\|CLAUDE[A-Z_]*\)=.*/\1/p'); do unset "$v"; done
export INFANTRY_STATE_DIR="$STATE" INFANTRY_HOME="$STATE" INFANTRY_PORT="$PORT" INFANTRY_PORT_STRICT=true
export INFANTRY_BUNDLED_MODULES="$DIST/app/resources/modules"
export INFANTRY_BIN="$DIST/app/resources"
exec "$DIST/runtime/bin/java.exe" -Xmx768m --add-opens=java.base/java.util=ALL-UNNAMED \
  -Dinfantry.plugins.devTrust=true -cp "$DIST/app/*" com.superiorsoftllc.infantry.daemon.DaemonMainKt
