<!-- SPDX-License-Identifier: Apache-2.0 -->
# AGENTS.md: the guide for AI coding agents

Read this first. It is a map, not a manual: every section links to the page that holds the detail. Facts here come
from the code; where a page and the code disagree, the code wins.

Infantry AI runs on **BattalionOS**, the MPL-2.0 core and shell (daemon core, protocol, runner, desktop shell); code, crate and CLI names keep the `infantry` prefix for now.

Infantry AI is a command post for coding agents: a daemon (ticket board, agents on their own git branches, mail, shared
project knowledge, Lua scripts and hooks) and clients (the Tauri desktop app, the Android app, the CLI) that talk to it
over a WebSocket. The main branch is the **Rust framework**: the daemon is a Rust binary, the app is Tauri 2 with a React
front end, and features arrive as plugins (WASM components, or native sidecars). The CLI is `infantry`.

The Kotlin/Gradle version is kept on the `legacy-java` branch. Every section below that names Kotlin, Gradle or the
`superiorSoftLibrary/` tree is history: see [section 8](#8-legacy-java-kotlin-history).

## 1. The rules

1. **Core holds only generic mechanisms.** A feature (a tab, a board, a scripting language, a harness for one agent
   CLI) lives in a plugin or a unit that the core only names through a seam. If you need a feature in core, you are
   probably missing a seam: add the generic seam (a `FrameUnit`, a hook kind, a WIT import), not the feature.
2. **No plugin ids, preset values or build-stack names in core.** `infantry-core` names no plugin id. The guard
   `core_names_no_plugin_id` in `rust/crates/infantry-guards` fails the build when it does (section 6).
3. **Layers point down only.** Platform core (L1), runner (L2), harnesses (L3), orchestration (L4), then the composition
   roots. Agents never depend on orchestration. See [Architecture](docs/wiki/Architecture.md) and
   [docs/rust-port/LAYERS.md](docs/rust-port/LAYERS.md). The guard `every_workspace_crate_respects_the_layer_map`
   enforces it.
4. **A plugin sees only its SDK.** A daemon plugin compiles against `infantry-plugin-sdk` and the WIT world in
   `infantry-plugin-wit`. It does not import daemon internals (`infantry-daemon`, `infantry-plugin-host`).
5. **No hard-coded limits.** A limit is a named setting. A cost trade-off is a setting whose default is the cheapest
   choice ([Settings](docs/wiki/Settings.md)).
6. **Tests never bind ports 8787, 8788, 8797 or 8798.** Those are the owner's daemon and UI and the PM's MCP pair
   (`DEV_OWNER_PORTS` in `rust/crates/infantry-daemon/src/health.rs`). Use port 0 or a free port. The daemon's own
   default is 18990, and it refuses 8797 (the Kotlin app's port).
7. **One heavy build at a time.** Do not run two `cargo` builds or two `pnpm tauri` builds at once on the same machine.
8. **Do not edit a test just to make it pass.** If a test is wrong, say why in the commit message.
9. **Before adding a helper or UI pattern, search for the existing seam.** If none fits, add the generic seam, never a
   local copy (section 4, "Shared seams").
10. **Before adding UI (dialog, modal, form, card), search the React app for the existing component** in
    `app/packages/ui` (section 4). New UI needs a reason in the ticket, and the done comment lists the components it reused.

## 2. Repo layout (main, Rust)

| Folder | What it is |
|---|---|
| `rust/` | The Cargo workspace. `rust/crates/*` holds the core crates; `rust/plugins/official/*` holds the official plugins. |
| `rust/crates/infantry-daemon` | The daemon: tokio and axum, serves `/ws` (v1 WebSocket) and `/health`. Composition root. |
| `rust/crates/infantry-distribution` | The `infantry-daemon` binary (`main.rs`): links the daemon library and the concrete harnesses. |
| `rust/crates/infantry-cli` | The native `infantry` binary: serves `infantry mcp serve` and most other commands itself. A command it does not have is handed to the Kotlin launcher (see [Command line](docs/wiki/CommandLine.md)). |
| `rust/crates/infantry-protocol` | The wire contract: `Envelope`, DTOs and frame names. |
| `rust/crates/infantry-core` | Platform core: the `FrameUnit` registry, event bus, db handle, settings. |
| `rust/crates/infantry-plugin-wit`, `infantry-plugin-host`, `infantry-plugin-sdk` | The plugin ABI (WIT), the host (wasmtime components and native sidecars) and the SDK a plugin builds with. |
| `rust/crates/infantry-runner*`, `infantry-harness*` | Runners (local, linked remote) and the agent harnesses (claude, codex, opencode, pi). |
| `rust/crates/infantry-board`, `infantry-orchestrator-api` | The ticket board and the orchestration interface (L4). |
| `rust/crates/infantry-mail`, `infantry-scripting`, `infantry-vault`, `infantry-usage`, `infantry-projects`, `infantry-knowledge` | Platform features in core: mail and notifications, Lua scripting, secrets, usage and pacing, projects and worktrees, roles and skills. |
| `rust/crates/infantry-migrate` | Snapshots a Kotlin state dir into a Rust one. |
| `rust/crates/infantry-guards` | Test-only layer and rule guards. |
| `rust/crates/conformance` | Black-box v1 WebSocket conformance suite, runnable against any daemon URL. |
| `rust/plugins/official/` | The official plugins (14 daemon halves; see [Plugins](docs/wiki/Plugins.md)). |
| `rust/plugins/stage.sh` | Builds the official daemon plugins as WASM components and lays them out for the daemon. |
| `app/` | The pnpm workspace. `app/packages/ui` and `app/packages/client` are the React app; `app/shell` is the Vite shell; `app/src-tauri` is the Tauri 2 host (crate `infantry-tauri`) with its generated Android project in `gen/android`. |
| `scripts/` | Owner build and proof scripts: `rust-owner-build.ps1`, `rust-build-env.ps1`, `rust-update-e2e.ps1`, and the release tools. |
| `docs/` | Design records (`docs/rust-port/*.md`), `docs/wiki/` (the wiki) and `docs/shared/product.md` (product facts). |
| `.github/workflows/rust-release.yml`, `rust-promote.yml` | The release pipeline and its promotion to the stable and nightly feeds. |

Kotlin folders still present in this tree: `superiorSoftLibrary/`, `plugins/` (Kotlin plugins), `templates/`, `gradle/`,
`build-logic/`, `build.gradle.kts`, `settings.gradle.kts`. They are history. See section 8.

## 3. Build, run, test

Rust commands run from `rust/`. App commands run from `app/`. Use the PowerShell or Git Bash shell on Windows.

```
# daemon and crates (from rust/)
cargo build -p infantry-distribution
cargo run -p infantry-distribution -- --port 18990        # a daemon on ws://127.0.0.1:18990/ws
curl http://127.0.0.1:18990/health                    # the health answer
cargo build --profile owner-test -p infantry-distribution   # quick owner build: no LTO, incremental
cargo test -p infantry-guards                         # the layer and rule guards
cargo test -p <crate>                                 # one crate, e.g. -p infantry-board

# official plugins (WASM components, from the repo root)
rust/plugins/stage.sh <out-dir> [release]            # then point the daemon at it with INFANTRY_PLUGINS_DIR

# app (from app/)
pnpm install --frozen-lockfile
pnpm dev                                              # the shell in the browser (Vite)
pnpm tauri dev                                        # the desktop app against the dev server (localhost:1420)
pnpm test && pnpm typecheck

# owner build of app + daemon (Windows, PowerShell, from the repo root)
scripts/rust-owner-build.ps1                          # builds the UI, the daemon, the shell and packages a folder

# installer and Android
cd app && pnpm installer                              # a debug installer
cd app && pnpm tauri android build --apk --target aarch64   # the Android APK (what the release workflow runs)
```

Every Rust build on the owner's machine, in any worktree, shares one target dir outside the worktrees
(`%LOCALAPPDATA%\infantry-dev\preview-target`; set by a machine-local `.cargo/config.toml` above the checkout and by
`scripts/rust-build-env.ps1`, setting `INFANTRY_CARGO_TARGET_DIR`). Cargo locks it, so builds queue (rule 7). After a
MERGED step, `pwsh -NoProfile -File scripts/cargo-target-sweep.ps1` trims artifacts unused for `INFANTRY_SWEEP_DAYS` days.
Dev builds keep line tables only for workspace crates and no debug info for dependencies (a debugger session:
`CARGO_PROFILE_DEV_DEBUG=full`); the settings, their measured effect and `scripts/rust-build-bench.ps1` are in
[Build speed](docs/wiki/Build-speed.md).

Read build output with `tail -n 20` and look at errors, not warning counts. Versions: `rust/Cargo.toml` (the workspace
and profiles) and `app/package.json` (pnpm 11.21.0, `@tauri-apps/cli` 2). Check them before you use an API from memory.

For the installer, the updater, and the Android build, read [Install](docs/wiki/Install.md),
[Android](docs/wiki/Android.md) and [Updates](docs/wiki/Updates.md).

## 4. Where each kind of change goes

| You want to | Go to | Recipe |
|---|---|---|
| Add a daemon tool, route or hook from a plugin | the plugin's daemon half (`rust/plugins/official/<Module>/`) and its `module.json` | [add a daemon tool](docs/wiki/recipes/add-daemon-tool.md) |
| Add a tab, panel or card from a plugin | the plugin's web UI half (`<Module>/ui/`, module.json `ui`) | [Web UI plugins](docs/wiki/Web-UI-plugins.md) |
| Add a panel or screen in the app | the React app (`app/shell/src`, `app/packages/ui`, `app/packages/client`) | [Plugins](docs/wiki/Plugins.md) |
| Add a setting | the daemon settings and `ui.json` | [Settings](docs/wiki/Settings.md) |
| Automate a process | a Lua script and a hook | [Scripting](docs/wiki/Scripting.md), [add a Lua hook](docs/wiki/recipes/add-lua-hook.md) |
| Support another agent CLI | a harness crate on `infantry-harness` | [add a CLI harness](docs/wiki/recipes/add-cli-harness.md) |
| Add a model group | the harness's models | [add a provider](docs/wiki/recipes/add-provider.md) |
| Change the wire format | `infantry-protocol`, then both ends | [Architecture](docs/wiki/Architecture.md) |
| Change what core can do | only if it is a generic mechanism; read [docs/rust-port/LAYERS.md](docs/rust-port/LAYERS.md) first | none |

If you are about to add a feature to `infantry-daemon` or to a core crate, check first whether a plugin can do it.

### Shared seams: reuse, never copy

| You need | Use |
|---|---|
| Notices, toasts | `app/packages/client/src/toasts.ts` and `app/shell/src/components/layout/toast-host.tsx` (the notification bus is in `infantry-mail`) |
| A plugin's child process | `infantry-proc` (every `Command` is created windowless on Windows) and `infantry-runner` |
| A child process spawn | `infantry-proc`; a direct `Command::new` outside it fails the guard `no_spawn_bypasses_the_windowless_helper` |
| A daemon plugin's storage, events, HTTP, settings | the WIT imports `storage`, `events`, `http`, `settings` (granted by `module.json`) |
| Markdown in the app | `app/packages/client/src/markdown.ts` |
| Secrets | `infantry-vault` (never a plain-text copy) |
| Usage and pacing | `infantry-usage` |

## 5. Documentation map

- [Mini AI OS](docs/wiki/Mini-AI-OS.md): the identity (a mini AI operating system), boot chain, plugins as apps. Read first.
- [Architecture](docs/wiki/Architecture.md): layers, daemon, protocol, the request flow, plugins, scripting. Start here.
- [Install](docs/wiki/Install.md), [Android](docs/wiki/Android.md), [Updates](docs/wiki/Updates.md).
- [Plugins](docs/wiki/Plugins.md), [Make a plugin](docs/wiki/make-a-plugin.md), [Daemon plugin API](docs/wiki/daemon-plugin-api.md),
  [Web UI plugins](docs/wiki/Web-UI-plugins.md).
- [Recipes](docs/wiki/recipes/README.md): worked "how to add X" guides.
- [Harnesses](docs/wiki/Harnesses.md), [Runners](docs/wiki/runner-quickstart.md), [Command line](docs/wiki/CommandLine.md).
- [Scripting](docs/wiki/Scripting.md), [Settings](docs/wiki/Settings.md), [Health](docs/wiki/Health.md), [Notifications](docs/wiki/Notifications.md).
- [Rust port design records](docs/rust-port/PLAN.md): the plan, the layer table, the migration, parity and the move-over bar.
- [docs/README.md](docs/README.md): one line per `docs/*.md` file, marked current, design record (dated) or history.
- [llms.txt](llms.txt): the same map in the llms.txt format.

## 6. Guards (tests that keep the rules true)

Rust guards, run with `cargo test -p infantry-guards` (and each crate's own tests):

| Guard | What it enforces |
|---|---|
| `every_workspace_crate_respects_the_layer_map` | Dependencies point down only; every crate is in the layer map (`layers.rs`). |
| `core_names_no_plugin_id` | Core crates name no plugin id. |
| `no_spawn_bypasses_the_windowless_helper` | Every child process goes through `infantry-proc`. |

Port guards in tests: no test binds 8787, 8788, 8797 or 8798 (rule 6).

<details>
<summary>Legacy guards (Kotlin tree, on the legacy-java branch)</summary>

The Kotlin guard tests (`LimitsGuardTest`, `DocsFreshnessGuardTest`, `SharedDocsGuardTest`, `NotificationGuardTest`,
`WasmCommonMainGuardTest` and others under `superiorSoftLibrary/infantry/src/test`) belong to the Kotlin build. They
are not run on main. See [Architecture](docs/wiki/Architecture.md) for the Rust equivalents.

</details>

## 7. Old names

The product was renamed from AgentHQ to Infantry AI. The Rust daemon still reads the old environment names as fallbacks
(`rust/crates/infantry-daemon/src/auth.rs`: `AGENTHQ_*` after `INFANTRY_*`).

| Old name | Current name | Still read? |
|---|---|---|
| `AGENTHQ_<X>` env var (e.g. `AGENTHQ_PORT`, `AGENTHQ_TOKEN`, `AGENTHQ_TOKEN_FILE`) | `INFANTRY_<X>` | Yes. `INFANTRY_*` wins. |
| `agenthq` CLI command | `infantry` | Yes, a deprecated alias for the same launcher (Kotlin era). |
| `~/.agenthq` state dir | `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` (Rust) | The Rust state dir is new; `infantry-migrate` copies a Kotlin state dir. |
| `Ahq*` prefix (e.g. `AhqRoleIcons`) | (none yet) | Kotlin-era class name. |

## 8. legacy-java (Kotlin history)

The Kotlin app and its Gradle build live on the `legacy-java` branch. Pages that describe Kotlin behaviour carry the
banner `legacy-java` and keep the old text under a `Legacy (Kotlin) reference` heading. Current Kotlin-only pieces in
this tree:

- `superiorSoftLibrary/` (the Kotlin daemon, UI and SDK), `plugins/` (Kotlin plugins), `templates/plugin-repo`, `gradle/`,
  `build-logic/`, `build.gradle.kts`, `settings.gradle.kts`, `gradlew`.
- The Kotlin daemon can still run as a child behind the Rust daemon during the port (`--legacy-backend`, see
  `rust/crates/infantry-daemon/src/main.rs`).
- A command the native CLI does not have yet still runs through the Kotlin launcher (`rust/crates/infantry-cli/src/launcher.rs`, `cli/mod.rs` `run`).
- Parity status and gaps: [PARITY.md](docs/rust-port/PARITY.md), [PARITY-GAPS.md](docs/rust-port/PARITY-GAPS.md).
