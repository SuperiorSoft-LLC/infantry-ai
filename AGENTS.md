# AGENTS.md: the guide for AI coding agents

Read this first. It is a map, not a manual: every section links to the page that holds the detail. Facts here come
from the code; where a page and the code disagree, the code wins.

Infantry AI is a command post for coding agents: a daemon (ticket board, agents on their own git branches, mail,
shared project knowledge, Lua scripts and hooks) and clients (desktop, web, Android, CLI) that talk to it over a
WebSocket. The app is built like an operating system: a small generic core, and plugins (modules) that provide
every feature. The CLI is `infantry`; `agenthq` is a deprecated alias for the same launcher.

## 1. The rules

1. **Core holds only generic mechanisms.** A feature (a tab, a board, a scripting language, a harness for one agent
   CLI) lives in a plugin. If you need a feature in core, you are probably missing a seam: add the generic seam
   (a slot, a hook kind, a frame table entry), not the feature.
2. **No plugin ids, preset values or build-stack names in core.** Core names a plugin or a stack nowhere in
   behaviour code. Guard tests fail the build when it does (section 6).
3. **Three layers, one direction.** Platform core, then official core plugins, then the orchestration plugin.
   Agents never depend on orchestration. See [Architecture](docs/wiki/Architecture.md).
4. **A plugin sees only its API module.** A UI half compiles against `infantryPlugins` (and the UI core types);
   a daemon half against `infantryDaemonApi`; a harness half against `infantryHarnessApi`. None of them
   may import daemon internals.
5. **No hard-coded limits.** A limit is a named setting (`LimitsGuardTest`). A cost trade-off is a setting whose
   default is the cheapest choice.
6. **Tests never bind ports 8787 or 8788** (the owner's daemon and UI). Use port 0 (`TestPortGuardTest`).
7. **Run one Gradle build at a time.** The test JVMs are memory hungry; two builds in parallel can crash the host.
8. Do not edit a test just to make it pass. If a test is wrong, say why in the commit message.

## 2. Repo layout

Gradle project paths keep the monorepo prefix `:superiorSoftLibrary:`. "Core" means part of the platform core
(generic mechanisms); the rest is client shell, SDK, or plugin.

| Module (folder under `superiorSoftLibrary/`) | What it is | Core? |
|---|---|---|
| `infantry` | Daemon, CLI and MCP server (`DaemonMain.kt`, `CliMain.kt`, `cli/mcp/McpServer.kt`). JVM only. The shell the leaf modules plug into. | core (MPL-2.0) |
| `infantryProtocol` | The wire contract: `Envelope` and the DTOs. A leaf. | core (MPL-2.0) |
| `infantryRunner` | The process runner and watch mechanism. A leaf. | core (MPL-2.0) |
| `infantryCore` | Packages that stay in core: sessions, mailbox, policy, settings, db, harness registry, providers, worktrees, usage. | core (MPL-2.0) |
| `infantryDaemonApi` | The ONE surface a daemon plugin compiles against (`DaemonApi`, `DaemonPlugin`, `DaemonTool`). | platform SDK (Apache-2.0) |
| `infantryHarnessApi` | The ONE surface a harness plugin compiles against (`HarnessPlugin`, `Harness`, `HarnessHost`). A leaf. | platform SDK (Apache-2.0) |
| `infantryBoard` | The board's lower-level packages (epics, brief, ledger, context, ticketfiles). Depends on the daemon API, never on `infantry`. | platform SDK (Apache-2.0) |
| `infantryPlugins` | The UI plugin contract (`plugin.api`: `ModuleManifest`, config types) and the host's loading rules (`plugin.host`: `PluginTree`). No Compose. | platform SDK (Apache-2.0) |
| `infantryUiCore` | The shared client: Compose UI shell, `Controller`, `DaemonClient`, `UiPlugin`, `UiRegistrar`. Kotlin Multiplatform (JVM, Android, wasm). | platform client (Apache-2.0) |
| `infantrySdkBom`, `infantrySdkCatalog` | Publishing only: the SDK's Maven BOM and Gradle version catalog, so an out-of-tree plugin pins one SDK version. No sources. | platform SDK (Apache-2.0) |
| `infantryAppUi` | The app UI outside the shell; holds the generated module list web and Android compile in. | app (Apache-2.0) |
| `infantryUi` | The desktop window (Compose Desktop). | app (Apache-2.0) |
| `infantryWeb` | The browser client (Kotlin/wasm); the daemon serves its bundle at `/`. | app (Apache-2.0) |
| `infantryAndroid` | A thin Android application shell around `infantryUiCore`. No sources of its own. | app (Apache-2.0) |
| `infantryHeadless` | Test-only: proves the daemon runs with no UI. | test (Apache-2.0) |
| `samples/markdownEnginePlugin` | An example markdown engine plugin built to a jar. | sample (Apache-2.0) |

Plugins live outside that folder, in `plugins/` (see [plugins/README.md](plugins/README.md)):

| Folder | What it holds |
|---|---|
| `plugins/official/` | Plugins by the app's own company (`Infantry_*`): every built-in tab, panel and daemon feature. Each is its own Gradle project. |
| `plugins/community/` | Plugins added by dropping a folder in. Off until `plugins/modules.json` enables them. |
| `plugins/demo/` | Demo-only modules, compiled in with `-Pdemo=true` for web and Android. |
| `plugins/examples/` | Reference plugins that compile and are documented: a daemon tool, a policy hook, a harness. |
| `plugins/_template/` | The starter to copy. Builds like any module so it always compiles. |
| `plugins/core/` | Empty by design (only a README); no module is core any more. |

A plugin folder can hold up to three halves: the UI half (root `build.gradle.kts`, `src/`), a daemon half
(`daemon/`, Gradle project `:daemon-plugin-<folder>-<Module>`) and a harness half (`harness/`, project
`:harness-plugin-<folder>-<Module>`). Its `module.json` declares all of them. Folders are found by scanning in
`settings.gradle.kts`; nothing names a module.

A plugin kept in its OWN repository starts from `templates/plugin-repo` instead (a standalone Gradle build against the
published SDK; `scripts/template-check.sh` proves it still builds). `plugins/_template` is the in-repo starter.

## 3. Build, run, test

A test on the [known-failure list](docs/KNOWN-FAILURES.md) fails on dev too: do not chase it unless your ticket is about it.

JDK 21 (the toolchain resolves it). Toolchain versions live in `gradle/libs.versions.toml` (Kotlin 2.3.10, Compose
Multiplatform 1.10.3, Ktor 3.4.1 (the daemon build pins every `io.ktor` module to it; the catalog's `ktorClientCio` is a stale 3.2.3), kotlinx.serialization 1.10.0, AGP 9.0.1); check it before you use an API from memory.
Gradle runs with `-Xmx6g` and the Kotlin daemon with 3 GB (`gradle.properties`). From the repository root:

```
./gradlew :superiorSoftLibrary:infantry:compileKotlin :superiorSoftLibrary:infantryUi:compileKotlin --console=plain
./gradlew :superiorSoftLibrary:infantry:runDaemon          # daemon on ws://127.0.0.1:8787/ws
./gradlew :superiorSoftLibrary:infantryUi:runUi            # desktop UI against it
./gradlew :superiorSoftLibrary:infantryUi:runSmoke         # UI against an in-process stub daemon
./gradlew :superiorSoftLibrary:infantry:installDist        # the `infantry` CLI (build/install/infantry/bin)
./gradlew :plugins:official:Infantry_Notes:jar            # one plugin's UI jar
./gradlew :daemon-plugin-official-Infantry_Notes:jar      # the same plugin's daemon jar
```

**Run ONE test class:**

```
./gradlew :superiorSoftLibrary:infantry:test --tests "com.superiorsoftllc.infantry.DocsFreshnessGuardTest" --console=plain
```

Replace the module path and class name. While you work, run only the classes of the code you changed. The full
gate over a batch of changes is `scripts/infantry-gate.sh` ([docs/GATE.md](docs/GATE.md)). Read build output with
`tail -n 20`; errors matter, warning counts do not. More: [CONTRIBUTING.md](CONTRIBUTING.md),
[QUICKSTART.md](superiorSoftLibrary/infantry/docs/QUICKSTART.md).

## 4. Where each kind of change goes

| You want to | Go to | Recipe |
|---|---|---|
| Add a tab, panel or settings card | a plugin's UI half (`UiPlugin.register`) and `module.json` | [add a UI panel plugin](docs/wiki/recipes/add-ui-panel-plugin.md) |
| Let another plugin extend yours | a slot in your `module.json`; the child fills it | [split into a child plugin](docs/wiki/recipes/split-into-child-plugin.md) |
| Add an agent/MCP tool, route or hook on the daemon | a plugin's daemon half (`DaemonPlugin.start`) | [add a daemon tool](docs/wiki/recipes/add-daemon-tool.md) |
| Add a setting | a `config` field in `module.json`, or `SettingScopes` for a core key | [add a setting](docs/wiki/recipes/add-setting-with-scopes.md) |
| Automate a process | a Lua script and a hook | [add a Lua hook](docs/wiki/recipes/add-lua-hook.md) |
| Support another agent CLI | a harness plugin or a JSON descriptor | [add a CLI harness](docs/wiki/recipes/add-cli-harness.md) |
| Add a model group to the picker | a harness's `models` | [add a provider](docs/wiki/recipes/add-provider.md) |
| Change the wire format | `infantryProtocol` (`Protocol.kt`), then both ends | [Architecture](docs/wiki/Architecture.md) |
| Change what core can do | only if it is a generic mechanism; read [docs/DAEMON-CORE.md](docs/DAEMON-CORE.md) first | none |

If you are about to touch `DaemonMain.kt` (very large) or a core module to add a feature, stop and check whether a
plugin can do it. Before you change a file, ask the knowledge base: `infantry issue precheck <path>`.

## 5. Documentation map

- [Architecture](docs/wiki/Architecture.md): layers, daemon, protocol, request flow, slots, scripting. Start here.
- [Recipes](docs/wiki/recipes/README.md): seven worked "how to add X" guides.
- [Plugins](docs/wiki/Plugins.md) and [Make a plugin](docs/wiki/make-a-plugin.md): `module.json`, a built example.
- [UI plugin API](docs/wiki/plugin-api.md), [Daemon plugin API](docs/wiki/daemon-plugin-api.md), [Harnesses](docs/wiki/Harnesses.md).
- [Scripting](docs/wiki/Scripting.md), [Settings](docs/wiki/Settings.md), [Licensing](docs/wiki/licensing.md).
- [Kotlin notes](docs/wiki/kotlin-notes.md): toolchain versions, UI core source sets and expect/actual, Compose MP pitfalls, the build flags.
- Design records (older, dated; the code wins): [DAEMON-CORE](docs/DAEMON-CORE.md),
  [AGENT-QUICKREF](superiorSoftLibrary/infantry/docs/AGENT-QUICKREF.md) (the CLI), [MCP](superiorSoftLibrary/infantry/docs/MCP.md).
- [docs/README.md](docs/README.md): one line per `docs/*.md` file, marked current reference, design record (dated) or history, with the wiki page that replaces a record. Search it before you grep `docs/`.
- [llms.txt](llms.txt): the same map in the llms.txt format.

## 6. Guards (tests that keep the rules true)

All under `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/`:

| Guard test | What it enforces |
|---|---|
| `daemon/DaemonCoreGuardTest.kt` | The daemon core line: leaf modules depend on no other module and no plugin. |
| `CoreHasNoScriptingGuardTest.kt` | Core names no script engine or Lua identifier; scripting is a plugin. |
| `daemon/BuiltinToolsListGuardTest.kt` | Every built-in agent tool left in core is on an explicit list with its reason. |
| `daemon/HarnessCoreGuardTest.kt` | The core jar carries no concrete harness. |
| `daemon/SdkSurfaceGuardTest.kt` | The packages the daemon and harness class loaders expose equal the published SDK modules (`infantryDaemonApi`, `infantryPlugins`, `infantryHarnessApi`). |
| `daemon/BoardImportGuardTest.kt` | Core reaches the board only through the daemon API. |
| `orchestration/OrchestratorBoundaryGuardTest.kt` | The runner side does not import orchestration. |
| `policy/NoPresetInCoreGuardTest.kt` | Policy mechanism is core, preset values are a plugin's. |
| `runner/StackNeutralGuardTest.kt` | Core main code names no build stack. |
| `settings/LimitsGuardTest.kt` | No unnamed numeric limit in `src/main`. |
| `daemon/TestPortGuardTest.kt` | Tests never bind 8787 or 8788. |
| `daemon/HarnessContractGuardTest.kt` | Every repo harness module's `harness.contract` equals `HARNESS_API_VERSION` (a lagging one is refused at load). |
| `DocsFreshnessGuardTest.kt` | Every file path and public type named in this file and `docs/wiki/**` exists. |
| `SdkTypesDocumentedGuardTest.kt` | Every public top-level type of `infantryPlugins`, `infantryDaemonApi` and `infantryHarnessApi` is named in its API page (`plugin-api.md`, `daemon-plugin-api.md`, `Harnesses.md`); an internal-but-public helper goes on the guard's commented allow-list. |

More guards outside that folder or not listed above (same rule: read the failure, fix the code, not the test):

| Guard test | What it enforces |
|---|---|
| `LicenseMapGuardTest.kt` | Every source file carries the SPDX id `scripts/license-paths.txt` gives its path (`scripts/license-headers.sh` writes it). |
| `SharedDocsGuardTest.kt` | Product facts (tagline, license line) are stated once in `docs/shared/product.md` and wrapped in `shared:` markers elsewhere. |
| `harness/HarnessApiImportGuardTest.kt` | `infantryHarnessApi` names no other infantry package and depends on nothing. |
| `daemon/plugins/PluginClassPathGuardTest.kt` | A daemon half that uses an app class outside the SDK shades it into its own jar (else NoClassDefFoundError at run time). |
| `daemon/plugins/HookKindsGuardTest.kt`, `daemon/plugins/BoardApiImportGuardTest.kt` | Hook kinds and board API imports stay on their declared lists. |
| `daemon/NoDemoInDaemonCoreGuardTest.kt` | The demo lockdown lives in `plugins/demo/Demo_Lock`, never in daemon core. |
| `agents/registry/AgentRegistryLayerGuardTest.kt` | The agent registry never imports orchestration (board, tickets, worktrees, pipeline). |
| `triggers/TriggerGuardTest.kt`, `triggers/TriggerPortsGuardTest.kt` | Trigger code reaches the board and sessions only through its ports. |
| `infantryUiCore` jvmTest: `NoDemoInCoreGuardTest`, `PolicyGateGuardTest`, `HandCursorGuardTest`, `ControllerEchoGuardTest` | UI core names no demo; every declared plugin action asks a gate; clickables use the hand-cursor wrappers in `Clickables.kt`; the controller echo rule. |
| `infantryUi` test: `PerfGuardTest`; `Infantry_Updater` daemon test: `UpdateTokenGuardTest` | UI perf budget; no update token ever enters the repo. |

The docs guard: when you rename or delete a file or type, it fails with the doc line that names it. Fix the doc.
An example path that the reader is meant to create goes inside a fenced code block, or on a line that carries the
marker `docs-freshness: ignore` in an HTML comment.

## 7. Old names

The product was renamed from AgentHQ to Infantry AI (RENAME-2). Code still reads the old names as fallbacks, so search
for either spelling. Facts below come from `InfantryEnv`, `LegacyToolNames`, `McpServeConfig`, `ProjectFiles`,
`StateDirMigration`, `RunnerFactory` and `AhqRoleIcons`.

| Old name | Current name | Still read? |
|---|---|---|
| `AGENTHQ_<X>` env var (e.g. `AGENTHQ_PORT`, `AGENTHQ_TOKEN`, `AGENTHQ_TOKEN_FILE`) | `INFANTRY_<X>` | Yes. `INFANTRY_*` wins; the old name is a fallback. `InfantryEnv.all` mirrors both ways. |
| `AGENTHQ_RUNNER` | `INFANTRY_RUNNER` | Yes, fallback. |
| `AGENTHQ_UPDATE_TOKEN` | `INFANTRY_UPDATE_TOKEN` | Yes, via `InfantryEnv`; scrubbed from child processes under both names. |
| `-Dagenthq.<x>` system property | `-Dinfantry.<x>` | Yes. `InfantryEnv.mirrorProperties` copies it unless the new one is set. |
| `agenthq_<tool>` MCP/tool name (e.g. `agenthq_ticket_get`) | `infantry_<tool>` | Yes, as an alias. Only `infantry_*` is listed. Scripts map it too. |
| `agenthq` CLI command | `infantry` | Yes, a deprecated alias for the same launcher. |
| `.agenthq/mcp.json` | `.infantry/mcp.json` | Yes, read when the new file is absent. |
| `.agenthq/` project folder (uploads, files) | `.infantry/` | Yes, read when a file is not under the new folder. Writes go to `.infantry/`. |
| `~/.agenthq` state dir | `~/.infantry` | Migrated on first start (`StateDirMigration`); names containing `agenthq` are renamed in the copy. |
| `feature-agenthq-mvp` branch | (none) | Still the name of the project branch. It is not renamed in code. |
| `Ahq*` prefix (e.g. `AhqRoleIcons`) | (none yet) | Still the class name; a naming leftover, not an alias. |
