#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# Entrypoint of the Rust runner image (docker/Dockerfile.rust): the SAME infantry-daemon binary as the desktop app, in runner mode.
# The daemon prints the INFANTRY RUNNER banner itself (endpoint + bearer, RR-2); this script only prepares /data and warns about
# the two address mistakes that make a pasted endpoint unreachable. Secrets are never echoed here.
set -eu
STATE="${INFANTRY_STATE_DIR:-/data}"
mkdir -p "$STATE"
# Every login and tool dir lives in the /data volume, so a recreated container keeps them (CLAUDE_CONFIG_DIR, PI_CODING_AGENT_DIR,
# XDG_DATA_HOME, <state>/harness-tools). Created owner-only: they hold sign-ins.
for d in "${CLAUDE_CONFIG_DIR:-}" "${PI_CODING_AGENT_DIR:-}" "${XDG_DATA_HOME:-}" "$STATE/harness-tools"; do
  [ -z "$d" ] || (umask 077 && mkdir -p "$d") || echo "[entrypoint] WARNING: cannot create $d"
done

if [ "${INFANTRY_MODE:-runner}" = "runner" ]; then
  if [ -z "${INFANTRY_RUNNER_HOST:-}" ]; then
    export INFANTRY_RUNNER_HOST=localhost
    echo "[entrypoint] INFANTRY_RUNNER_HOST is not set: the endpoint says localhost (a Docker on the same PC as the app). On another machine, run with -e INFANTRY_RUNNER_HOST=<its address>"
  fi
  case "$INFANTRY_RUNNER_HOST" in
    host.docker.internal)
      echo "[entrypoint] WARNING: INFANTRY_RUNNER_HOST=host.docker.internal resolves inside a container only; the PC that pastes the endpoint cannot reach it. Use localhost (same PC) or the server's public/tailnet address." ;;
  esac
  if [ -z "${INFANTRY_RUNNER_PUBLIC_PORT:-}" ]; then
    echo "[entrypoint] NOTE: the endpoint advertises port ${INFANTRY_RUNNER_LINK_PORT:-8790} (the container's). If you published another port (-p 18921:${INFANTRY_RUNNER_LINK_PORT:-8790}), set -e INFANTRY_RUNNER_PUBLIC_PORT=18921 or the app will not connect."
  fi
fi
exec infantry-daemon "$@"
