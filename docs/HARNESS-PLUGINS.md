# HARNESS-PLUGIN-1: harnesses as installable plugins (ticket c0ba8e4c)

Goal (owner): core contains no harness. Claude, opencode, pi and any later harness are each an **installation**; a daemon
with none installed starts, says so, and offers to install one. This is about the harness **adapter code** (argv, stream
translation, session adapter, models, hooks); the harness **CLI tool** install (npm, commit dd7900ed) is a separate
concern that the plugin will declare, not own.

Related: RUNTIME-LOAD-1 (`docs/RUNTIME-LOAD.md`: harness list identical with full and empty plugins dir), DAEMON-PLUGINS-1
(`docs/DAEMON-PLUGINS.md`: tiers, grants, bundled root, `DaemonApiClassLoader`), CORE-SPLIT-A (e7fb5a60: runner and protocol
are leaf modules), STALL-3/STALL-4 (d258b738, 14ac9383: watch hooks per harness, `PiLaunchExtras`, `ToolProfiles`).

## 1. Audit

### 1.1 Can `HarnessRegistry` take plugins from outside the class path today?

The registry itself: **yes**. `HarnessRegistry.discover(HarnessPluginSource)` is source-agnostic, does the contract-version
handshake, refuses duplicates and commits all-or-nothing; `unload(pluginId)` exists. What was missing was a source: the
only one was `ServiceLoaderPluginSource` (the daemon's own class path). Step H1 (this ticket) adds
`JarHarnessPluginSource` (jars outside the class path, one `URLClassLoader` each). It is **not wired** into the daemon.

But the registry is only half of how a harness runs. There are **two harness layers** today:

| layer | what | harnesses in it | how registered |
|---|---|---|---|
| `AgentProvider` (`provider/`) | what `SessionManager` actually drives | `opencode` (the hard-coded DEFAULT), `claude`, `stub` | `ProviderFactory.builders` map, compiled in; `AGENTHQ_PROVIDER` / `AGENTHQ_PROVIDERS` pick |
| `HarnessPlugin` -> `HarnessRegistry` (`harness/`) | WIRE-1; a `CliHarness` is adapted to an `AgentProvider` by `HarnessProvider` | `claude` (again), `pi` | `META-INF/services` in the daemon jar (`ServiceLoaderPluginSource`), then JSON descriptors from `<config>/harnesses/` |

So `claude` exists twice (provider wins on the id), `pi` only as a plugin, and **`opencode` is not a plugin at all**.
JSON descriptor harnesses (HARNESS-DESC-1) already load from a folder at run time; that is a no-code harness, not a code
plugin, and it stays.

### 1.2 Per harness: what the adapter code depends on (plugin -> daemon)

| harness | code today (lines) | daemon internals it imports | size to decouple |
|---|---|---|---|
| **pi** | `harness/pi/*` (881): PiHarnessPlugin, PiCommand, PiStreamTranslator, PiSessionAdapter, PiModelCatalog, PiLaunchExtras, PiTodoExtension | harness API (`CliHarness`, `HarnessPlugin`, `HarnessRequest/Event`, `HarnessTranslator`, `ToolApproval`, `HarnessCapability`); runner (`ProcessSpec`, `ProcessRunner`, `PipedProcessRunner`, `RunningProcess`, `ProcessListener`, a leaf module already); `session.host` (`HarnessSessionAdapter`, `AdapterSink`, `HostedProcess`, `TurnEnd`); `provider.RunRequest`, `provider.HarnessModelCatalog`; `protocol.ProviderModels`; `executables.CliExecutables/CliTools` | **M**. Cleanest one: already a `HarnessPlugin`. Blockers are global mutable seams the daemon sets: `PiLaunchExtras.builtIn` (DaemonMain 1115: todo extension + `WatchHooks.piLaunchArgs`) and `PiLaunchExtras.args` (experiments `PiToolBridgeRegistration`) |
| **claude** | `harness/claude/*` (338) + `claude/*` ClaudeCommand, ClaudePermissionMode, ClaudeSessionAdapter + `provider/ClaudeCodeProvider` + `providerconfig/ClaudeConfigReader` (1,103 total) | everything pi uses, plus `prompts.PromptAsker/QuestionSpec`, `protocol.PromptOption` (owner questions), `daemon.AgentHqMcp` (MCP config), `daemon.WatchHooks` (watch settings file), `session.InfantryEnv`, `session.agentHqSetting`, `provider.AgentProvider`, `provider.ClaudeCodeProvider` (the plugin wraps the provider) | **L**. Two layers to merge into one plugin; MCP + watch + prompt seams must become host services |
| **opencode** | `opencode/*` OpenCodeCommand(s), OpenCodePermissions, OpenCodeServeAdapter (28.7K) + `provider/OpenCodeProvider` + `providerconfig/OpenCodeConfigReader` (1,079 total) | runner, `session.host`, `provider.RunRequest`, `harness.ToolApproval/HarnessCommand/CommandAction`, `prompts.Decision`, `daemon.AgentHqMcp` (mergeOpenCode, isOpenCodeAgentHqTool), `daemon.WatchHooks` (openCodePlugin), `protocol.EventNormalizer`, `session.InfantryEnv`, `executables` | **L**. Not a plugin yet; is the daemon's DEFAULT provider; the serve adapter is the largest adapter |

### 1.3 The other direction: daemon code that names a harness (must move into the plugin or become a contribution)

| file | what it hard-codes | moves to |
|---|---|---|
| `provider/ProviderFactory.kt` | builders `opencode`, `claude`; `DEFAULT = "opencode"`; aliases `oc`, `cc`, `claude-code` | registry; aliases in module.json |
| `daemon/DaemonMain.kt` 1110-1123 | pi launch extras (todo + watch), opencode watch plugin file, claude watch settings dir | each plugin's `start(host)` |
| `daemon/WatchHooks.kt` | `mergeOpenCodePlugin`, `piLaunchArgs`, `harness == "claude"` / `"pi"` branches | per-harness watch contribution (host gives the watched-runner launcher) |
| `daemon/AgentHqMcp.kt` | `mergeOpenCode`, `isOpenCodeAgentHqTool` | opencode plugin; host gives the MCP launch (`Launch`) |
| `agentHqRunner/.../Sandbox.kt` | `opencode`/`pi`/`claude` sandbox argv/env branches and data dirs | `CliHarness.sandbox(spec, dirs)` contribution |
| `agentHqProtocol/.../EventNormalizer.kt` | opencode rejection / tool-end shapes, claude `permission_denials` | each harness's translator emits `HarnessEvent` (denial event) |
| `agentHqProtocol/.../todo/OpenCodeTodoWrite.kt`, `TodoAdapter.kt` | opencode todo shape | opencode translator |
| `stats/SessionStatsService.kt` | `readOpenCode`, Pi `models-store.json`, `CLAUDE` | `HarnessStats` contribution (stats taggers) |
| `usage/Limits.kt` | `ClaudeOAuthUsageSource`, `OpenCodeGoUsageSource` | `UsageSource` contribution |
| `tokens/OpenCodeUsageParser.kt`, `usagemeter/StreamUsage.kt` | opencode usage lines | translator `Usage` event |
| `session/host/OneShotAdapter.kt` 93 | `provider.id == "opencode"` no-reply check | opencode adapter |
| `executables/CliTools.kt` (`KNOWN` = claude, opencode, pi), `executables/HarnessPrefix.kt` (npm bin paths) | CLI resolution and installer layout | module.json `harness.cli` |
| `backlog/WeightExecutors.kt` (w1-3 -> `claude`), `backlog/RoutingRules.kt` (`OPUS_PROVIDER = "pi"`) | routing defaults | project/owner setting; a default only when that harness is installed |
| `agents/AgentProfiles.kt` 40 | `ClaudeCommands.EFFORT_LEVELS` as THE effort set | the resolved harness's `HarnessCommand` values |
| `experiments/pitoolbridge/*` | sets `PiLaunchExtras.args` | pi plugin (or its own experiment plugin) |
| UI `AhqHarnessIcons.kt`, `HarnessInstallSetting.INSTALLABLE` | icons + installable set | module.json `harness.icon`, `harness.cli.install` |
| `ToolProfiles` (runner), `AccessPolicy` env names | not harness-specific | stay core; exposed read-only to plugins |

## 2. Blank test (scratch, not committed)

Scratch change: emptied `src/main/resources/META-INF/services/com.superiorsoftllc.agenthq.harness.HarnessPlugin` (no
claude, no pi plugin) and removed the `opencode` and `claude` builders from `ProviderFactory` (stub kept). `installDist`,
then the daemon class run from `build/install/agenthq/lib` with every `AGENTHQ_*` unset and `AGENTHQ_STATE_DIR` /
`AGENTHQ_HOME` = a temp dir, `AGENTHQ_PORT=8812`, set before launch. Reverted afterwards (`git status` clean).

| # | what | result |
|---|---|---|
| B1 | daemon start, default env | **crash at startup**: `NoSuchElementException: Key opencode is missing in the map` at `ProviderFactory.fromFlag` <- `AgentHqDaemon.build` default param (DaemonMain 600). The daemon cannot start with zero harnesses. |
| B2 | daemon start, `AGENTHQ_PROVIDER=stub` | starts; `/health` ok, `providers:["stub"]`, log `harnesses: <none>`; the 7 official daemon halves load |
| B3 | state dir after start | an `opencode/` folder is written anyway (DaemonMain 1120 installs the opencode watch plugin unconditionally) |
| B4 | `agenthq harnesses` | lists only `stub` as default: the test fake is presented as a real harness; no "install one" hint |
| B5 | `ticket create --weight 3` + role, `ticket start` | refused: `provider 'claude' is not configured on this daemon (configured: stub)` (WeightExecutors routes w1-3 to claude) |
| B6 | `--weight 5`, `ticket start` | refused: `provider 'pi' is not configured` (RoutingRules `OPUS_PROVIDER = "pi"`) |
| B7 | `ticket create --provider claude` | refused at save: `provider 'claude' is not configured`. Good shape, wrong words ("not installed" is the truth) |
| B8 | built-in roles | listed; no harness pinned in a role, so roles are not broken by the absence |

Found by reading (not run in the blank build):
* UI: `HarnessInstallSetting.INSTALLABLE` and `AhqHarnessIcons` name the three; pickers come from the `providers`/`models`
  frames so they would show only `stub` (no empty state).
* Tests that assume the built-ins (would fail under the scratch change; the suite was **not** run with it):
  `HarnessParityTest`, `ClaudeHarnessPluginTest`, `PiHarnessPluginTest`, `PiSessionAdapterTest`, `ClaudeSessionAdapterTest`,
  `HarnessWiringDaemonTest`, `HarnessSpawnWiringTest`, `ClaudeModelsDaemonTest`, `PiModelsDaemonTest`,
  `ModelsHeadOfLineDaemonTest`, `DaemonCoreGuardTest`, plus every test that relies on `ProviderFactory.DEFAULT`.
* Demo seed: not checked and not exercised here.

## 3. Design

### 3.1 The harness API module (what a harness plugin implements and may call)

New leaf Gradle module **`agentHqHarnessApi`** (beside `agentHqRunner` / `agentHqProtocol`, same pattern as CORE-SPLIT-A).
It is the ONLY thing a harness jar compiles against, and the only daemon packages its class loader exposes.

Implements (moved from `agentHq/harness` and `session/host`, unchanged signatures):
* `HarnessPlugin` (`pluginId`, `contractVersion`, `register(registrar)`, `unload()`), `HarnessRegistrar`.
* `Harness` / `CliHarness` (`processSpec`, `newTranslator`, `commands`, `sessionAdapter()`, `conversationCarry`),
  `HarnessRequest`, `HarnessEvent` (+ a `Denial` and `Usage` event so EventNormalizer/usage parsers lose their harness
  branches), `HarnessTranslator`, `HarnessCapability`, `HarnessCommand`/`CommandAction`/`StandardCommands`, `ToolApproval`,
  `ConversationCarry`, `HarnessSessionAdapter`/`AdapterSink`/`HostedProcess`/`TurnEnd`, `HarnessModelCatalog`.
* Optional contributions (default no-op): `sandbox(spec, dirs)`, `stats(sessionId)`, `usageSource()`, `watchHook(...)`.

May call (`HarnessHost`, handed to `HarnessPlugin.register` through the registrar, never a static):
* `stateDir` (its own `plugin-data/<Module>/`), `resolveCli(tool)`, `processRunner`, `settings(key)` (declared keys only),
  `mcpLaunch()` (the agenthq MCP server command for the session), `watch` (the watched-runner launcher, on/off per session),
  `ask(question)` (PromptAsker), `log`.
* Nothing else: no `SessionManager`, stores, `AccessPolicy` or `DaemonMain`. Class loader: a `HarnessApiClassLoader` like
  `DaemonApiClassLoader`, exposing `java.*`, `kotlin.*`, `kotlinx.coroutines/serialization.*`, `...harness.api.*`,
  `...runner.*` (leaf) and `...protocol.ProviderModels`.

Contract version: the existing `HARNESS_CONTRACT_VERSION` exact-match handshake stays; moving the types is a major bump
(see 3.7).

**As built in H3 (HARNESS-PLUGIN-3): bridge, not move.** The plan above moves the daemon's harness types into the API
module. H3 instead adds a NEW, self-contained contract in `agentHqHarnessApi`
(`com.superiorsoftllc.agenthq.harness.api`). It depends only on the Kotlin stdlib. `HarnessApiImportGuardTest` checks that
it imports nothing from the daemon. The daemon's 1.x types stay where they are, and the built-in pi/claude/opencode keep the
1.x path unchanged.

* Surface (2.0.0): `HarnessPlugin` (`pluginId`, `contractVersion` with no default, `register`, `unload`),
  `HarnessRegistrar` (`host`, `register`), and `Harness` (`id`, `capabilities`, `process(request)` -> `HarnessProcess`,
  `newTranslator()`). Also `HarnessRequest`, `ToolApproval`, `HarnessCapability`, `HarnessEvent` and `HarnessTranslator`.
  `HarnessHost` provides `moduleId`, `apiVersion`, `stateDir`, `log`, `setting(key)`, `resolveCli(tool)`,
  `start(process, listener)`, `mcpLaunch(env)`, `watchLauncher(env)` and `ask(sessionId, question)`.
* A 2.x harness is **declarative**. It returns the argv/env and a translator. The daemon launches, sandboxes, gates and
  kills the process, exactly as it does for a built-in (`ApiCliHarness` is a `CliHarness`).
* Bridge: `ApiHarnessPluginBridge` wraps a 2.x plugin as a 1.x `HarnessPlugin`, so the ONE `HarnessRegistry` holds both.
  The registry checks a bridge with `HarnessApiVersion.refusal` (semver: same major, minor not newer than the daemon's).
  Every other plugin keeps the exact-match 1.x check.
* Class loader: `HarnessApiClassLoader` exposes only `kotlin.*`, `kotlinx.serialization.*`, `kotlinx.coroutines.*` and
  `...harness.api.*`, and hides the daemon's `META-INF/services`. The runner and protocol packages are NOT exposed (the
  plan above listed them): process launching goes through `HarnessHost.start`, or through the daemon via `process()`.
* Trade-off: the built-ins do not get the new contract yet, so for now two contracts coexist. In return, H3 has no
  migration risk, and the 2.x surface was designed small instead of inheriting every 1.x member (`sessionAdapter`,
  `commands`, `conversationCarry`, etc.). H6-H8 either port each built-in to 2.x or grow 2.x by minors (members with
  defaults only). No wire change was made: the `providers`, `new` and `models` frames are untouched.

### 3.2 The folder source

A harness ships as a **module** like any other plugin (`plugins/<tier>/<Module>/module.json`), with a `harness` block:

```json
"harness": {
  "jar": "harness/build/libs/Infantry_Pi-harness.jar",
  "contract": "2.0.0",
  "ids": ["pi"],
  "aliases": { "pi-agent": "pi" },
  "cli": { "id": "pi", "command": "pi", "install": { "npm": "@earendil-works/pi-coding-agent", "bin": "dist/bundle/cli.js" } },
  "icon": "icons/pi.svg",
  "settings": ["pi.extraArgs"],
  "hooks": ["watch", "sandbox", "stats"]
}
```

Load path: `ModuleResolver` (same folders, modules.json, enable list, safe mode) -> for each LOADED module with a `harness`
block -> `JarHarnessPluginSource` over that one jar with the `HarnessApiClassLoader` parent -> `HarnessRegistry.discover`.
The plugin's `pluginId` must equal the module name and its registered ids must equal `harness.ids` (refused otherwise, so
the manifest is a truthful index the UI can show without loading code).

Trust, identical to daemon halves (DAEMON-PLUGINS section 2):

| tier | loads when | may |
|---|---|---|
| bundled (install's `modules/`) | always | all declared |
| official | jar passes `OfficialTrust` (signed) or devTrust | all declared |
| community | enabled + grant `harness.code`, plus `harness.hook.<kind>`, `harness.setting:<key>`, `harness.cli` (resolve/launch its own CLI) | only granted; ids must not shadow an official id |

### 3.3 Installs, updates, models, settings, hooks

* **Install**: install = drop the module folder (or the marketplace does it) + enable; the harness CLI install
  (HarnessInstaller, dd7900ed) reads `harness.cli.install` instead of `HarnessPrefix`/`CliTools.KNOWN`.
* **Update**: a new module version replaces the folder; sessions already running keep their adapter object (old class
  loader) until they end; new spawns get the new one (`unload` + `discover` of that module; the registry already supports
  it). Hot reload is a later step; a daemon restart is the guaranteed path.
* **Models**: the harness's `HarnessModelCatalog` (code) or the descriptor `models` section (JSON); the `models` frame
  already merges both.
* **Settings**: declared keys in `harness.settings`, read through `HarnessHost.settings`, edited in the Settings plugin.
* **Hooks**: watch, sandbox, stats, usage become contributions the plugin returns; the core calls them by harness id, so
  `WatchHooks`, `Sandbox`, `SessionStatsService`, `Limits` lose their `when (harness)` branches.

### 3.4 No harness installed

* Daemon **starts**. No default provider is chosen at build time; `ProviderFactory.DEFAULT` goes, the default is the first
  installed harness (or the owner's setting), else **none**.
* `/health` + `ready` carry `harnesses: []`; `agenthq harnesses` prints "No harness installed. Install one: agenthq harness
  install <id>, or drop a harness module into plugins/." The stub is a test provider and is no longer listed unless
  `AGENTHQ_PROVIDER=stub` is set explicitly.
* Spawn / ticket start / ticket save naming an absent id: refused with `harness '<id>' is not installed (installed: ...)`.
  Routing defaults (weights, opus route) that name an absent harness are skipped with that same reason, never a crash.
* UI: harness/model pickers show an empty state with an Install action (the Agents plugin's install surface).
* Nothing harness-specific is written to the state dir unless that harness is loaded (fixes B3).

### 3.5 Migration order and compatibility

Order: **pi** (already a plugin, smallest seams), then **claude** (merge its two layers), then **opencode** (no plugin yet,
the largest adapter, and currently the default). Each moves to `plugins/official/Infantry_<Name>/` and ships in the bundled
root, so a default install behaves exactly as today.

Compatibility: harness ids stay `pi`, `claude`, `opencode` (sessions, roles, tickets, routing rules and stats store ids as
strings: no data migration). Aliases move to the manifest. A stored id whose plugin is absent resolves to "not installed"
(the session can still be viewed; resume is refused with the reason). `AGENTHQ_PROVIDER(S)` keep working as a pick among
installed harnesses.

### 3.6 Ordered steps

| step | what | size |
|---|---|---|
| **H1** | `JarHarnessPluginSource` (+ `folder(dir)`): jars outside the class path, one loader each, only the jar's own services entries, failures attributed per jar. Test plugin packed at test time. **Done in this ticket, not wired.** | S |
| H2 | Zero-harness core: drop `ProviderFactory.DEFAULT`, start with none, "not installed" wording, routing defaults skip absent harnesses, stub hidden, no unconditional `opencode/` write, `harnesses` empty state. Fixes B1-B7. | M |
| H3 | `agentHqHarnessApi` module + `HarnessHost` + `HarnessApiClassLoader`; ~~move the API types~~ a new 2.0.0 contract bridged into the 1.x registry (see 3.1 "As built"). **Done (HARNESS-PLUGIN-3).** | M |
| H4 | module.json `harness` block, ModuleResolver -> `JarHarnessPluginSource`, trust/grants; build registry after module resolution (today `loadHarnesses()` runs at daemon build, before `DaemonPluginHost.load`); example `plugins/examples/Acme_EchoHarness`. **Done (HARNESS-PLUGIN-3)**: `HarnessModules` loads harness modules after module resolution and re-syncs on every re-resolution. Desktop app staging/signing of an official harness half is deferred to H6, the first official harness module. | M |
| H5 | Contributions replacing core branches: watch, sandbox, stats, usage, denial/usage events, CLI + installer from manifest, UI icon/installable from manifest. Split into **H5a-H5f** (section 3.8); **H5a done (HARNESS-PLUGIN-4)**. | L (split per contribution) |
| H6 | pi -> `plugins/official/Infantry_Pi` (incl. `PiLaunchExtras`, todo extension, tool-bridge experiment). | M |
| H7 | claude -> `Infantry_Claude`, one layer (fold `ClaudeCodeProvider` into the plugin). | L |
| H8 | opencode -> `Infantry_OpenCode` as a `HarnessPlugin` (serve adapter, permissions, MCP merge, watch plugin). | L |
| H9 | Guard test: core jar has no harness class and no harness id literal (extend `DaemonCoreGuardTest`). | S |

### 3.8 H5 split into slices (HARNESS-PLUGIN-4)

One slice per contribution, each S or M, each mergeable alone. Rules for every slice:

* **Harness API**: only 2.x **minor** additions: a new member with a default (`= null` / no-op) on `Harness`, or a new
  `HarnessEvent` subtype. A 2.0.0 jar keeps loading on a 2.x daemon (`HarnessApiVersion.refusal` already accepts an older
  minor). Nothing is removed or re-typed. A new `HarnessEvent` subtype is binary compatible because plugins *produce*
  events and never switch over them; a plugin that does `when (event)` exhaustively gets a compile warning on rebuild, not
  a runtime break.
* **Wire**: unchanged unless a slice says so; if it must change, an additive contract minor with a CONTRACT.md entry.
* **Core**: the new path names no harness; it reads the manifest or calls the contribution by harness id. The built-in
  `when (harness)` branches stay until H6-H8 move pi/claude/opencode, and each of those steps deletes the branch it replaces.
* **Trust**: bundled/official get what they declare. A community module needs one grant per declaration (as in 4.1);
  an ungranted declaration refuses the whole module before its jar is opened.

| slice | what | order / why | contract effect | community grants | tests |
|---|---|---|---|---|---|
| **H5a** | CLI + installer from `harness.cli` (+ `install`), UI icon from `harness.icon`, installable set from the daemon's report, `GET /plugins` harness status | 1st: no API change, and every later slice benefits from an installable, visible harness | wire 1.145.0 additive (`GET /plugins` `harness`, `harnessIds`; install report lists module CLIs); API none; module.json `harness.cli.label` added | `harness.cli`, **`harness.cli.install`** (new) | **done**, see 3.8.1 |
| H5b | denial event: `HarnessEvent.Denial(tool, reason)`; check that the existing `Usage` event reaches the usage meter / stats (not verified here) | 2nd: smallest API step, takes the claude `permission_denials` / opencode rejection shapes out of `EventNormalizer` for plugins | API **2.1.0** (new event subtype); wire none (it maps onto the existing denial frame) | none (data about its own run) | a 2.1 translator emitting `Denial` gives the same frame as a built-in denial; a 2.0.0 jar still loads on 2.1; a malformed event becomes an attributed `Error` |
| H5c | usage source: `Harness.usageSource(): HarnessUsageSource? = null` (plan/quota poll) for `Limits` | 3rd: independent of sessions; `Limits` already takes a list of sources | API 2.2.0; wire none (rows in the existing usage frame, tagged by harness id) | `harness.hook.usage` | the module's source shows in the usage frame; ungranted refused; a throwing or slow source is isolated (timeout) and attributed |
| H5d | stats: `Harness.stats(): HarnessStats? = null` (read its own session store into the stats taggers) | 4th: needs H5b's events to be complete | API 2.3.0; wire none | `harness.hook.stats` | `SessionStatsService` reads a module's rows with no harness branch; ungranted refused; a throw is isolated |
| H5e | watch: `Harness.watch(env, launcher): WatchContribution? = null` (extra argv/env, files to write in its own `stateDir`) | 5th: touches launch; `HarnessHost.watchLauncher` (H3) already exists | API 2.4.0; wire none | `harness.hook.watch` (exists) | a watched echo run gets the launcher on its argv; nothing is written outside `plugin-data/<Module>/`; ungranted refused |
| H5f | sandbox: `Harness.sandbox(dirs): SandboxContribution? = null` (extra readable/writable dirs and env names). The daemon still builds and applies the sandbox | last: highest risk, since it widens what the agent process may touch | API 2.5.0; wire none | `harness.hook.sandbox`; a community module may only add dirs under its own `plugin-data/` and the session worktree (the daemon checks every path) | a dir outside the allowed roots is refused, never applied; a sandboxed echo run sees only what it asked for and was allowed; ungranted refused |

H5b-H5f may ship in one API minor if they land together. The table gives each its own minor so that each can merge alone.

#### 3.8.1 H5a as built

* **Manifest**: `harness.cli` = `{id, command?, label?, install?: {npm, bin?}}`. The checks (`ManifestHarnessCli.refusal`, run
  before the jar is opened) are: `id` matches the harness id pattern and is not a built-in CLI (`CliTools.KNOWN`, npm) or
  another loaded module's; `command` is a bare name, never a path; `npm` is an npm package name (it reaches npm as ONE argv
  element, so it can never be a flag such as `--registry=...`); `bin` is relative inside the package (no `..`, `\`, `:`).
* **Resolver**: `CliTool.prefixBin` (new, optional) is where the installer's `npm install -g --prefix` leaves the CLI on
  Windows (`node_modules/<npm>/<bin>`, then `<command>.cmd`); elsewhere `<prefix>/bin/<command>`. Built-in tools leave it
  null and keep the `HarnessPrefix` table, unchanged.
* **Installer**: `HarnessInstaller(fromModules = HarnessModules::installables)` lists the built-ins first (same order),
  then each LOADED module's `cli.install`. A built-in id wins. The list is re-read on every call, so a module loaded or
  unloaded since is seen. The package and the pinned version (`AGENTHQ_<ID>_VERSION`, `-` -> `_`) are re-checked before
  npm runs.
* **Daemon**: `HarnessHost.resolveCli` resolves through the module's manifest tool, so it finds a CLI the installer put
  in the prefix. Settings > CLI locations lists module tools after the built-ins and descriptors. `GET /plugins` modules
  carry `harness` (`HarnessModules.status`) and `harnessIds`.
* **UI**: `HarnessInstallSetting.INSTALLABLE` is gone. A CLI row gets install controls when the daemon's install report
  has its id. `HarnessIcon` draws a loaded module's `harness.icon` (svg/png, inside the module folder) for its ids and
  aliases before the built-in glyph table. Trusted tiers claim first, and a community module cannot repaint an id that
  has a built-in glyph or that another module claimed. Plugins > Modules shows a harness row (icon, ids, and the daemon's
  state when the daemon is 1.145.0 or newer, read from `GET /plugins`). Web/Android modules have no folder, so they keep
  the built-in glyphs (a compiled-in icon is later work).
* **Example**: `plugins/examples/Acme_EchoHarness` declares `cli` (`acme-echo`, npm `@acme/echo-harness-cli`) and
  `icons/echo.svg`. Its run still needs no CLI; the block is the reference for H6's pi module.
* Built-in pi/claude/opencode are unchanged (same table, same order, same ids).

### 3.7 Expensive to reverse

* **The harness API surface (H3)**: once community jars compile against it, every signature change is a contract major and
  breaks them. Keep it small; contributions are optional interfaces, not new abstract members.
* **Harness ids as stored strings**: renaming an id later needs a data migration; keep the three ids forever.
* **One class loader per harness jar**: chosen for isolation; sharing helpers across harnesses then means putting them in
  the API module, deliberately.

Trade-off recorded: a separate harness plugin kind (this design) versus making harnesses ordinary daemon halves
(`DaemonApi`). Daemon halves see only `DaemonApi`, which has no process, stream or session-adapter types; growing it to carry
them would couple every daemon plugin to the harness contract. A distinct `harness` block on the same module, the same
resolver and the same trust rules gives one install story with two narrow APIs.

## 4. Security review (HARNESS-PLUGIN-3, H3 + H4)

### 4.1 What a harness plugin can do

* **Its code runs inside the daemon JVM**, with the daemon's OS rights. `HarnessApiClassLoader` is an API boundary: it
  stops a jar from linking against daemon classes by name. It is **not a sandbox**: reflection, `java.io` and
  `java.lang.Process` are all reachable. The security boundary is the **trust gate in front of loading**. Code that is not
  trusted never runs, because every rule is checked before the jar is opened.
* **Its agent process is launched by the daemon, not by the plugin.** `Harness.process()` only returns argv/env. The
  daemon spawns that process through the same `CliHarness` path as a built-in. The session's policy, sandbox and spawn
  gates (`manager.spawnGate` -> daemon-half `spawnGate` hooks, including **Demo_Lock** seats) and tool approval therefore
  apply unchanged. These gates are role/project level and run before any harness is asked for a command, so a harness
  plugin cannot opt out of them. `HarnessHost.start` (direct launch) is refused unless the module declares `harness.cli`,
  and for community modules also holds that grant.
* **Host services are scoped per module** (`ModuleHarnessHost`). It reads settings it declared (and, for community
  modules, was granted `harness.setting:<key>` for). It resolves only its own declared CLI. `mcpLaunch`, `watchLauncher`
  and `ask` work only when declared in `harness.hooks` (and, for community modules, granted `harness.hook.<kind>`). Its
  `stateDir` is `plugin-data/<Module>/`. Its log lines are prefixed `[harness <Module>]`.
* Trust tiers are the same as for daemon halves: bundled/devTrust load; official needs `OfficialTrust` (signed); community
  needs enabled + `harness.code` + one grant per declared hook, setting and CLI. A community module may not take an id or
  alias that is already installed, and trusted tiers load first. Default deny; a failing gate refuses that module only.
* **Unload**: disabling or removing the module (on the next re-resolution, i.e. a policy/modules change, or on a restart)
  removes its ids from the registry, calls `unload()` and closes its class loader. The next spawn of that id gets
  "harness '<id>' is not installed".

### 4.2 Threats and the tests that pin them

| threat | mitigation | test |
|---|---|---|
| community harness loaded without consent | `harness.code` grant required, checked before the jar is opened | `HarnessModulesTest` "a community harness without the harness code grant is refused and never loaded"; `EchoHarnessModuleDaemonTest` "...without the harness code grant is never loaded" (whole daemon) |
| community harness uses a hook/setting/CLI it was not granted | one grant per declaration; host re-checks each call | `HarnessModulesTest` "...needs a grant for each hook it declares, and its cli"; `ModuleHarnessHostTest` (all 3) |
| community harness shadows an installed id (e.g. registers `claude`) | id/alias collision refused; trusted tiers load first | `HarnessModulesTest` "a community harness may not shadow an installed id" |
| tampered official jar | `OfficialTrust` signature check unless bundled/devTrust | `HarnessModulesTest` "an official jar that is neither bundled nor signed is refused" |
| plugin built for an unknown/newer API | semver refusal with a clear message, before the jar is opened; manifest and code must agree | `HarnessModulesTest` "an unknown contract is refused with a clear message..."; `ApiHarnessPluginTest` "the API version is semver..." |
| manifest lies about what the code registers | registered ids must be `harness.ids`; `pluginId` must be the module name | `HarnessModulesTest` "a plugin registering ids the manifest does not declare..." |
| throwing plugin takes down the daemon or a session | every plugin call is guarded and attributed to the plugin; a translator throw becomes an `Error` event for that run | `ApiHarnessPluginTest` "a throwing plugin is isolated and attributed" |
| plugin links against daemon internals | API module imports nothing from the daemon; the loader exposes only runtimes + API | `HarnessApiImportGuardTest` (all 3); `ApiHarnessPluginTest` "the plugin class loader sees the harness API and nothing else" |
| disabled module keeps running | sync unloads it and closes its loader; a later spawn is "not installed" | `HarnessModulesTest` "disabling the module unloads its harness..."; `EchoHarnessModuleDaemonTest` (module removed) |
| missing / misplaced jar | refused (jar must be inside the module folder), never fatal | `HarnessModulesTest` "a missing jar is refused, never fatal" |
| H5a: community module installs an npm package (runs its install scripts) without consent | `harness.cli` **and** `harness.cli.install` grants; an ungranted module is refused, so its CLI is neither listed nor installable | `ManifestHarnessInstallTest` "an ungranted community cli and install is refused..." |
| H5a: manifest smuggles an npm flag, a path command, a `..` bin, or takes a built-in CLI id (e.g. `git`, `claude`) | `ManifestHarnessCli.refusal` before the jar is opened; npm spec re-checked before npm runs | `ManifestHarnessInstallTest` "a cli block that could reach a flag, a path or a built-in is refused..." |
| H5a: community module repaints a built-in or another module's harness icon | trusted tiers claim first; community may not claim an id with a built-in glyph; icon path must stay inside the module folder | `HarnessManifestUiTest` "pills draw the manifest icon..." |

### 4.3 Residual risks (accepted, for later steps)

* Trusted (bundled/official/granted community) harness code is fully trusted in-process. The class loader does not limit
  what it can do, the same as daemon halves.
* There is no timeout or CPU guard on synchronous plugin calls (`process`, `translate`). A hanging plugin blocks the
  thread that called it (not audited: whether any caller is a shared daemon thread).
* A session already running when its module is unloaded keeps its adapter. Classes it has not loaded yet may then fail;
  that failure surfaces as an attributed `Error` for that run.
* A module enable/disable takes effect on the next module re-resolution (policy or modules change) or on a restart.
  `GET /plugins` and Plugins > Modules now show harness status (H5a).
* H5a: an installed module CLI stays in `harness-tools/` after its module is removed (the same as a built-in CLI). It is
  no longer listed or launched, but it is not deleted.
* H5a: the UI reads icon files from this client's own plugins folder. A remote daemon's modules are not drawn with their
  icons unless the same module is installed locally.
