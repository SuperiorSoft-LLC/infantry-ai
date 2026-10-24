# Headless Infantry AI daemon (no Compose UI). Build the context with Gradle, then build the image from it:
#   ./gradlew :superiorSoftLibrary:agentHq:dockerContext
#   docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/agentHq/build/docker
# `dockerContext` copies this file next to the installDist output (agenthq/), so the image holds exactly what a droplet runs.
# Harness CLIs (claude, opencode, ...) are not baked in: Node/npm is, so the built-in installer puts them in the volume
# (`agenthq harness-install claude` -> /data/harness-tools). See README "Docker".

FROM eclipse-temurin:21-jre AS stage
COPY agenthq /opt/agenthq
RUN chmod +x /opt/agenthq/bin/agenthq

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Infantry AI" org.opencontainers.image.description="Infantry AI headless daemon (formerly AgentHQ)"
# git: ticket starts cut worktrees. curl: health check.
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 --home-dir /home/agenthq agenthq \
    && mkdir -p /data /work && chown agenthq:agenthq /data /work
# Node 22 + npm for the harness installer (HARNESS-INSTALL-1), from the official image: Ubuntu's nodejs is too old (< 20).
COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:22-bookworm-slim /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
COPY --from=stage /opt/agenthq /opt/agenthq

ARG AGENTHQ_VERSION=dev
ENV AGENTHQ_VERSION=$AGENTHQ_VERSION \
    AGENTHQ_DISTRIBUTION=docker \
    AGENTHQ_HOST=0.0.0.0 \
    AGENTHQ_PORT=8787 \
    AGENTHQ_STATE_DIR=/data \
    AGENTHQ_HOME=/data \
    AGENTHQ_BIN=/opt/agenthq/bin \
    AGENTHQ_WEB_DIR=/opt/agenthq/web \
    PATH=/opt/agenthq/bin:$PATH
# AGENTHQ_TOKEN (or AGENTHQ_TOKEN_FILE) is required: the daemon refuses a non-loopback bind without one.
# WEB-1: the browser client is at http://<host>:8787/. Set AGENTHQ_WEB_HOSTS to the host name(s) the browser uses
# (e.g. agents.example.com): inside a container the daemon cannot see the host's own addresses.
# Mount the keystore master key and set AGENTHQ_KEYSTORE_KEY_FILE (CONTRACT.md 1.102.0); never bake it in.

USER agenthq
WORKDIR /work
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${AGENTHQ_PORT}/health" || exit 1
ENTRYPOINT ["java", "-cp", "/opt/agenthq/lib/*", "-Djava.awt.headless=true", "-Xms32m", "-Xmx512m", "com.superiorsoftllc.agenthq.daemon.DaemonMainKt"]
