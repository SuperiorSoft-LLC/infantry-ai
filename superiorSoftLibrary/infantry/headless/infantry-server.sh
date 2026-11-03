#!/bin/sh
# Infantry AI headless daemon (no UI). Java: the bundled runtime/ first, then JAVA_HOME, then `java` on PATH (21+).
# Configure with INFANTRY_* environment variables (README.txt next to bin/); extra JVM flags go in INFANTRY_JAVA_OPTS.
APP_HOME=$(cd "$(dirname "$0")/.." && pwd -P) || exit 1
if [ -x "$APP_HOME/runtime/bin/java" ]; then
    JAVA="$APP_HOME/runtime/bin/java"
elif [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
# Sessions run `infantry ...`: the daemon puts this bin dir on their PATH.
INFANTRY_BIN="${INFANTRY_BIN:-$APP_HOME/bin}"
export INFANTRY_BIN
# The update routes find the install (VERSION file, versions/ folder) from here.
INFANTRY_APP_HOME="${INFANTRY_APP_HOME:-$APP_HOME}"
export INFANTRY_APP_HOME
# shellcheck disable=SC2086
exec "$JAVA" -Xms32m -Xmx512m -Djava.awt.headless=true $INFANTRY_JAVA_OPTS \
    -cp "$APP_HOME/lib/*" com.superiorsoftllc.infantry.daemon.DaemonMainKt "$@"
