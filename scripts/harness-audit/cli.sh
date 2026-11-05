#!/usr/bin/env bash
# HARNESS-AUDIT-1 probe: the CLI pinned to the test daemon (INFANTRY_URL beats INFANTRY_PORT).
DIST="$(cygpath -m "$1")"; shift
for v in $(env | sed -n 's/^\(INFANTRY_[A-Z_]*\|AGENTHQ_[A-Z_]*\)=.*/\1/p'); do unset "$v"; done
export INFANTRY_URL="ws://127.0.0.1:${PORT:-8812}/ws" INFANTRY_STATE_DIR="$STATE"
exec "$DIST/runtime/bin/java.exe" --add-opens=java.base/java.util=ALL-UNNAMED -cp "$DIST/app/*" com.superiorsoftllc.infantry.cli.CliMainKt "$@"
