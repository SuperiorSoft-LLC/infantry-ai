#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
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

# RUNNER-LOGIN-1: claude's login lives in the state volume (CLAUDE_CONFIG_DIR, /data/claude in the image), so a recreated container
# keeps it. A login made before this (in ~/.claude and ~/.claude.json, e.g. on a compose home volume) is copied over once.
if [ -n "${CLAUDE_CONFIG_DIR:-}" ]; then
  (umask 077 && mkdir -p "$CLAUDE_CONFIG_DIR") || echo "[entrypoint] WARNING: cannot create CLAUDE_CONFIG_DIR $CLAUDE_CONFIG_DIR"
  if [ ! -s "$CLAUDE_CONFIG_DIR/.credentials.json" ] && [ -s "$HOME/.claude/.credentials.json" ] && [ "$HOME/.claude" != "$CLAUDE_CONFIG_DIR" ]; then
    cp -a "$HOME/.claude/." "$CLAUDE_CONFIG_DIR/" \
      && { [ ! -s "$HOME/.claude.json" ] || [ -s "$CLAUDE_CONFIG_DIR/.claude.json" ] || cp -a "$HOME/.claude.json" "$CLAUDE_CONFIG_DIR/.claude.json"; } \
      && chmod 700 "$CLAUDE_CONFIG_DIR" && chmod 600 "$CLAUDE_CONFIG_DIR/.credentials.json" \
      && echo "[entrypoint] moved the claude login from $HOME/.claude to $CLAUDE_CONFIG_DIR (kept across container recreates)"
  fi
fi

# HARNESS-ALL-1: pi's (PI_CODING_AGENT_DIR) and opencode's (XDG_DATA_HOME/opencode) logins live in the state volume too. A login
# made in the old home places is copied over once, only when the new place has none.
move_login() { # <old dir> <new dir> <file that marks a login> <name>
  [ -n "$2" ] || return 0
  (umask 077 && mkdir -p "$2") || { echo "[entrypoint] WARNING: cannot create $2 for $4"; return 0; }
  if [ ! -s "$2/$3" ] && [ -s "$1/$3" ] && [ "$1" != "$2" ]; then
    cp -a "$1/." "$2/" && chmod 600 "$2/$3" && echo "[entrypoint] moved the $4 login from $1 to $2 (kept across container recreates)"
  fi
}
move_login "$HOME/.pi/agent" "${PI_CODING_AGENT_DIR:-}" auth.json pi
[ -z "${XDG_DATA_HOME:-}" ] || move_login "$HOME/.local/share/opencode" "$XDG_DATA_HOME/opencode" auth.json opencode

# sqlite-jdbc and JNA extract their native .so into java.io.tmpdir (/tmp) and dlopen it. Docker mounts a compose `tmpfs:`
# /tmp NOEXEC, so the load fails ("failed to map segment from shared object") and the daemon cannot open its database.
# Extract them into the state volume instead (INFANTRY_NATIVE_DIR), which is executable.
NATIVE_DIR="${INFANTRY_NATIVE_DIR:-$STATE/native-libs}"
mkdir -p "$NATIVE_DIR"

# RUNNER-UP-1: with INFANTRY_RUNNER_NAME set and this runner not paired yet, print the pairing link into the container log
# once the daemon answers. One code only: when it expires unpaired the log says how to get a new one (no loop: each issue is a
# fresh 10-minute window for guessing). The link is a 10-minute secret; it is useless once redeemed or expired.
runner_pair_when_ready() {
  _name="$1"
  _tries=0
  until infantry settings get runner.projects >/dev/null 2>&1; do
    _tries=$((_tries + 1))
    [ "$_tries" -le "${INFANTRY_RUNNER_READY_TRIES:-120}" ] || { echo "[entrypoint] runner pairing skipped: the daemon never answered"; return 1; }
    sleep 1
  done
  if infantry runner list 2>/dev/null | grep -q "^paired as runner"; then
    echo "[entrypoint] this runner was paired before (kept in the state volume); its bearer pairs it with any app (\`infantry runner bearer\`), or \`infantry runner detach\` for a new code"
    return 0
  fi
  if [ -z "${INFANTRY_RUNNER_HOST:-}" ]; then
    echo "[entrypoint] WARNING: INFANTRY_RUNNER_HOST is not set: the link's hosts are container addresses. Set it to this server's public or tailnet address."
  fi
  case "${INFANTRY_RUNNER_HOST:-}" in
    host.docker.internal)
      echo "[entrypoint] WARNING: INFANTRY_RUNNER_HOST=host.docker.internal resolves inside a container only; the PC that pastes this link cannot reach it. Use localhost (same PC) or the server's public/tailnet address." ;;
  esac
  if [ -n "${INFANTRY_RUNNER_HOST:-}" ] && [ -z "${INFANTRY_RUNNER_PUBLIC_PORT:-}" ]; then
    echo "[entrypoint] NOTE: the link advertises port 8790 (the container's). If you published another port (-p 18921:8790), set INFANTRY_RUNNER_PUBLIC_PORT=18921 or the link will not connect."
  fi
  set -- runner pair --name "$_name"
  [ -z "${INFANTRY_RUNNER_PUBLIC_PORT:-}" ] || set -- "$@" --port "$INFANTRY_RUNNER_PUBLIC_PORT"
  [ -z "${INFANTRY_RUNNER_HOST:-}" ] || set -- "$@" --host "$INFANTRY_RUNNER_HOST"
  [ -z "${INFANTRY_RUNNER_ALLOW_PROJECTS:-}" ] || set -- "$@" --allow-projects "$INFANTRY_RUNNER_ALLOW_PROJECTS"
  [ -z "${INFANTRY_RUNNER_ALLOW_HARNESSES:-}" ] || set -- "$@" --allow-harnesses "$INFANTRY_RUNNER_ALLOW_HARNESSES"
  echo "==== PAIRING LINK ===="
  if infantry "$@"; then
    echo "==== END PAIRING LINK ===="
  else
    echo "==== END PAIRING LINK ===="
    echo "[entrypoint] the pairing code expired unpaired. For a new one: docker exec -it infantry-runner infantry runner pair --name $_name"
  fi
}
# RUNNER-PAIR-1 (the owner's 2 steps): in runner mode, print this runner's ENDPOINT and long-lived BEARER on every start, once the
# daemon answers. Step 2 is pasting both into the app (Settings > Runners > Add runner). The bearer is made once in the state
# volume and stays valid until `docker exec infantry-runner infantry runner bearer --rotate`. Empty grants become * (every project
# and harness); INFANTRY_RUNNER_ALLOW_PROJECTS / _HARNESSES narrow them. INFANTRY_RUNNER_PAIRING=code keeps the older one-time link.
runner_bearer_when_ready() {
  _tries=0
  until infantry settings get runner.projects >/dev/null 2>&1; do
    _tries=$((_tries + 1))
    [ "$_tries" -le "${INFANTRY_RUNNER_READY_TRIES:-120}" ] || { echo "[entrypoint] runner bearer skipped: the daemon never answered"; return 1; }
    sleep 1
  done
  set -- runner bearer
  [ -z "${INFANTRY_RUNNER_NAME:-}" ] || set -- "$@" --name "$INFANTRY_RUNNER_NAME"
  [ -z "${INFANTRY_RUNNER_HOST:-}" ] || set -- "$@" --host "$INFANTRY_RUNNER_HOST"
  [ -z "${INFANTRY_RUNNER_PUBLIC_PORT:-}" ] || set -- "$@" --port "$INFANTRY_RUNNER_PUBLIC_PORT"
  [ -z "${INFANTRY_RUNNER_ALLOW_PROJECTS:-}" ] || set -- "$@" --allow-projects "$INFANTRY_RUNNER_ALLOW_PROJECTS"
  [ -z "${INFANTRY_RUNNER_ALLOW_HARNESSES:-}" ] || set -- "$@" --allow-harnesses "$INFANTRY_RUNNER_ALLOW_HARNESSES"
  echo "==== INFANTRY RUNNER: paste these two lines into the app (Settings > Runners > Add runner) ===="
  infantry "$@" || echo "[entrypoint] could not print the runner bearer; run: docker exec infantry-runner infantry runner bearer"
  echo "==== END INFANTRY RUNNER ===="
}
# RUNNER-ENDPOINT-1: the endpoint the PC dials comes from the docker run env, never from the container's own (bridge) address,
# which the PC cannot reach. With no INFANTRY_RUNNER_HOST the runner is taken to be a local Docker reached as localhost:<published
# port>. The daemon reads it from its env, so the start banner, `docker exec ... infantry runner bearer` and the banner printed
# after a rotation or a pairing all show the same endpoint.
if [ "${INFANTRY_MODE:-}" = "runner" ] && [ -z "${INFANTRY_RUNNER_HOST:-}" ]; then
  export INFANTRY_RUNNER_HOST=localhost
  echo "[entrypoint] INFANTRY_RUNNER_HOST is not set: the endpoint says localhost (a Docker on the same PC as the app). On another machine, run with -e INFANTRY_RUNNER_HOST=<its address>"
fi
if [ "${INFANTRY_MODE:-}" = "runner" ] && [ "${INFANTRY_RUNNER_PAIRING:-bearer}" != "code" ]; then
  ( runner_bearer_when_ready ) &
elif [ -n "${INFANTRY_RUNNER_NAME:-}" ]; then
  ( runner_pair_when_ready "$INFANTRY_RUNNER_NAME" ) &
fi

# shellcheck disable=SC2086  # JAVA_OPTS is a list of words on purpose
exec java ${JAVA_OPTS:--Xms32m -Xmx512m} -Djava.awt.headless=true \
  -Dorg.sqlite.tmpdir="$NATIVE_DIR" -Djna.tmpdir="$NATIVE_DIR" --add-opens=java.base/java.util=ALL-UNNAMED -cp "/opt/infantry/lib/*" \
  com.superiorsoftllc.infantry.daemon.DaemonMainKt "$@"
