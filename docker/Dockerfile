# Headless Infantry AI daemon (no Compose UI) with the harness CLIs inside. Build the context with Gradle, then the image:
#   ./gradlew :superiorSoftLibrary:infantry:dockerContext
#   docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/infantry/build/docker
# `dockerContext` copies this file and entrypoint.sh next to the installDist output (infantry/) and the browser bundle
# (infantry/web), so the image holds exactly what a droplet runs. installDist is the daemon's own runtime classpath:
# no Compose/skiko jar is in it (those are only in the desktop app), so nothing Windows-only reaches the image.
#
# Harness CLIs baked in (DOCKER-2, ticket c00d1d1c): opencode, pi and claude (RUNNER-IMAGE-CLAUDE-1); --build-arg INSTALL_CLAUDE=false leaves claude out.
# Pin versions with --build-arg OPENCODE_VERSION=... / PI_VERSION=... / CLAUDE_VERSION=... (default: latest at build time).
# They land in /usr/local/bin, which the daemon finds on PATH (executables/ExecutableResolver.kt). The built-in installer
# (`infantry harness-install <id>` -> /data/harness-tools) still works and wins over the baked-in copy when used.

# SANDBOX-1: the Landlock launcher (limits.sandbox.mode = 1 wraps every agent CLI with it). Static: no runtime deps.
FROM gcc:13-bookworm AS landlock
COPY landlock-exec.c /src/landlock-exec.c
RUN gcc -O2 -static -Wall -Werror -o /landlock-exec /src/landlock-exec.c

FROM eclipse-temurin:21-jre AS stage
COPY infantry /opt/infantry
RUN chmod +x /opt/infantry/bin/infantry

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Infantry AI" org.opencontainers.image.description="Infantry AI headless daemon (formerly Infantry)"
# git: ticket starts cut worktrees. curl: health check. ripgrep: opencode/pi search tools use it when present.
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates ripgrep \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 --home-dir /home/infantry infantry \
    && mkdir -p /data /work && chown infantry:infantry /data /work
# Node 22 + npm, from the official image: Ubuntu's nodejs is too old (pi needs node >= 22.19).
COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:22-bookworm-slim /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

ARG OPENCODE_VERSION=latest
ARG PI_VERSION=latest
ARG INSTALL_CLAUDE=true
ARG CLAUDE_VERSION=latest
RUN npm install -g --no-audit --no-fund "opencode-ai@${OPENCODE_VERSION}" "@earendil-works/pi-coding-agent@${PI_VERSION}" \
    && if [ "$INSTALL_CLAUDE" = "true" ]; then npm install -g --no-audit --no-fund "@anthropic-ai/claude-code@${CLAUDE_VERSION}"; fi \
    && npm cache clean --force \
    && opencode --version && pi --version

COPY --from=stage /opt/infantry /opt/infantry
COPY --from=landlock /landlock-exec /usr/local/bin/landlock-exec
COPY entrypoint.sh /usr/local/bin/infantry-entrypoint
# DEMO-MODEL-CC-1: the Command Code provider and the demo model for opencode; the key is only ever the CMD_API_KEY env at run time.
COPY --chown=10001:10001 opencode-commandcode.json /home/infantry/.config/opencode/opencode.json
# LF line ends are kept by .gitattributes (*.sh eol=lf); a CRLF script would fail with "not found".
RUN chmod 755 /usr/local/bin/infantry-entrypoint

ARG INFANTRY_VERSION=dev
ENV INFANTRY_VERSION=$INFANTRY_VERSION \
    INFANTRY_DISTRIBUTION=docker \
    INFANTRY_HOST=0.0.0.0 \
    INFANTRY_PORT=8787 \
    INFANTRY_STATE_DIR=/data \
    INFANTRY_HOME=/data \
    INFANTRY_TOKEN_FILE=/data/access-token \
    INFANTRY_BIN=/opt/infantry/bin \
    INFANTRY_WEB_DIR=/opt/infantry/web \
    INFANTRY_POLICY_DIRS=/opt/infantry/policies \
    JAVA_OPTS="-Xms32m -Xmx512m" \
    INFANTRY_LIMIT_HEALTH_WATCHDOG_FAILURES=3 \
    PATH=/opt/infantry/bin:$PATH
# Ticket 55d60a5e: Docker restarts a container that EXITS, never one that is only unhealthy. The daemon's health watchdog
# (limits.health.*) asks its own /health every 10 s and halts the JVM (exit 3) after 3 failures in a row, so
# `restart: unless-stopped` brings it back. INFANTRY_LIMIT_HEALTH_WATCHDOG_FAILURES=0 turns it off.
# Token: INFANTRY_TOKEN, or the file INFANTRY_TOKEN_FILE; when neither exists the entrypoint generates the file in /data on
# first start (INFANTRY_TOKEN_AUTO=false = refuse to start instead). The daemon refuses a non-loopback bind without one.
# WEB-1: the browser client is at http://<host>:8787/. Set INFANTRY_WEB_HOSTS to the host name(s) the browser uses
# (e.g. agents.example.com): inside a container the daemon cannot see the host's own addresses.

USER infantry
WORKDIR /work
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${INFANTRY_PORT}/health" || exit 1
ENTRYPOINT ["/usr/local/bin/infantry-entrypoint"]
