#!/bin/sh
# Infantry AI headless daemon (no UI). Java: the bundled runtime/ first, then JAVA_HOME, then `java` on PATH (21+).
# Configure with AGENTHQ_* environment variables (README.txt next to bin/); extra JVM flags go in AGENTHQ_JAVA_OPTS.
APP_HOME=$(cd "$(dirname "$0")/.." && pwd -P) || exit 1
if [ -x "$APP_HOME/runtime/bin/java" ]; then
    JAVA="$APP_HOME/runtime/bin/java"
elif [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
# Sessions run `agenthq ...`: the daemon puts this bin dir on their PATH.
AGENTHQ_BIN="${AGENTHQ_BIN:-$APP_HOME/bin}"
export AGENTHQ_BIN
# The update routes find the install (VERSION file, versions/ folder) from here.
AGENTHQ_APP_HOME="${AGENTHQ_APP_HOME:-$APP_HOME}"
export AGENTHQ_APP_HOME
# shellcheck disable=SC2086
exec "$JAVA" -Xms32m -Xmx512m -Djava.awt.headless=true $AGENTHQ_JAVA_OPTS \
    -cp "$APP_HOME/lib/*" com.superiorsoftllc.agenthq.daemon.DaemonMainKt "$@"
