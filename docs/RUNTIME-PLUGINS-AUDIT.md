# RUNTIME-PLUGINS AUDIT: what loads at compile time today vs at runtime (goal D5)

Ticket be38a342. This is a read-only audit. No code was changed, nothing was built, and no test was run.
Base: `feature-agenthq-mvp` @ 0b145701.

Goal (D5, owner 2026-10-28): every plugin loads at RUNTIME, and only core is compiled in. Core (D4) = the shell, the
configuration and the plugin orchestration. Everything else is an official plugin that can be removed (D3).

Builds on: PLUGINS-1/2/3 (cfa9af24, 035dba10, 6414ea36), ShellContext (4c8f56c3), SHELL-EMPTY-DROPIN-FEASIBILITY.md
(c6290fde), SHELL-LEAKS.md, NORTH-STAR-DESIGN.md, LICENSE-0 (note `license-0-report-2026-10-26`), and EMPTY-SHELL-DESIGN
rev 4 (d78395b1, branch tip 8ce2b3b1, not merged yet). This audit takes rev 4's tier model and E-steps as given. It
supplies the per-feature facts and the ticket list. Where the tree differs from rev 4, section 5 says so.

## Summary

```
Total features audited: 30
  Already runtime-loaded:   7  (community jars, App_UI.ihq drop-in, NS3 compositions, markdown engines,
                                voice plugins, plugin backends/endpoints, harness JSON descriptors)
  Still compile-time:      23  (all 20 UI modules + 3 daemon groups: harness plugins, provider list, feature services)
  Desktop UI modules runtime-loaded in the SHIPPED app: 0 of 20.
```
- Each of the 20 modules can be switched on or off at runtime (modules.json, Settings > Modules, applied at the next
  start). None has its CODE loaded at runtime. They are compiled into `:agenthqAppUi`, which the desktop app ships on
  its classpath and finds through `ServiceLoader`. Web and Android compile it in.
- The runtime path exists and is proven by a test: `App_UI.ihq` loaded through the drop box (S6, `renderTabShotsForDiff
  -PtabShotsExtra=dropin`). It loads all 20 modules as ONE core-tier bundle. That makes it a single compiled unit
  delivered at runtime, not 20 plugins.
- Six blocker classes (B1-B6): build wiring, modules declaring classes in the shell's own packages,
  the kit not visible to the filtered class loader, `coreController`/`appShell` use, feature code left in the shell,
  and class edges between modules. Details in section 2.

---

## 1. Inventory: every feature, how it loads, its tier

"Compile-time" means the code is linked into a build artifact the app ships (a Gradle `project(...)` dependency or a
`srcDir`). "Runtime" means the code or data is found by scanning a folder on the user's machine.

### 1a. UI modules (plugins/core, all registered in `plugins/core/Infantry_AppUi/CoreModules.kt`)

Build wiring for all of them: `superiorSoftLibrary/agenthqAppUi/build.gradle.kts` adds every `plugins/core/Infantry_*`
folder as a `commonMain` srcDir, except Infantry_Board, which is its own project linked as an `api(project(...))`
dependency. Desktop: `agentHqUi` has `runtimeOnly(agenthqAppUi)`, found by `ServiceLoader<ModuleSource>` →
`CoreModuleSource`. Web: `agentHqWeb/Main.kt` calls `CompiledModules.install(CoreModules.all)`. Android:
`implementation(agenthqAppUi)` + `ServiceLoader`.

`ctl` = lines that use `appController` / `appShell` / `coreController` / `coreShell`. Tier = target tier per D3/D4.

| # | Module | Contributes | Path | LOC | Loaded how | ctl | Tier (target) |
|---|---|---|---|---|---|---|---|
| 1 | Infantry_Agents | left panel `infantry.agents` (manifest only; the list is the `TabContext.agentsList` lambda) | plugins/core/Infantry_Agents | 18 | compile-time (srcDir) | 0 | official |
| 2 | Infantry_Cli | center tab `infantry.cli` (transcript via `TabContext.transcript`) | plugins/core/Infantry_Cli | 29 | compile-time (srcDir) | 0 | official |
| 3 | Infantry_Settings | center host tab `infantry.settings` (groups Profiles/Triggers/Notes/Run/Git) + config field | plugins/core/Infantry_Settings | 68 | compile-time (srcDir) | 2 (`coreShell`, `LocalTabFactory`) | official |
| 4 | Infantry_Epics | center tab; pane is `EpicsPane.kt` (192) in agenthqUiCore | plugins/core/Infantry_Epics | 31 | compile-time (srcDir) | 1 | official |
| 5 | Infantry_Prompts | right tab; pane is `PromptsPane.kt` (163) in agenthqUiCore | plugins/core/Infantry_Prompts | 29 | compile-time (srcDir) | 1 | official |
| 6 | Infantry_Skills | right tab; pane is `SkillsPane.kt` (178) in agenthqUiCore | plugins/core/Infantry_Skills | 29 | compile-time (srcDir) | 1 | official |
| 7 | Infantry_Stats | center tab; pane is `StatsPane.kt` (437) in agenthqUiCore | plugins/core/Infantry_Stats | 32 | compile-time (srcDir) | 1 | official |
| 8 | Infantry_Usage | right tab; pane is `UsagePane.kt` (241) in agenthqUiCore, plus `UsagePacingSection` (269) and `LimitsSection` (73) in the core Config | plugins/core/Infantry_Usage | 30 | compile-time (srcDir) | 1 | official |
| 9 | Infantry_Review | right tab `infantry.review` | plugins/core/Infantry_Review | 85 | compile-time (srcDir) | 1 | official |
| 10 | Infantry_Attention | right tab `infantry.attention` | plugins/core/Infantry_Attention | 144 | compile-time (srcDir) | 1 | official |
| 11 | Infantry_Pods | center tab `infantry.pods` | plugins/core/Infantry_Pods | 148 | compile-time (srcDir) | 1 | official |
| 12 | Infantry_Profiles | center tab `infantry.profiles` | plugins/core/Infantry_Profiles | 248 | compile-time (srcDir) | 1 | official |
| 13 | Infantry_Triggers | center tab `infantry.triggers`; `TriggerDraft`/`TriggerDuration` (123) in agenthqUiCore | plugins/core/Infantry_Triggers | 291 | compile-time (srcDir) | 1 | official |
| 14 | Infantry_Run | center tab `infantry.run` (tab tree `ShellRun` registered by AppUi); `RunPills` (94) in agenthqUiCore | plugins/core/Infantry_Run | 696 | compile-time (srcDir) | 0 | official |
| 15 | Infantry_Notes | center tab `infantry.notes` (tab tree `ShellNotes` registered by AppUi) | plugins/core/Infantry_Notes | 698 | compile-time (srcDir) | 0 | official |
| 16 | Infantry_Git | center tab `infantry.git` (tab tree `ShellGit` registered by AppUi) | plugins/core/Infantry_Git | 862 | compile-time (srcDir) | 0 | official |
| 17 | Infantry_Changes | right tab `infantry.changes` (tab tree `ShellChanges` registered by AppUi) | plugins/core/Infantry_Changes | 1,136 | compile-time (srcDir) | 0 | official |
| 18 | Infantry_Roles | center tab `infantry.roles` + roles window (fill `infantry.shell.roles` registered by AppUi) | plugins/core/Infantry_Roles | 1,160 | compile-time (srcDir) | 1 | official |
| 19 | Infantry_Board | 14 block types `board.*` + overlay (fill `infantry.shell.board` = `ShellBoard`, registered by AppUi) | plugins/core/Infantry_Board (own Gradle project) | 1,771 | compile-time (`api(project)` of agenthqAppUi; merged into App_UI.jar) | 0 | official |
| 20 | Infantry_AppUi | fills all 15 shell slots: header, left, center (chat), right, drawer, footer/status bar, assistant, toast, board, diff, sound, voice, questions (approvals), config.settings, roles | plugins/core/Infantry_AppUi | 5,459 | compile-time (srcDir) | 39 | official (toast, connection items and config host → core; see rev 4 E7) |

### 1b. Already runtime-loaded

| # | Feature | Path / mechanism | Loaded how | Platforms | Tier |
|---|---|---|---|---|---|
| 21 | Community UI modules (examples `Acme_Hello`, `Acme_Echo`) | `<plugins root>/community/<Name>/{module.json, jar}`; `DesktopPlugins.loadJar` through the filtered `PluginClassLoader` | runtime (folder scan) | desktop | community |
| 22 | App UI drop-in bundle `App_UI.ihq` (all 20 modules in one signed jar) | drop box → `IhqInstaller` → `<root>/core/Infantry_AppUi`; `DesktopPlugins.bundles()` (`entry` = `CoreModuleSource`) | runtime, opt-in (proven by the `dropin` shots; NOT the shipped path) | desktop | core (bundle) |
| 23 | NS3 compositions (`fills[].layout` JSON pages, no code) | `DesktopPlugins.composition` | runtime | desktop | any |
| 24 | Markdown engines | `<stateDir>/markdown-engines/*.jar`, `MarkdownEnginePluginLoader` (own filtered loader: `kotlin.*` + `ui.model.*` only) | runtime | desktop | community-style |
| 25 | Voice (TTS) plugins | `VoicePluginStore`: catalog → verified download → folder; runs as an external process | runtime (install on click) | desktop | official/community assets |
| 26 | Plugin backends (`endpoints`, e.g. `plugins/examples/echo-backend`) | `JvmEndpointAccess`, secrets held by the host | runtime (external process/URL) | desktop | community |
| 27 | Harness descriptors (JSON) | daemon `<AGENTHQ_HOME>/harnesses/*.json`, `HarnessDescriptorLoader` (data, not code) | runtime | daemon | official/community |

### 1c. Daemon (superiorSoftLibrary/agentHq): compile-time

| # | Feature | Path | Loaded how | Tier (target) |
|---|---|---|---|---|
| 28 | Harness plugins `ClaudeHarnessPlugin`, `PiHarnessPlugin` | `harness/claude`, `harness/pi`; `META-INF/services/...HarnessPlugin` in the daemon jar | compile-time (ServiceLoader over the daemon's own classpath) | official (the `HarnessRegistry` itself = core orchestration) |
| 29 | Provider list `opencode` / `claude` / `stub` | `provider/ProviderFactory.kt` (hard-coded `builders` map) | compile-time | official (the stub is test-only) |
| 30 | Feature services behind the official UI plugins: tickets/backlog, mailbox, knowledge (memory/decisions/issues), roles, pods/orchestration, triggers, epics, prompts, stats, usage, projectgit, run commands, questions/attention, stories, update | `agentHq/src/main/kotlin/.../{backlog,mailbox,memory,decisions,issues,role,orchestration,triggers,epics,prompts,stats,usage,projectgit,commands,questions,attention,stories,update}` | compile-time; `DaemonPlugins` scans and resolves modules but "nothing is put on its class path" | UNDECIDED: D4 names the UI. Owner question d0e4e3fb asks whether D5 covers the daemon. |

### 1d. What stays core (not counted as features)

`agenthqUiCore` frame (`CoreShell`, 15 empty slots), loader (`DesktopPlugins`, `PluginFolders`, `ModuleResolver`,
`DependencyGraph`, `PluginClassLoader`, `IhqInstaller`, drop box, safe mode), tiers and permissions (`ServiceRegistry`,
`RouteGate`, `EndpointGate`), daemon link (`Controller`, `DaemonLink`, `AppState`, connect/servers/update), theme and
kit, block/layout engine, `ShellContext` + `CoreShellContext`, the trust-kernel Config (Plugins/Modules, Secrets, Pair,
Updates, About, Logs), and the daemon's session runtime, `HarnessRegistry`, access policy and HTTP/MCP. All of these
match D4.

---

## 2. Blockers per compile-time official feature, and the interface that covers it

### 2a. Blockers that apply to all of them

| Blocker | Fact | Covered by | Missing |
|---|---|---|---|
| B1 Build wiring | One Gradle module (`agenthqAppUi`) compiles 19 folders as srcDirs. A hand-written list (`CoreModules.all`) names all 20. `agentHqWeb/Main.kt` imports `CoreModules`. The desktop app has `runtimeOnly(agenthqAppUi)`. | `ModuleSource` + `CompiledModules` (S4); `module.json` `entry` / `jar`; Infantry_Board's own-project pattern | One project per module (`gradle/core-module.gradle.kts`); a generated list for web/Android (rev 4 E1) |
| B2 Shared packages | 12 of 20 modules declare classes in the shell's packages `com.superiorsoftllc.agenthq.ui` / `ui.shell.blocks`, and use same-package kit symbols with no import. Modules also share `internal` members because they compile as one Gradle module (e.g. `ProfileExecutorField`). | none | Each module gets its own package. Shell symbols are reached only through published packages. |
| B3 Kit not exported | The filtered `PluginClassLoader` lets a plugin see only `plugin.api`, `ui.plugin`, `ui.model`, `ui.protocol`, `ui.block`, Compose and Kotlin. `ui.shell` (`ShellContext`, `LocalShellContext`) and the kit in `ui` (Theme, `AhqIcons`, controls) are NOT visible. Today a core jar loads unfiltered, so this is hidden. | `ShellContext` exists (16 domains) | Publish `ui.shell` API + `ui.kit` to the filtered loader (rev 4 E5). **Contract bump: the allowed-package list is API.** |
| B4 Window controller | 11 tabs reach `Controller` through `appController` (`TabContext.coreController`). AppUi has 39 uses of `appShell` / `appController`. | `ShellContext` domains: link, session, git, run, board, roles, toast, config, layout, notes, changes, executor, traits, tickets, window, questions | `ChatContext`, `AgentsContext`, `ProcessesContext`, `UsageContext`, `LibraryContext`, `PodsContext`, `TriggersContext`, `ReviewContext`, `EpicsContext`, `AttentionContext` (rev 4 section 6). **Contract bump (additive) per domain.** |
| B5 Code in the shell | 1,211 lines of Epics/Prompts/Skills/Stats/Usage panes, `UsagePacingSection` + `LimitsSection` (342), `TriggerDraft`/`Duration` (123), `RunPills` (94), and app chrome `TitleBar` (466) + `ProjectPicker` (442), chat state/renderers (~575: `ChatPanelState`, `ChatAttachments`, `ChatBlockRenderers`, `ReasoningPanelUi`, `TodoBox`), `SwitchAgentPicker`, `RestartSession`, `SessionStopActions`, `TicketImages` all sit in `agenthqUiCore`. | a `config.<entry>` contribution for settings sections (PLUGINS-1 generic contributions) | Move the code into its module. The kit keeps only generic helpers. |
| B6 Cross-module class edges | AppUi → Git/Run/Notes/Changes (tab trees), AppUi → Roles (fill), AppUi → Board (`api` + `ShellBoard`), AppUi/Board → `ReportFlagChip`, Attention → AppUi (`WatchFlagChip`, `QuestionHistory`), Roles → Profiles (`ProfileExecutorField`): 9 edges | `AgentHqModule.tabTrees`, slot fills by the owning module | Each module registers its own tree and fill; chips go to the kit |

### 2b. Per feature

| Module | Specific blockers | Interface today | Missing / to add |
|---|---|---|---|
| Agents | B1 only. The list itself is the `agentsList` lambda that AppUi supplies. | `TabContext.agentsList` (ui.plugin, visible) | Nothing to load the jar. The real list stays in AppUi until `AgentsContext`. |
| Cli | B1 only. Transcript is drawn by AppUi through `TabContext.transcript`. | `TabContext.transcript` | Nothing to load the jar (rev 4 first slice) |
| Settings | B1; B4-like: `coreShell?.ui?.subTabs` and `LocalTabFactory` (shell `ui` package) | `SubTabHost` (ui.plugin) | Sub-tab state through `LayoutContext` or `TabContext`. Small additive contract change. |
| Epics, Prompts, Skills, Stats, Usage | B1, B4 (1 each), B5 (pane in shell). Prompts/Skills use `insertIntoChat`. Usage also has a Config section in the shell. | `TabContext.state`, `insertIntoChat`; `config` contributions | `EpicsContext`, `LibraryContext`, `UsageContext` (Stats + Usage); move the panes |
| Review, Pods | B1, B2, B4 (`Controller`) | `TabContext.state` | `ReviewContext`, `PodsContext` |
| Triggers | B1, B2, B4 (`Controller`, `CommandResult`), B5 (`TriggerDraft`/`Duration`) | — | `TriggersContext`; move the draft code |
| Attention | B1, B2, B4, B6 (uses AppUi's `WatchFlagChip`, `QuestionHistory`) | `QuestionsContext` (part of it) | `AttentionContext`; `QuestionHistory` → Attention; chip → kit |
| Profiles | B1, B2, B4, B6 (`ProfileExecutorField` used by Roles) | `ExecutorContext` | profiles domain on `RolesContext`; field → kit |
| Git, Run, Notes, Changes | B1, B2, B3, B6 (tab tree registered by AppUi). No `ctl`: they already use `ShellContext` (`git`, `run`, `notes`, `changes`). | `GitContext`, `RunContext`, `NotesContext`, `ChangesContext`, `LinkContext`, `SessionContext` | Only publish (B3) + self-registration (B6). They are the most ready of the large modules. |
| Roles | B1, B2, B3, B6 (fill registered by AppUi; Profiles edge), 1 `ctl` | `RolesContext`, `TraitsContext`, `NotesContext`, `ExecutorContext` | Fill its own slot; replace the last `ctl` |
| Board | Own project already. B3, B6 (`api` edge from AppUi; `ShellBoard` overlay in AppUi; `BoardDeps(state, controller)` constructor called from AppUi) | `BoardContext`, `TicketsContext`, `RolesContext`, `ExecutorContext`, `LayoutContext` | Fill `infantry.shell.board` itself; drop the `api` edge |
| AppUi | Everything: B1-B6, 39 `ctl`, 114 shell imports, 15 slot fills, 2,000+ lines of its code in the shell (B5) | partial: `ToastContext`, `QuestionsContext`, `ConfigContext`, `BoardContext` | Split it (rev 4 E7): toast/connection/config host → core; Chat, Approvals, Header, Assistant, Sound+Voice → own modules; `ChatContext`, `ProcessesContext`. **Approvals: the daemon must stay the enforcement point** (rev 4 C5). |
| Daemon harness plugins | Discovered with `ServiceLoaderPluginSource` over the daemon classloader. `HarnessPluginSource` is already replaceable. | `HarnessPlugin` / `HarnessRegistrar` contract (versioned, exact-match) | A folder source (`harnesses/*.jar` with its own loader). **Daemon change.** |
| Daemon provider list | Hard-coded map in `ProviderFactory`; `DEFAULT = "opencode"` | none | A registry, or fold providers into the harness registry. **Daemon change.** |
| Daemon feature services | All in the daemon jar. `DaemonPlugins` resolves modules but loads no code. | `GET /plugins` view only | A daemon module contract (routes, storage, MCP tools). **Daemon change + new contract. Owner decision pending (d0e4e3fb).** |

---

## 3. Proposed extraction order (smallest and safest first)

Each line is a ticket title with its weight. **[C]** = contract bump (plugin API / allowed packages / `ShellContext`).
**[D]** = daemon change. Every UI step keeps the 32 default shots + `empty` + `dropin` at 0 px (rev 4 rule).
SETTINGS-TAB-1 (ef796432) is merged at 0b145701, so rev 4's "after ef796432" gate on the edge-cutting step is met.

| # | Ticket title | W | Flags |
|---|---|---|---|
| 1 | BUILD: generate the module list from plugins/core/*/module.json; one ModuleSource `entry` per module; delete CoreModules.kt | 2 | |
| 2 | LOADER: bundled modules root + plugins/official folder + Infantry-key signature check + devTrust property | 3 | [C] additive (`Tier.OFFICIAL`) |
| 3 | EXTRACT Infantry_Cli: own project, signed official jar, runtime-loaded on desktop; measure plugin load (budget 500 ms) | 3 | |
| 4 | EXTRACT Infantry_Agents: own project, official jar | 1 | |
| 5 | EXTRACT Infantry_Settings: sub-tab state through the plugin API, drop `coreShell`; own jar | 2 | [C] additive |
| 6 | API: publish ui.shell (ShellContext) + ui.kit to the filtered loader; move module classes out of shell packages | 3 | [C] |
| 7 | EXTRACT Git, Run, Notes, Changes: self-register tab trees, own packages, official jars (one ticket each) | 2 each | |
| 8 | EXTRACT Infantry_Board: fill infantry.shell.board itself, ReportFlagChip → kit, drop the api edge from AppUi | 3 | |
| 9 | MOVE panes Epics/Prompts/Skills/Stats/Usage + Usage config sections out of agenthqUiCore into their modules | 3 | |
| 10 | API: LibraryContext; Prompts + Skills off coreController → official jars | 2 | [C] |
| 11 | API: UsageContext; Stats + Usage off coreController → official jars | 2 | [C] |
| 12 | API: EpicsContext; Epics off coreController → official jar | 2 | [C] |
| 13 | API: ReviewContext + PodsContext; Review + Pods → official jars | 3 | [C] |
| 14 | API: TriggersContext; move TriggerDraft into Triggers → official jar | 3 | [C] |
| 15 | EXTRACT Profiles: ProfileExecutorField → kit, profiles domain on RolesContext → official jar | 2 | [C] |
| 16 | EXTRACT Roles: fill infantry.shell.roles itself, last coreController use → official jar | 3 | |
| 17 | EXTRACT Attention: QuestionHistory in, WatchFlagChip → kit, AttentionContext → official jar | 3 | [C] |
| 18 | SPLIT AppUi part 1: toast, connection items, config host → shell; TitleBar/ProjectPicker → Header module | 4 | |
| 19 | SPLIT AppUi part 2: Chat + Approvals modules on ChatContext/ProcessesContext; daemon stays the approval gate | 5 | [C] [D] (verify enforcement only) |
| 20 | SPLIT AppUi part 3: Assistant, Sound+Voice, diff modules; agenthqAppUi dissolves; App_UI.ihq → NS3 composition of deps | 4 | |
| 21 | CLEANUP: delete coreController/coreShell (ratchet at 0); core vs official differ only in defaults | 2 | [C] breaking (`apiVersion` 2) |
| 22 | DAEMON: harness plugins from a harnesses/ jar folder (own loader); move claude/pi out of the daemon jar | 3 | [D] [C] (harness contract unchanged; loading rule new) |
| 23 | DAEMON: ProviderFactory map → registry (or merge into HarnessRegistry) | 3 | [D] |
| 24 | DESIGN: daemon feature services as plugin back ends (routes, storage, MCP tools) — only if the owner says yes | 4 | [D] [C]; blocked on owner question d0e4e3fb |

Steps 1-3 match rev 4 E1+E2. Steps 6-21 regroup rev 4 E4-E8 by feature, so each ticket is one module and can be
sized and routed on its own. Steps 4-5 are new: Agents and Settings are as easy as Cli. Settings did not exist when
rev 4 was based (758149a5).

---

## 4. Platforms: what is true today

- **Desktop (JVM):** runtime loading works for community jars, the App UI bundle (`.ihq` drop-in, with signing,
  consent, soft restart and unload in S7), compositions, markdown engines and voice plugins. The shipped app still
  takes its 20 modules from its own classpath. With no modules, the empty shell (drop box + Configuration) is proven
  by `DropInUiTest` and the `empty-shell` baselines.
- **Web (wasm):** cannot load code at runtime. `Main.kt` compiles `agenthqAppUi` in and calls
  `CompiledModules.install(CoreModules.all)`. Discovery is a no-op (`CompiledModules.wasmJs.kt`). There is no folder
  scan, so community modules, compositions and markdown/voice plugins do not exist on web today. The web bundle is
  served by the daemon (`WebBundle.kt`).
- **Android:** `implementation(agenthqAppUi)` + `ServiceLoader` over the APK. There is no folder scan or drop-in. The
  same compiled set as web.
- **One contract today:** yes for the module model. `AgentHqModule` / `ModuleManifest` / `TabContext` / `ShellContext`
  are `commonMain` and the same on all three. `module.json` `platforms` (PLUGIN-PLATFORMS-1) lets a module opt out of
  a platform. What differs is only how the list is obtained, and that is the D5 shape: desktop loads at runtime, web
  and Android bundle. After step 1 the bundled list is generated from the same module.json files, so nobody keeps it
  by hand.
- Not true yet: a product manifest (`product.json`) that picks which official modules a web/Android build bundles
  (rev 4 section 5). Compositions on web/Android, which need no code loading, are also not done.

## 5. Facts that differ from EMPTY-SHELL-DESIGN rev 4

- The tree has **20** modules, not 19: Infantry_Settings (SETTINGS-TAB-1, ef796432) was added after rev 4's base.
- **Infantry_Agents and Infantry_Settings** are as small as Cli (18 and 68 lines). Agents has no edges and no `ctl`;
  Settings has 2 `coreShell` uses. They could join the first slice.
- **B2 (shell packages)** and **B3 (kit not exported)** are hidden today because core loads unfiltered. Under rev 4's
  rule that official loads through the filtered loader, they block every module except Agents and Cli. Settings, Epics, Prompts, Skills, Stats and
  Usage keep their own files in `plugins.core`, but they call into the shell's `ui` package (`coreShell`,
  `LocalTabFactory`, the `*Pane` composables). So rev 4's E5 must come before E6, as rev 4 orders it.
- The daemon has compile-time plugins (`HarnessPlugin` via ServiceLoader, the `ProviderFactory` map) that rev 4 does
  not cover.

---

## AppUi remaining (RUNTIME-16, ticket 76227a76)

State after RUNTIME-6..15 and this ticket: Infantry_AppUi is the only module left in plugins/core. No official plugin
reaches the window's `Controller` any more (0 main-code uses in plugins/official; `TabContext.coreController` had 0
users and is deleted here). What still reaches it is Infantry_AppUi alone, through `appShell` (= `coreShell`): 31 uses
in 12 files. Decision per piece: **shell** = stays in core (config/orchestration), **plugin** = moves to an existing
one, **new** = becomes a new official plugin. "Done" = moved in this ticket.

| Piece (files in plugins/core/Infantry_AppUi) | LOC | Fills / role | Reaches Controller? | Decision | Status |
|---|---|---|---|---|---|
| `AppController.kt` (`TabContext.appController`) + `TabContext.coreController` | 11 | tab access to the window controller | was the path | delete (0 users) | **done** |
| `ShellSound.kt`, `ShellVoice.kt` | 32 | `infantry.shell.sound`, `infantry.shell.voice` | via appShell | **new** Infantry_Alerts (on `ShellContext.alerts`, additive) | **done** |
| Sidebar "To review" counter (`Sidebar.kt`) | 1 | reads `AppState.reviewQueue` | no | stays in the agents list, shown only when Infantry_Review's tab is loaded | **done** |
| `ShellToast.kt`, `ToastHost.kt` | 166 | `infantry.shell.toast` (already on `ShellContext.toast` only) | no | **shell** (rev 4 E7: core draws its own toasts) | done, RUNTIME-17: in agenthqUiCore, `ShellToast` drawn by `CoreShell` (no slot) |
| `StatusBarBlocks.kt` (connection dot, last frame, config cog), `ProcessesBadge.kt` | 321 | `infantry.shell.footer` | via appShell | connection items + cog: **shell**; RAM/rogue badge: **new** Infantry_Processes (`ProcessesContext`) | done, RUNTIME-18: `CoreStatusBar` in agenthqUiCore, drawn by `CoreShell` (no slot); `infantry.shell.footer.processesBadge` is a slot filled by official Infantry_Processes on the additive `ShellContext.processes` |
| `ShellConfigSettings.kt` (16 config sections) | 47 | `infantry.shell.config.settings` | no (sections are in core) | host: **shell**; each section to the plugin that owns it (Limits→Usage, Roles→Roles, Voice/Sound→Alerts, Questions→Attention) | follow-up F3 |
| `ShellHeader.kt` (+ core `TitleBar`, `ProjectPicker`) | 211 (+908 in core) | `infantry.shell.header` | via appShell (10) | **new** Infantry_Header | done, RUNTIME-20: official jar `plugins/official/Infantry_Header` fills `infantry.shell.header` on the additive `ShellContext.header` (`HeaderContext`, `NoHeader` default); `WindowChrome`, `TITLE_BAR_HEIGHT` and `AssistantEntry` stay in core |
| `ShellLeftPanel.kt`, `Sidebar.kt`, `SidebarBoardButton.kt`, `ProjectsSidebar.kt`, `ShellCompactDrawer.kt` | 589 | `infantry.shell.left`, `infantry.shell.drawer`; the agents list behind `TabContext.agentsList` | via appShell | panel frames: **shell**; the agents list → **plugin** Infantry_Agents (needs `AgentsContext`) | done, RUNTIME-21: frames stay in the shell; the list, Board button and ProjectsSidebar are official Infantry_Agents on the additive `ShellContext.agents` (`AgentsContext`, `NoAgents`); `TabContext.agentsList` deleted; `TicketTag`, `NeedsYouBadge`, New-agent consts moved to agenthqUiCore. Leftover: chat header ticket tag opens the board via `ui.showBoard` (ShellCenter, F6) |
| `ShellRightPanel.kt` | 140 | `infantry.shell.right` | via appShell (5) | **shell** (a panel frame + strips, like the left) | done, RUNTIME-21: frames stay in the shell; the list, Board button and ProjectsSidebar are official Infantry_Agents on the additive `ShellContext.agents` (`AgentsContext`, `NoAgents`); `TabContext.agentsList` deleted; `TicketTag`, `NeedsYouBadge`, New-agent consts moved to agenthqUiCore. Leftover: chat header ticket tag opens the board via `ui.showBoard` (ShellCenter, F6) |
| `ShellCenter.kt`, `ChatPanel.kt`, `ChatSurface.kt`, `ChatAttachmentViews.kt`, `ChangeBox.kt`, `CommsCardStyle.kt`, `OriginRenderer.kt`, `PromptCard.kt`, `SessionStartPane.kt`, `NewAgentDialog.kt` | 2,641 | `infantry.shell.center` (chat + center strip + New agent) | via appShell | centre frame + strip: **shell**; chat: **new** Infantry_Chat (`ChatContext`); New agent dialog → Infantry_Chat | follow-up F6 |
| `ShellQuestionPill.kt`, `QuestionWindow.kt` | 324 | `infantry.shell.questions` (approvals / owner questions) | via `QuestionsContext` + appShell | **new** Infantry_Approvals; the daemon stays the enforcement point (rev 4 C5) | follow-up F7 |
| `ShellAssistant.kt`, `AssistantPopup.kt` | 417 | `infantry.shell.assistant` | via appShell | **new** Infantry_Assistant (on Infantry_Chat's panel) | follow-up F8 |
| `ShellDiff.kt`, `DiffModal.kt` | 326 | `infantry.shell.diff` | via appShell (`openDiff(state, controller)`) | **plugin** Infantry_Changes (it opens the diffs) | follow-up F9 |
| `ShellAccess.kt` (`appShell`) + core `coreShell` | 13 | the last controller path | — | delete when the 31 uses are 0, then `apiVersion` 2 | follow-up F10 |

Follow-ups (weight 1-5): F1 toast → shell (1); F2 status bar: connection/cog → shell, Infantry_Processes on
`ProcessesContext` (3); F3 config sections to their owners via `config.<entry>` contributions (3); F4 Infantry_Header
(TitleBar + ProjectPicker out of agenthqUiCore) (3); F5 panel frames → shell, agents list → Infantry_Agents on
`AgentsContext` (3); F6 Infantry_Chat on `ChatContext` (5); F7 Infantry_Approvals (4); F8 Infantry_Assistant (2,
after F6); F9 diff modal → Infantry_Changes (2); F10 delete `coreShell`/`appShell`, `apiVersion` 2, retire
Infantry_AppUi and App_UI.ihq (2, after F1-F9).
