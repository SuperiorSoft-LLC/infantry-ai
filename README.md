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
