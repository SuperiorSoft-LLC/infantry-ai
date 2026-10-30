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
| H3 | `agentHqHarnessApi` module + `HarnessHost` + `HarnessApiClassLoader`; move the API types (contract 2.0.0). | M |
| H4 | module.json `harness` block, ModuleResolver -> `JarHarnessPluginSource`, trust/grants; build registry after module resolution (today `loadHarnesses()` runs at daemon build, before `DaemonPluginHost.load`); example `plugins/examples/Acme_EchoHarness`. | M |
| H5 | Contributions replacing core branches: watch, sandbox, stats, usage, denial/usage events, CLI + installer from manifest, UI icon/installable from manifest. | L (split per contribution) |
| H6 | pi -> `plugins/official/Infantry_Pi` (incl. `PiLaunchExtras`, todo extension, tool-bridge experiment). | M |
| H7 | claude -> `Infantry_Claude`, one layer (fold `ClaudeCodeProvider` into the plugin). | L |
| H8 | opencode -> `Infantry_OpenCode` as a `HarnessPlugin` (serve adapter, permissions, MCP merge, watch plugin). | L |
| H9 | Guard test: core jar has no harness class and no harness id literal (extend `DaemonCoreGuardTest`). | S |

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
