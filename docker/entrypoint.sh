#!/bin/sh
# Infantry AI daemon entrypoint (DOCKER-2, ticket c00d1d1c).
#  - Token: with no AGENTHQ_TOKEN and no token file yet, ONE token is generated into the state volume
#    (AGENTHQ_TOKEN_FILE, default /data/access-token, mode 600) and used from then on. Only the PATH is printed, never
#    the token. AGENTHQ_TOKEN_AUTO=false turns generation off: the daemon then refuses to start without a token.
#  - JVM options come from JAVA_OPTS (default -Xms32m -Xmx512m): the heap is a setting, not baked in.
set -eu

STATE="${AGENTHQ_STATE_DIR:-/data}"
mkdir -p "$STATE"

# The image always sets AGENTHQ_TOKEN_FILE; a non-blank AGENTHQ_TOKEN next to it makes the daemon refuse to start
# ("set AGENTHQ_TOKEN_FILE or AGENTHQ_TOKEN, not both", daemon/AccessPolicy.kt:337). An explicit token wins: keep one.
if [ -z "${AGENTHQ_TOKEN:-}" ]; then
  unset AGENTHQ_TOKEN
else
  unset AGENTHQ_TOKEN_FILE
fi

if [ -z "${AGENTHQ_TOKEN:-}" ] && [ "${AGENTHQ_TOKEN_AUTO:-true}" = "true" ]; then
  TOKEN_FILE="${AGENTHQ_TOKEN_FILE:-$STATE/access-token}"
  if [ ! -s "$TOKEN_FILE" ]; then
    (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TOKEN_FILE")
    echo "[entrypoint] generated a new access token at $TOKEN_FILE"
  else
    echo "[entrypoint] using the access token at $TOKEN_FILE"
  fi
  export AGENTHQ_TOKEN_FILE="$TOKEN_FILE"
elif [ -n "${AGENTHQ_TOKEN_FILE:-}" ] && [ ! -s "$AGENTHQ_TOKEN_FILE" ] && [ -z "${AGENTHQ_TOKEN:-}" ]; then
  # Generation is off and the file is missing: let the daemon refuse, with its own message.
  unset AGENTHQ_TOKEN_FILE
fi

# sqlite-jdbc and JNA extract their native .so into java.io.tmpdir (/tmp) and dlopen it. Docker mounts a compose `tmpfs:`
# /tmp NOEXEC, so the load fails ("failed to map segment from shared object") and the daemon cannot open its database.
# Extract them into the state volume instead (AGENTHQ_NATIVE_DIR), which is executable.
NATIVE_DIR="${AGENTHQ_NATIVE_DIR:-$STATE/native-libs}"
mkdir -p "$NATIVE_DIR"

# shellcheck disable=SC2086  # JAVA_OPTS is a list of words on purpose
exec java ${JAVA_OPTS:--Xms32m -Xmx512m} -Djava.awt.headless=true \
  -Dorg.sqlite.tmpdir="$NATIVE_DIR" -Djna.tmpdir="$NATIVE_DIR" -cp "/opt/agenthq/lib/*" \
  com.superiorsoftllc.agenthq.daemon.DaemonMainKt "$@"
