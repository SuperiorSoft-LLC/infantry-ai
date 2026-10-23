# AgentHQ

AgentHQ is a daemon, CLI and desktop/Android UI for running and supervising teams of coding agents
against a ticket board (tickets, roles, mail, shared project knowledge).

Status: **private prototype**. License: pending (none chosen yet).

## Modules (all under `superiorSoftLibrary/`, prefix kept so `:superiorSoftLibrary:*` paths stay valid)

- `agentHq` – daemon + `agenthq` CLI (JVM)
- `agenthqUiCore` – shared UI core (Compose Multiplatform)
- `agentHqUi` – desktop UI
- `agentHqAndroid` – Android shell
- `agentHqHeadless` – test-only guard that the daemon runs with no UI

## Build

Requires JDK 21 (Gradle toolchain resolution via foojay) and, for the Android module, an Android SDK.

    ./gradlew :superiorSoftLibrary:agentHq:compileKotlin :superiorSoftLibrary:agentHqUi:compileKotlin
    ./gradlew :superiorSoftLibrary:agentHq:installDist     # build/install/agenthq/bin/agenthq

Run only one Gradle build at a time. Docs live in `docs/AGENTHQ-*.md`; see `docs/AGENTHQ-INSTALL.md`.

Secrets guard: `./gradlew verifySecretsNotTracked` fails if keys, keystores or tokens are tracked.
Signing secrets belong in `secrets.local.properties` (gitignored) or environment variables.

## Docker (headless daemon)

A headless image of the daemon (no UI) for servers. The image name follows the pending rename: `infantryai/agenthq`
(placeholder until the rename lands). **Not published yet**; build it locally:

    ./gradlew :superiorSoftLibrary:agentHq:dockerContext
    docker build -t infantryai/agenthq:dev superiorSoftLibrary/agentHq/build/docker

Run (the token is mandatory: the daemon refuses a non-loopback bind without one):

    docker run -d --name agenthq -p 127.0.0.1:8787:8787 \
      -e AGENTHQ_TOKEN="$(openssl rand -hex 32)" \
      -v agenthq-data:/data infantryai/agenthq:dev

- **Volume**: `/data` holds the database, logs and state (`AGENTHQ_STATE_DIR`). Keep the same volume across image upgrades.
- **Config**: `AGENTHQ_PORT` (default 8787), `AGENTHQ_HOST` (default `0.0.0.0`), `AGENTHQ_TOKEN` or `AGENTHQ_TOKEN_FILE`
  (e.g. a Docker secret at `/run/secrets/agenthq_token`); other `AGENTHQ_*` variables are read as usual.
- **Health**: `HEALTHCHECK` polls `GET /health` (the one path open without a token).
- **Harness CLIs**: not bundled yet (the installer ticket will provide them). Until then mount them, e.g.
  `-v ~/.local/bin/claude:/usr/local/bin/claude:ro` plus their config/credentials dirs, and put repos under `/work`.
- **Clients**: in the desktop or Android app add a remote endpoint `ws://<host>:8787/ws` (or `wss://` behind TLS) with the token.
- **Security**: the token is the only credential and it grants command execution through agents. Never expose the raw port
  to the internet: bind to loopback (`-p 127.0.0.1:8787:8787`) and put a TLS reverse proxy (Caddy, nginx) or a VPN/SSH
  tunnel in front (it must pass WebSocket upgrades). The container runs as non-root (uid 10001). Do not bake the token into an image.
