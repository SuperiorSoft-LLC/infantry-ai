#!/bin/sh
# Infantry AI daemon entrypoint (DOCKER-2, ticket c00d1d1c).
#  - Token: with no INFANTRY_TOKEN and no token file yet, ONE token is generated into the state volume
#    (INFANTRY_TOKEN_FILE, default /data/access-token, mode 600) and used from then on. Only the PATH is printed, never
#    the token. INFANTRY_TOKEN_AUTO=false turns generation off: the daemon then refuses to start without a token.
#  - JVM options come from JAVA_OPTS (default -Xms32m -Xmx512m): the heap is a setting, not baked in.
set -eu
# RENAME-2 legacy fallback: an AGENTHQ_<X> variable stands in when INFANTRY_<X> is unset.
for _v in $(env | sed -n "s/^AGENTHQ_\([A-Za-z0-9_]*\)=.*/\1/p"); do
  eval "[ -n "\${INFANTRY_$_v:-}" ] || export INFANTRY_$_v="\$AGENTHQ_$_v""
done
unset _v

STATE="${INFANTRY_STATE_DIR:-/data}"
mkdir -p "$STATE"

# The image always sets INFANTRY_TOKEN_FILE; a non-blank INFANTRY_TOKEN next to it makes the daemon refuse to start
# ("set INFANTRY_TOKEN_FILE or INFANTRY_TOKEN, not both", daemon/AccessPolicy.kt:337). An explicit token wins: keep one.
if [ -z "${INFANTRY_TOKEN:-}" ]; then
  unset INFANTRY_TOKEN
else
  unset INFANTRY_TOKEN_FILE
fi

if [ -z "${INFANTRY_TOKEN:-}" ] && [ "${INFANTRY_TOKEN_AUTO:-true}" = "true" ]; then
  TOKEN_FILE="${INFANTRY_TOKEN_FILE:-$STATE/access-token}"
  if [ ! -s "$TOKEN_FILE" ]; then
    (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TOKEN_FILE")
    echo "[entrypoint] generated a new access token at $TOKEN_FILE"
  else
    echo "[entrypoint] using the access token at $TOKEN_FILE"
  fi
  export INFANTRY_TOKEN_FILE="$TOKEN_FILE"
elif [ -n "${INFANTRY_TOKEN_FILE:-}" ] && [ ! -s "$INFANTRY_TOKEN_FILE" ] && [ -z "${INFANTRY_TOKEN:-}" ]; then
  # Generation is off and the file is missing: let the daemon refuse, with its own message.
  unset INFANTRY_TOKEN_FILE
fi

# DEMO-LOCK (ticket 2f154f5f): the demo project's directory is a git repository the two seeded agents work in. Made once,
# on the first locked start, in the /work volume; the Demo_Lock daemon plugin then seeds the project and its two sessions (plugins/demo/Demo_Lock/daemon).
case "${INFANTRY_DEMO_LOCK:-0}" in
  1|true|on|yes)
    # Ticket 9bf81983: the PUBLIC visitor token the page fills in (default-deny under the lock, never the owner token).
    # With none given, ONE is generated into the state volume, next to (and different from) the owner token.
    if [ -z "${INFANTRY_DEMO_PUBLIC_TOKEN:-}" ] && [ -z "${INFANTRY_DEMO_PUBLIC_TOKEN_FILE:-}" ]; then
      PUBLIC_FILE="$STATE/demo-public-token"
      if [ ! -s "$PUBLIC_FILE" ]; then
        (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$PUBLIC_FILE")
        echo "[entrypoint] generated the demo visitor token at $PUBLIC_FILE"
      fi
      export INFANTRY_DEMO_PUBLIC_TOKEN_FILE="$PUBLIC_FILE"
    fi
    DEMO_DIR="${INFANTRY_DEMO_DIR:-/work/demo}"
    if [ ! -d "$DEMO_DIR/.git" ]; then
      mkdir -p "$DEMO_DIR"
      printf '# Demo project\n\nA scratch repository for the Infantry AI demo agents.\n' > "$DEMO_DIR/README.md"
      git -C "$DEMO_DIR" init -q -b main
      git -C "$DEMO_DIR" add README.md
      git -C "$DEMO_DIR" -c user.name=demo -c user.email=demo@localhost commit -q -m "demo project"
      echo "[entrypoint] demo lock: created the demo repository at $DEMO_DIR"
    fi
    ;;
esac

# sqlite-jdbc and JNA extract their native .so into java.io.tmpdir (/tmp) and dlopen it. Docker mounts a compose `tmpfs:`
# /tmp NOEXEC, so the load fails ("failed to map segment from shared object") and the daemon cannot open its database.
# Extract them into the state volume instead (INFANTRY_NATIVE_DIR), which is executable.
NATIVE_DIR="${INFANTRY_NATIVE_DIR:-$STATE/native-libs}"
mkdir -p "$NATIVE_DIR"

# shellcheck disable=SC2086  # JAVA_OPTS is a list of words on purpose
exec java ${JAVA_OPTS:--Xms32m -Xmx512m} -Djava.awt.headless=true \
  -Dorg.sqlite.tmpdir="$NATIVE_DIR" -Djna.tmpdir="$NATIVE_DIR" --add-opens=java.base/java.util=ALL-UNNAMED -cp "/opt/infantry/lib/*" \
  com.superiorsoftllc.infantry.daemon.DaemonMainKt "$@"
