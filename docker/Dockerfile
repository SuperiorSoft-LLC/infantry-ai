# Headless AgentHQ daemon (no Compose UI). Build the context with Gradle, then build the image from it:
#   ./gradlew :superiorSoftLibrary:agentHq:dockerContext
#   docker build -t infantryai/agenthq:dev superiorSoftLibrary/agentHq/build/docker
# `dockerContext` copies this file next to the installDist output (agenthq/), so the image holds exactly what a droplet runs.
# Harness CLIs (claude, opencode, ...) are NOT baked in yet (installer ticket pending): mount them, see README "Docker".

FROM eclipse-temurin:21-jre AS stage
COPY agenthq /opt/agenthq
RUN chmod +x /opt/agenthq/bin/agenthq

FROM eclipse-temurin:21-jre
# git: ticket starts cut worktrees. curl: health check.
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 --home-dir /home/agenthq agenthq \
    && mkdir -p /data /work && chown agenthq:agenthq /data /work
COPY --from=stage /opt/agenthq /opt/agenthq

ENV AGENTHQ_HOST=0.0.0.0 \
    AGENTHQ_PORT=8787 \
    AGENTHQ_STATE_DIR=/data \
    AGENTHQ_HOME=/data \
    AGENTHQ_BIN=/opt/agenthq/bin \
    PATH=/opt/agenthq/bin:$PATH
# AGENTHQ_TOKEN (or AGENTHQ_TOKEN_FILE) is required: the daemon refuses a non-loopback bind without one.

USER agenthq
WORKDIR /work
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${AGENTHQ_PORT}/health" || exit 1
ENTRYPOINT ["java", "-cp", "/opt/agenthq/lib/*", "-Djava.awt.headless=true", "-Xms32m", "-Xmx512m", "com.superiorsoftllc.agenthq.daemon.DaemonMainKt"]
