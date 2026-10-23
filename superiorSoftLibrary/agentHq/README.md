# AgentHQ

> **DRAFT (RELEASE-1).** Written for the first public GitHub release. Module paths below are the ones
> in the current monorepo (`:superiorSoftLibrary:agentHq`, ...); they change when AgentHQ is extracted
> into its own repository. See `docs/RELEASE-AUDIT.md` for what still blocks publishing.

AgentHQ runs a team of AI coding agents on your own machine. A small local daemon starts and supervises
agent CLI sessions (Claude Code, pi, opencode, or any CLI you describe in a JSON file), gives each one its
own git worktree, a ticket board, a mailbox to talk to the other agents, and shared project memory. A
desktop app shows every agent, what it is doing, and what it needs from you.

- **Local first.** The daemon binds `127.0.0.1` by default. Your code and your provider credentials stay
  where they already are: AgentHQ drives the CLIs you have installed and logged into.
- **Bring your own harness.** Claude Code and pi are built in; opencode is a configured provider; any
  other CLI can be added with a JSON descriptor, no code change.
- **One agent, one worktree.** A ticket start cuts its own branch, so agents never edit each other's
  checkout.
- **Headless daemon, thin clients.** Everything lives in the daemon behind a versioned WebSocket protocol
  ([`CONTRACT.md`](CONTRACT.md)); the desktop app and the `agenthq` CLI are clients of it.

<!-- SCREENSHOT: main window, agents list grouped by status + chat pane -->
![AgentHQ desktop: agents list and chat](docs/images/screenshot-main.png)

<!-- SCREENSHOT: ticket board modal -->
![AgentHQ ticket board](docs/images/screenshot-board.png)

## Requirements

| Need | Version | Notes |
|---|---|---|
| JDK | 21 or newer | The modules compile with `jvmToolchain(21)`. Gradle itself runs on any JDK 17+; if no JDK 21 is installed, the foojay toolchain resolver downloads one. |
| Gradle | none to install | Use the bundled wrapper (`./gradlew`, Gradle 9.4.1). |
| git | any recent | On `PATH`. Used for worktrees and ticket branches. |
| An agent CLI | at least one | `claude` (Claude Code), `pi`, or `opencode`, installed and logged in, on the daemon's `PATH`. Not needed for the stub mode below. |
| OS | Windows, macOS, Linux | Developed and tested mainly on Windows 11. macOS/Linux launchers ship but are less exercised. |

No Android SDK is needed for the daemon, the CLI or the desktop app.

## Install

```sh
git clone <repo-url> agenthq
cd agenthq
./gradlew :superiorSoftLibrary:agentHq:installDist
```

This produces the `agenthq` CLI in `superiorSoftLibrary/agentHq/build/install/agenthq/bin/`
(`agenthq` for POSIX shells / Git Bash, `agenthq.bat` for Windows). Put that directory on your `PATH`:
agents call `agenthq ...` themselves to read their tickets, inbox and memory.

## Quick start

### 1. Try it with no agent CLI (stub mode)

```sh
./gradlew :superiorSoftLibrary:agentHqUi:runSmoke
```

Opens the desktop app against an in-process stub daemon with throwaway state. Nothing is spawned and no
tokens are spent: use it to check the app works on your machine.

### 2. Daemon + desktop app (two terminals)

```sh
# terminal 1: the daemon, listening on ws://127.0.0.1:8787/ws
./gradlew :superiorSoftLibrary:agentHq:runDaemon

# terminal 2: the desktop app
./gradlew :superiorSoftLibrary:agentHqUi:runUi
```

Or both in one process: `./gradlew :superiorSoftLibrary:agentHqUi:runRealDaemon` (listens on 8788).

In the app, open **New agent** (`Ctrl+N`), pick a harness (`claude` or `pi`) and a model, type the first
prompt and spawn.

### 3. Point it at your project

Set these in the shell that starts the daemon:

| Variable | Meaning |
|---|---|
| `AGENTHQ_REPO` | The git repository agents work in. Each session / ticket gets its own worktree. |
| `AGENTHQ_PROVIDER` | Default provider: `opencode`, `claude` or `stub`. |
| `AGENTHQ_PORT`, `AGENTHQ_HOST` | Listen port (default 8787) and host (default `127.0.0.1`). |
| `AGENTHQ_TOKEN` / `AGENTHQ_TOKEN_FILE` | Bearer token. Required if you bind anything other than loopback. |
| `AGENTHQ_STATE_DIR` | State directory (database, sessions). Default `~/.agenthq`. |

The full list, the port fallback rules and troubleshooting are in [`docs/QUICKSTART.md`](docs/QUICKSTART.md).

## Supported harnesses

| Harness | How it is wired | Status |
|---|---|---|
| **Claude Code** (`claude`) | Built-in harness plugin. Slash commands (`/model`, ...) supported. | Primary, most tested. |
| **pi** (`pi`) | Built-in harness plugin, found by `ServiceLoader`. | Supported; no slash commands yet. |
| **opencode** (`opencode`) | Configured provider (`AGENTHQ_PROVIDER=opencode`). | Supported. |
| **Anything else** | A JSON descriptor in `~/.agenthq/harnesses/*.json`. | See [`docs/harnesses/`](docs/harnesses/) and QUICKSTART §4. |

AgentHQ never stores provider API keys: each CLI uses its own login.

## Project layout

| Module | What it is |
|---|---|
| `agentHq` | The daemon and the `agenthq` CLI (JVM). Protocol: [`CONTRACT.md`](CONTRACT.md). Internals: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). |
| `agenthqUiCore` | Shared Compose Multiplatform UI (screens, models, role portraits). |
| `agentHqUi` | The desktop app (Compose Desktop) and its packaging. |
| `agentHqHeadless` | A guard that boots the daemon with no UI on the classpath, proving the daemon stays headless. |

## Building and testing

```sh
./gradlew :superiorSoftLibrary:agentHq:test
./gradlew :superiorSoftLibrary:agentHqUi:test
```

Tests never bind 8787 or 8788; they use ephemeral ports.

## Status

Pre-1.0. The WebSocket protocol is versioned (`EventVocabulary.VERSION`) and documented in
[`CONTRACT.md`](CONTRACT.md); expect it to move until 1.0. A mobile client on the same daemon is planned.

## License

<!-- OWNER: no license chosen yet. Add a LICENSE file at the repository root and name it here. -->
TBD.

## Contributing

<!-- OWNER: contribution guidelines / CLA / code of conduct, if any. -->
TBD.
