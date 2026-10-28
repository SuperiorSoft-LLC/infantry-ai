# EMPTY SHELL: the app is an OS, plugins are programs

**Revision 4** (ticket d78395b1, 2026-10-28). Revisions 1-3: ticket 73b920ff (rev 3 = c6f23fa5).
This note is design only. No code was changed, nothing was built, and no test was run.
Base: `feature-agenthq-mvp` @ 758149a5, after 4dc8b33f.

**Decisions applied in rev 4:**
- **D3** (owner, 2026-10-28): "official" = plugins made by the app's own developer/company. They are NOT required and
  can be removed. Every "official (REQUIRED)" wording of rev 3 is gone.
- **D4** (owner, 2026-10-28): the app is an OS, plugins are programs. Core = the shell, the configuration and the
  plugin orchestration only. Everything else today (Roles, Attention, board, chat, PM tools) is an official plugin.
  So Roles and Attention move to official (rev 3 left them "owner to decide"), and so do Chat, Agents, Cli and
  Approvals (rev 3 had them core).
- **Trust** (owner, via PM, with D4): official plugins do NOT get core-level trust; they are removable programs. The
  `coreController` ratchet measures the code still in core that belongs in a plugin; it goes to 0 as that code moves
  out.
- Kept from rev 3: three tiers `plugins/{core,official,community}`, runtime-loaded on desktop, one module contract for
  every platform.

It builds on SHELL-EMPTY-DROPIN-FEASIBILITY.md (S0-S7), SHELL-LEAKS.md (S4a-S4d), NORTH-STAR-DESIGN.md (NS1-NS10),
BLOCKS-PERF-CHECK.md, plugins/GUIDE.md, LICENSE-0 (memory note `license-0-report-2026-10-26`) and LICENSE-1 (0fc727a6).

Related board work:
- SETTINGS-TAB-1 (ef796432) edits the Notes, Git, Triggers, Profiles and Run registrations. It must merge before the
  edge-cutting step E6.
- NS6b-NS9 (scoped grants) are daemon-side and do not overlap with this note.
- df416b6f (a competing rev) is stale and not used.

Principle (owner): a module that has to be compiled in is not a module. The OS is the shell; every feature is a
program loaded through the module system.

## Summary for the owner (one page)

```
 1. CORE = the OS: the shell, the configuration (Settings, trust kernel) and the plugin orchestration (loader,
    resolver, tiers, permissions, services, daemon link). Nothing else. No feature is core (D4).
 2. OFFICIAL = programs made by Infantry (D3). All 19 of today's modules are official by that test: chat, agents,
    approvals, board, roles, attention, notes, git, run, ... They ship enabled in the default install, and any of them,
    or all of them, can be disabled, removed or replaced. None is required.
 3. COMMUNITY = programs made by anyone else.
 4. Official plugins do NOT get core-level trust. Official and community load through the SAME filtered class loader
    and the SAME plugin API. Official differs only in defaults: it is signed by the Infantry key, it ships in the
    install, it is enabled by default, and its declared services are granted without an `allow` entry.
 5. The shell already starts with ZERO modules and shows a clear empty state: the drop box plus Configuration.
    Evidence: DropInUiTest, the shell-empty-1400 baselines, runShell, FreshShell. With every official plugin removed
    the app starts to that state; agents still run through the CLI and MCP.
 6. Three folders, three tiers: plugins/core, plugins/official, plugins/community; the same three in the install and in
    the user root. Tier = folder + signature + the tier claim inside the signed module.json. No compiled-in list.
 7. Today all 19 modules sit in plugins/core with core rights (coreController, slot fills, unfiltered loader). That is
    the debt. A RATCHET test counts it (program modules left in plugins/core, appController/appShell uses) and only
    lets it go down. A module moves to plugins/official when it needs no core right. At 0, coreController is deleted
    and plugins/core holds only orchestration pieces, possibly none.
 8. Removal is not safe yet for 8 modules: 9 class links between modules (AppUi -> Git, Run, Notes, Changes, Roles,
    Board; Attention -> AppUi; Roles -> Profiles). Cutting them is step E6.
 9. First slice: Infantry_Cli as a real signed OFFICIAL jar loaded from modules/official (weight 3). It is the smallest
    module with no class links and no controller use, and it proves the official path end to end.
10. Risks: start-up (plugin load already went from 226 to 436 ms; budget <= 500 ms, measured in E2). Replaceable
    approvals UI (section 3d): the daemon, not the UI, must stay the enforcement point.
```

**Rev 3 conflicts, as D3/D4 settle them:**
- **C1** ("core = what makes it tick" vs "remove all core and it still works"): settled. Core has no feature, so
  removing every plugin leaves the OS running to the empty state. Running agents is a program's job (official Chat and
  Agents), and the CLI/MCP keep working without any UI plugin.
- **C2** (core minimal in count, not in size): settled. The 3.3k lines of chat/sessions/approvals are official.
- **C3** (first slice core vs smallest): settled. The first slice is official (Cli). Caveat kept: Cli's transcript is
  still drawn by AppUi through `TabContext.transcript` until the AppUi split (E7).
- **C4** (official "exactly like third-party" vs today's code): still a fact, now the ratchet's target. 11 modules
  (Attention, Epics, Pods, Profiles, Prompts, Review, Roles, Skills, Stats, Triggers, Usage) reach the window's
  `Controller` through `coreController`, and AppUi uses `appShell`. They stay in plugins/core, counted by the ratchet,
  until their uses reach 0.
- **C5** (a replaced approvals UI could auto-approve): open risk, handled in 3d and section 9.

---

## 1. The shell, and does it start with zero modules?

**Yes, today.** `CompiledModules.all` is empty when no `ModuleSource` is on the classpath. `CoreShell.slots` then draw
nothing, the centre slot draws the drop box, and Configuration (the trust kernel) opens. Proof already in the repo:
- `agentHqUi/.../DropInUiTest` "the empty shell draws the drop box, the app UI does not"
- baselines `agentHqUi/tab-shots-baseline/empty-shell/shell-empty-1400.png` and `shell-empty-config-1400.png`
- `:agentHqUi:runShell` (NS4) and "Try in a fresh shell" (NS10)

I did not re-run them: none is a one-command check without a full Compose build. The command is
`gradlew.bat :superiorSoftLibrary:agentHqUi:test --tests "*DropInUiTest*"`.

**The empty state is a contract:** a window with the drop box in `infantry.shell.center`, the Configuration button
(top right), connect/servers when no daemon answers, and (NS4 2b) a daemon status line. The `shell-empty` shots define
it.

**What the shell (= core, D4) is: not a module, not removable.**

| Shell piece | D4 part | Where |
|---|---|---|
| Frame: `CoreShell` skeleton, locked nodes, 15 empty slots, window layer, window title-bar frame | shell | agenthqUiCore `ui/shell/blocks` |
| Loader: `DesktopPlugins`, `PluginFolders`, `ModuleResolver`, `DependencyGraph`, `PluginClassLoader`, `IhqInstaller`, drop box, consent, soft restart, safe mode | orchestration | agenthqUiCore `ui/plugin`, agenthqPlugins `plugin/host` |
| Trust: `Tier`, `ServiceRegistry`, `RouteGate`, signer pinning, demo lock | orchestration | agenthqPlugins |
| Platform services: events, storage, settings, notifications (toast host), daemon | orchestration | agenthqPlugins, agenthqUiCore |
| Daemon link: `Controller`, `DaemonLink`, `WireProtocol`, `Model`/`AppState`; connect, servers, switchError, serverUpdate, connection items of the status bar | orchestration | agenthqUiCore `ui/net`, `ui/protocol`, `ui/model` |
| Configuration: Plugins/Modules, Secrets, Pair, Updates, About, Logs, and the host for each module's `config.<entry>` | configuration | agenthqUiCore `ConfigDialog` |
| Theme, kit widgets, icons, markdown engine, layout engine, `ShellContext` | shell (the platform API) | agenthqUiCore `ui/` root, `ui/block`, `ui/shell` |
| Bundled daemon (in-process in the installed app; a thin flavour without it is NS-L1) | orchestration host | `:agentHq` |

**Not shell but still in it (moves out, E4):** `EpicsPane`, `PromptsPane`, `SkillsPane`, `StatsPane`, `UsagePane`
(1,211 lines) and `UsagePacingSection`. `ProjectPicker` (442) moves to Agents; the non-frame part of `TitleBar` (466)
moves with the header.

**Moves INTO the shell** (D4: platform services, not features): the toast host (`ToastHost`, `ShellToast`;
`Services.NOTIFICATIONS` already lands there), the status-bar connection items, `ShellConfigSettings` (the config host).
Today they are in Infantry_AppUi.

## 2. Classification of the 19 modules

Test (D4): **is it the shell, the configuration or the plugin orchestration?** If not, it is a program: OFFICIAL when
Infantry makes it (D3), COMMUNITY otherwise. By that test every module today is official. "ctl" = it uses
`appController`/`appShell` today, so it still needs core rights and stays in plugins/core until that reaches 0.

| Module | Lines | Class | Can move to plugins/official when |
|---|---|---|---|
| Infantry_Cli | 29 | OFFICIAL | now (first slice) |
| Infantry_Agents | 18 | OFFICIAL | now; it gets real code in E7 |
| Infantry_AppUi | 5,456, ctl | SPLIT | split in E7: shell parts into the shell, the rest into official modules |
| Infantry_Attention | 144, ctl | OFFICIAL (D4; was "owner to decide") | its 4a edges are cut (E6) and its ctl uses reach 0 |
| Infantry_Roles | 1,160, ctl | OFFICIAL (D4; was "owner to decide") | its edges are cut (E6) and its ctl uses reach 0 |
| Infantry_Profiles | 247, ctl | OFFICIAL | ctl uses reach 0 |
| Infantry_Board | 1,771 | OFFICIAL | AppUi's link to it is cut (E6) |
| Infantry_Notes | 697 | OFFICIAL | E6 |
| Infantry_Git | 861 | OFFICIAL | E6 |
| Infantry_Run | 695 | OFFICIAL | E6 |
| Infantry_Changes | 1,136 | OFFICIAL | E6 |
| Infantry_Epics | 31 (+192 in core), ctl | OFFICIAL | pane moved (E4), ctl uses reach 0 |
| Infantry_Stats | 32 (+437 in core), ctl | OFFICIAL | same |
| Infantry_Usage | 30 (+241 in core), ctl | OFFICIAL | same |
| Infantry_Prompts | 29 (+163 in core), ctl | OFFICIAL | same |
| Infantry_Skills | 29 (+178 in core), ctl | OFFICIAL | same |
| Infantry_Triggers | 290, ctl | OFFICIAL | ctl uses reach 0 |
| Infantry_Review | 85, ctl | OFFICIAL | same |
| Infantry_Pods | 148, ctl | OFFICIAL | same |

**Infantry_AppUi split (step E7):**

| Part | Files (lines) | Goes to |
|---|---|---|
| Chat, composer, attachments | `ShellCenter` 240, `ChatPanel` 364, `ChatSurface` 1,049, `ChatAttachmentViews` 145, `SessionStartPane` 92, `OriginRenderer` 47, `CommsCardStyle` 46 | OFFICIAL: new `Infantry_Chat` (Cli folded in or kept as its tab) |
| Sessions list, new agent, project picker | `ShellLeftPanel` 72, `ShellCompactDrawer` 59, `Sidebar` 372, `ProjectsSidebar` 30, `NewAgentDialog` 323, `ProjectPicker` 442 | OFFICIAL: `Infantry_Agents` |
| Approvals / questions | `ShellQuestionPill` 53, `QuestionWindow` 353 (minus `QuestionHistory`, which goes to Attention) | OFFICIAL: `Infantry_Approvals` (see 3d on replacement) |
| Header | `ShellHeader` 211, `TitleBar` 466 minus the window frame | OFFICIAL: with Agents *unsure* (or its own small module) |
| Status bar | `StatusBarBlocks` 171, `ProcessesBadge` 150 | connection items → SHELL; processes badge → Run |
| Toast | `ShellToast` 36, `ToastHost` 130 | SHELL (notifications service) |
| Board overlay frame | `ShellBoard` 48, `SidebarBoardButton` 58 | Board |
| Diff modal, change box | `ShellDiff` 66, `DiffModal` 260, `ChangeBox` 150 | Changes |
| Assistant | `ShellAssistant` 95, `AssistantPopup` 322 | OFFICIAL: new `Infantry_Assistant` |
| Sound, voice | `ShellSound` 15, `ShellVoice` 17 | OFFICIAL: one small module |
| Roles window fill, prompt card | `ShellRoles` fill registration, `PromptCard` 185 | Roles, Prompts |
| Config settings host | `ShellConfigSettings` 45 | SHELL: Config hosts every module's `config.<entry>` |

Result: **core = the shell, nothing else.** Every feature ships as an official plugin. Nothing needs compile-time
membership.

## 3. Three tiers, three folders

There are three source folders and three install locations, one per tier. A module's tier comes from its folder plus
its signature. No list compiled into the shell names a module. A tier is a trust level, not a "required" flag: no tier
makes a module required (D3).

### 3a. Folders

| Tier | Repo (source) | Install (bundled, written by the MSI) | User root (drop box / manual) |
|---|---|---|---|
| core: orchestration pieces packaged as modules; today also the 19 program modules not yet moved (the ratchet's debt) | `plugins/core/<Name>` | `<install>\app\resources\modules\core\<Name>\` | `%USERPROFILE%\.agenthq\plugins\core\<Name>\` |
| official: programs made by Infantry (D3), ship enabled, removable | `plugins/official/<Name>` | `<install>\app\resources\modules\official\<Name>\` | `%USERPROFILE%\.agenthq\plugins\official\<Name>\` |
| community: programs made by anyone else | `plugins/community/<Name>` | (none: the MSI never ships community) | `%USERPROFILE%\.agenthq\plugins\community\<Name>\` |

- Each module folder holds `module.json` and `lib/<Name>.jar`. The jar is jarsigned, and the `module.json` that counts
  is the copy inside the signature.
- **The loader fixes the three tier folders.**
  - `ModulesConfig.DEFAULT_FOLDERS` gains `official`.
  - The scanner always reads the three standard folders, even when an older `modules.json` lists only `core` and
    `community`.
  - The `folders` key may only *add* extra folders. Without this rule, an existing user's `modules.json` would hide
    every official plugin after the upgrade.
- **Bundled vs user root:** when both hold the same module name, the user copy wins only if it has the same signer and
  a higher version. That is the upgrade path. A user copy with the same name and a different signer is a
  *replacement* (3d).
- **End state of plugins/core:** only modules that are orchestration (D4), for example a platform service shipped as a
  module. It may well be empty: the shell binary already holds the orchestration. A program never belongs there.

### 3b. How the loader tells the tiers apart (no compile-time membership)

A module gets tier T only when **all three** of these agree. If they do not, the module is **refused**, never demoted,
and Settings > Modules shows the reason.

1. **Folder:** the module sits in T's folder, in the bundled root or the user root.
2. **Signature:** every jar entry and `module.json` verify against a key trusted for T. The check is
   `JarFile(verify=true)`, as `IhqInstaller` does today. Trusted keys per tier:
   - **core:** the Infantry key, pinned in the shell binary (agenthqPlugins). Nothing else: core is the OS.
   - **official:** the Infantry key (D3: official = made by the app's own developer).
   - **community:** any signer, or unsigned when `allowUnsigned` is set. A key the owner adds to `trusted-keys/` makes
     its modules community-signed-and-trusted (installs without the unsigned warning); it never lifts them to official
     or core.
3. **Signed claim:** the `"tier"` field of the `module.json` *inside the signature* equals T. This stops an
   Infantry-signed official jar from being copied into `core\` to gain core rights.

**A manifest hash is not a trust source.**
- A hash list compiled into the shell is a membership list again.
- A hash file next to the jars sits in the same user-writable folder, so it proves nothing.

A hash is used only as a **cache key**: (path, size, mtime, SHA-256) → "verified for tier T", so the loader can skip
re-verification at start. A miss or a mismatch only means "verify again". A cache entry never grants anything.

Dev runs from the repo root skip step 2 with `agenthq.plugins.devTrust=true`. The packaged launcher never sets it, and
a test checks that.

**Code change this implies:** `Tier` gains `OFFICIAL`. Today several checks are written as `tier != COMMUNITY` (the
TabFactory interceptors and injections, `PluginClassLoader filtered`). They must name each tier explicitly, as 3c says,
so that official never silently gets core rights.

### 3c. Trust: what each tier is allowed

Rule (owner, with D4): **official plugins do not get core-level trust.** Official is a program like community; it
differs only in defaults (signer, shipped, enabled, services granted by declaration). Core rights exist only for the OS
and, transitionally, for the program modules not yet moved out of plugins/core. The ratchet (3g) drives those to 0.

| Permission | core (OS; plus the not-yet-moved programs) | official | community |
|---|---|---|---|
| Ships in the install, enabled by default | yes | yes | no: off until enabled in `modules.json` |
| Disable / remove from Settings | yes; the shell always survives (empty state) | yes, never blocked | yes |
| Fill an open shell slot (`fills`) | yes | yes | yes, with consent at install (the dialog names the slots); one filler per slot, the owner picks in Settings |
| Tabs, panels, block types, `config.<entry>` | yes | yes | yes |
| Override a node (`shell` overrides) | yes | nodes of official and community modules; a core or shell node only with `allowOverride` | only with `allowOverride` for that node |
| Inject into a node | yes | into nodes that are not locked | only with `allowInject` |
| Locked shell nodes (connect, servers, trust Config, …) | never | never | never |
| Class loader | unfiltered (`PluginClassLoader filtered=false`) | **filtered**, same as community: the plugin API + `ui.shell.api` + `ui.kit` | filtered: the same packages |
| Window controller (`coreController`, `coreShell`) | yes, until the ratchet reaches 0 and they are deleted | **none** | none |
| `ShellContext` | yes | yes (`ui.shell.api`) | yes (`ui.shell.api`) |
| Platform services (events, storage, settings, notifications, daemon) | every declared one | every declared one, unless `deny` lists it | only those in `allow` (`ServiceRegistry`) |
| Daemon routes (`daemon` service, `RouteGate`) | the routes its manifest declares | the routes its manifest declares | the routes its manifest declares; the service needs `allow` |
| Own backend endpoints (`EndpointGate`) | declared endpoints | declared endpoints | declared endpoints; `endpoints` must be in `allow` |
| Demo lock | loads; endpoints only if listed in `demoLock.allowEndpoints` | loads; endpoints only if listed | does not load |
| Safe mode | loads | **does not load**: safe mode = shell + core, so a bad official plugin can be disabled | does not load |
| May depend on (`DependencyGraph`) | core only | core and official | any tier |
| `author` + `license` required in `module.json` | yes (new: today only community must state them) | yes | yes |

Two consequences:
- **Slots become open hosts.** Under D4 the chat, the sessions list and the board overlay are programs, so the slots
  they fill must accept non-core fillers. Only the locked nodes stay closed. This replaces rev 3's "only core fills a
  slot".
- **A module in plugins/official can never reach the window controller.** So a module moves folder only when its
  `appController`/`appShell` uses are 0. That is what makes "official is just a program" true in code, not only in
  name.

### 3d. Removing or replacing an official plugin (user and developer)

Official plugins are never required (D3). Removing all of them leaves the empty state.

**Disable.** Settings > Modules, switch the plugin off.
- This writes `{"Infantry_Board": {"enabled": false}}` into `modules.json`. This is the existing PLUGINS-2 path:
  dependants are listed and switched off with it.
- It applies at the next start or soft restart.
- It is the durable way: an MSI repair or upgrade restores the bundled files, but never touches `modules.json`.

**Remove.**
- A copy in the user root is deleted with Remove. That is `IhqInstaller.uninstall`, extended to official. It runs in
  safe mode, so Windows has released the jar.
- A bundled copy is not deleted by the UI, because the MSI owns it. "Remove" there means disable and hide.
- A developer leaves the plugin out of `product.json`, so their product build never installs it.

**Replace with your own.** Drop a `.ihq` whose module uses **the same module id** (for example `Infantry_Board`) and the
same tab and node ids.
- Signed by another key, it installs into the user root's `community\` folder (3b: no other key reaches official).
- The loader sees two candidates with one name and different signers. The consent dialog says "replaces the official
  Infantry_Board".
- On yes, the dialog writes `{"Infantry_Board": {"source": "user"}}` into `modules.json`. The bundled copy is then
  skipped, not deleted.
- Because the id is the same, every saved layout, hidden-node setting, `plugin-data/Infantry_Board/` folder and
  `modules.json` entry keeps working.
- Alternative: a module with a **new** id that registers the same tab ids, with the official one disabled. This also
  works, but its `plugin-data` starts empty.

**Replacing Approvals (C5).** The approvals UI answers tool prompts, so a replacement could auto-approve. Guards:
the consent dialog names it ("replaces the official Infantry_Approvals, which answers agents' permission prompts");
the answer goes through a daemon route the module must declare and `RouteGate` checks; and the daemon's tool policy
stays the enforcement point, so a UI module can only answer prompts the daemon asks, never widen the policy.

**Undo.** Remove the user copy, or set `source` back to `bundled`. The official version comes back from the install
folder.

### 3e. Ids stay stable (no saved layout or setting breaks)

Moving a folder from `plugins/core` to `plugins/official` changes **only its path and its tier**. These do not change:
- the module `name` (`Infantry_*`): the key in `modules.json` and of `plugin-data/<name>`;
- the tab ids (`infantry.*`);
- the node, slot, block-type and template ids (the L5 rule: never rename a node id);
- the preference keys and `config.<entry>` ids.

**The AppUi split (E7) is the one exception.** It creates new **module** names (Infantry_Chat, Infantry_Approvals,
Infantry_Assistant, …).
- The **node and tab ids** those modules register stay the same, so layouts and hidden-node settings carry over.
- `modules.json` entries for `Infantry_AppUi` (enabled, deny) move to its successor modules through a one-time
  migration in `ModulesFile`, with a test.

**Guard test:** it lists every tab, node and block-type id the default install registers, and fails if one disappears
or is renamed between steps.

### 3f. Which modules move (from section 2)

| Stays in `plugins/core` (end state) | Moves to `plugins/official` |
|---|---|
| none of today's 19 modules. Only orchestration pieces, if any are ever packaged as modules | all 19: Cli, Agents, Attention, Roles, Profiles, Board, Notes, Git, Run, Changes, Epics, Stats, Usage, Prompts, Skills, Triggers, Review, Pods, and the AppUi successors (Chat, Approvals, Assistant, sound/voice, header). AppUi's shell parts go into the shell itself |

**Each move** is one folder rename plus the signed `"tier": "official"`. A module moves only when (a) its class links
are cut, because a core module may not link an official one, and (b) its `appController`/`appShell` uses are 0,
because official has no window controller (3c).

### 3g. The coreController ratchet

The ratchet measures **code still in core that belongs in a plugin** (owner, with D4). One test, two counts, both may
only go down:
1. **program modules in `plugins/core`**: 19 today; target 0 (D4: no program is core);
2. **`appController`/`appShell` uses in `plugins/`**: today's S4d count; target 0.

Each step that moves code out lowers a count and lowers the test's expected number in the same commit. When both reach
0, `TabContext.coreController` and `coreShell` are deleted, the unfiltered loader is used by orchestration pieces only,
and official is a program in every respect.

## 4. What breaks when a module is removed

"Today" = if it were its own jar now and were removed (they all ship in one jar today, so nothing can be removed alone
yet). "Target" = the empty state required once the module has moved. Built from reading the code; **not run**. E2 adds
a test that loads "all minus X" for every X and renders the default shots without an exception.

| Removed | Today | Target empty state |
|---|---|---|
| Cli | `showChat` goes to `homeTab` = the first remaining centre tab (degrades, verified in `App.kt`) | centre shows the other tabs; with no centre tab, the centre slot shows "No chat module: install one" |
| Agents | left panel has no list | left panel hidden; one-line note in the drawer |
| AppUi (all) | empty shell (proven by the shots) | same |
| Chat / Approvals (after split) | n/a | centre: "No chat module"; questions: a status-bar count that opens Settings > Modules |
| Attention | **AppUi still runs; Attention alone would fail to link** if AppUi is gone (`QuestionHistory`, `WatchFlagChip`) | tab gone |
| Board | **AppUi fails to link** (`ShellBoard` uses `BoardModal*`, `LocalBoardOverlay`): the whole UI is refused → empty shell | Board button and overlay gone |
| Git, Run, Notes, Changes | **AppUi fails to link** (it registers `ShellGit`/`ShellRun`/`ShellNotes`/`ShellChanges`) → empty shell | tab gone |
| Roles | **AppUi fails to link** (`ShellRoles`) | roles window/tab gone; new-agent dialog still picks a role |
| Profiles | **Roles fails to link** (`ProfileExecutorField`) | tab gone; Roles' profile editor shows the plain executor picker |
| Epics, Prompts, Skills, Stats, Usage | tab gone (their panes are in the shell, so nothing links) | tab gone |
| Pods, Review, Triggers | tab gone | tab gone |
| all 19 | empty shell | empty shell (agents still reachable through the CLI and MCP) |

Daemon features behind a removed UI module stay reachable through the CLI and MCP. Removing a UI module never removes
data.

## 5. Desktop loading, and web/Android product build (one contract)

**One module contract on every platform:** `module.json` (with `entry` naming a `ModuleSource`) plus the plugin API.
Only how the module gets into the process differs.

**Desktop: loaded at run time.** Each module is its own Gradle project (the Infantry_Board pattern, written once as
`gradle/module.gradle.kts`) and its own signed jar. Its `module.json` `entry` names a 3-line `ModuleSource` in its own
folder. `DesktopPlugins.bundles()` already loads a folder whose entry is a `ModuleSource`, so the only new code is:
- the second (bundled) root;
- the `official` folder;
- the signature and signed-tier check;
- the filtered loader for official (3c).

The desktop app drops `runtimeOnly(agenthqAppUi)` and `isShellJar`. `runShell` becomes "the app with an empty modules
folder".

**Web (wasm) and Android** cannot load code at run time, so the set is composed at build time from the same data.
- `product.json` per product: `{ "modules": "all-official" | [names], "community": [] }`. The default is every
  official plugin; a product may list fewer, down to none (D3). The desktop installer reads the same file to decide
  which jars to place.
- `generateModuleList` (`gradle/product.gradle.kts`):
  - scans `plugins/{core,official}/*/module.json`;
  - filters by `product.json`;
  - orders the modules as `ModuleResolver` would (Kahn over `dependencies` + `sequence`, ties by name);
  - writes `object CoreModules { val all; val factory }` with today's name and API, so the ~20 UI tests and
    agentHqWeb `Main.kt` do not change;
  - adds a `project(...)` dependency for each chosen module.
- The Android srcDir workaround from 4dc8b33f goes away, because nothing is excluded any more.
- What Android can do dynamically later:
  1. compositions (`layout.json`, NS3): data, on every platform, now;
  2. `DexClassLoader` for sideloaded builds only (Play policy forbids it in Play builds);
  3. Play Feature Delivery: still build-time composition.
- Wasm: compositions only.

## 6. Cross-module links and the plugin API

**Rule:** a module links only against the shell's API, never against another module's classes. Today's edges and their
fixes:
- AppUi → Git/Run/Notes/Changes (tab trees), Roles (fill), Board (overlay frame): each module registers its own tree,
  fill or overlay.
- `ReportFlagChip`, `WatchFlagChip`, `ProfileExecutorField`: move to the kit.
- `QuestionHistory`: moves into Attention.
- AppUi → all modules via `CoreModules.all`: generated (E1).

**Official and community link against the same API** (3c). Today the community class loader
(`PluginClassLoader.ALLOWED`) exposes only:
- `plugin.api`
- `ui.plugin` (minus `DaemonLink`, `PluginClassLoader`, `JvmEndpointAccess`, `DaemonNodeConfigApi`)
- `ui.model`
- `ui.protocol`
- `ui.block`
- Compose, Kotlin, kotlinx

It **hides `ui.shell`**, where `ShellContext` and `LocalShellContext` live, and the kit widgets in the `ui` root package.
Since official gets the same filtered loader, a step (E5) must publish, to both:
- `ui.shell.api`: `ShellContext` and its domain interfaces, not `CoreShellContext`;
- `ui.kit`: the widgets.

That is a package move, and it is expensive to reverse (section 9). Then `coreController` is retired module by module
through ShellContext domains (S4d estimates: ChatContext ~30, AgentsContext ~12, ProcessesContext ~5, plus Usage,
Library, Pods, Triggers and Review contexts), counted by the ratchet (3g).

## 7. License-relevant facts (facts only, no conclusions)

- **License files today:** `LICENSE` is a placeholder ("LICENSE NOT YET CHOSEN ... All rights reserved"). LICENSE-1
  (0fc727a6, backlog) proposes MPL 2.0. `THIRD-PARTY-NOTICES.md` lists JediTerm (Apache 2.0, chosen from its LGPL/Apache
  dual license), pty4j (EPL 1.0, with winpty and ConPTY under MIT) and JNA (Apache 2.0, chosen from its LGPL/Apache dual
  license). No source file carries an SPDX header yet.
- **Shell files (= core, D4):** `superiorSoftLibrary/agenthqUiCore`, `superiorSoftLibrary/agenthqPlugins`,
  `superiorSoftLibrary/agentHqUi` (desktop entry and packaging), `agentHqWeb`, `agentHqAndroid` (entries),
  `superiorSoftLibrary/agentHq` (the daemon).
- **Module files (programs):** every folder `plugins/core/*` (today), `plugins/official/*` (planned) and
  `plugins/community/*`, each with its own `module.json`. Today all official module code is in `plugins/core/*`,
  except 1,211 lines of module panes still in agenthqUiCore (section 1).
- **Per-module license field:** `module.json` has `license` and `author`. A community module must state both (the
  resolver refuses it otherwise). Core modules do not fill it today.
- **What a module links against** (the interfaces):
  - (1) the `module.json` schema (`ModuleManifest`, agenthqPlugins `plugin.api`);
  - (2) the Kotlin types in the packages listed in section 6 (`UiPlugin`, `ModuleSource`, `AgentHqModule`,
    `TabProvider`, `TabContext`, `UiRegistrar`, `Block`, `AppState` and the wire types);
  - (3) after E5, `ShellContext` and the kit.

  Modules compile `compileOnly` against `agenthqUiCore` and Compose (see `plugins/community/Acme_Hello/build.gradle.kts`).
  Their jar holds only their own classes.
- **How a module runs with the shell:**
  - desktop: in the same JVM process, loaded by its own `URLClassLoader` whose parent is the app's loader (dynamic
    linking in one address space);
  - web and Android: compiled into the same binary by the product build (static linking);
  - the installed desktop app also runs the daemon in the same JVM.
- **How a module reaches the daemon:** through the shell's `Controller`/`DaemonLink` (core today), or through the gated
  `daemon` service (`RouteGate`, HTTP/WebSocket wire protocol). A module never opens its own daemon connection with the
  owner's credentials.
- **Distribution:** a module can be shipped alone as a signed `.ihq` (a zip of `module.json` + `lib/*.jar`), separately
  from the shell. The Infantry install ships the official modules as separate jars in their own folder (after this
  design).

## 8. Migration (each step shippable; default 32 shots + `empty` shots at 0 px)

| Step | What | Weight | Proof |
|---|---|---|---|
| **E1+E2 (first slice)** | **Infantry_Cli as a real signed OFFICIAL jar.** First commit: `generateModuleList` + one `ModuleSource` per module + `plugins/official` folder support in the scanner (the list stays complete, so 0 px). Then Cli becomes its own project in `plugins/official`. The product build stages `modules/official/Infantry_Cli`. `PluginFolders` gets the bundled root + signed-tier check + `devTrust`; official loads through the filtered loader. Measure plugin load and first frame (BLOCKS-PERF harness); budget <= 500 ms. | 3 | 0 px; tests: unsigned jar in `official\` refused, official-signed jar in `core\` refused, Cli removed → `homeTab` fallback, no exception |
| E3 | `Tier.OFFICIAL` + the fixed three folders (3a/3b) + the per-tier rules (3c, slots as open hosts) + the id guard test (3e) + the ratchet test (3g) + Settings > Modules shows tier, signer and source. Agents moves to official. | 3 | 0 px; the all-minus-X removal test; tests: old modules.json without `official` still loads official, safe mode skips official, official has no `coreController` |
| E4 | Move the 1,211 lines of Epics/Prompts/Skills/Stats/Usage panes + `UsagePacingSection` out of agenthqUiCore into their modules (still in plugins/core: they use ctl) | 3 | 0 px incl. `config` shots |
| E5 | Publish `ui.shell.api` + `ui.kit` to the filtered loader (official and community alike) | 3 | unit tests; a community test module compiles and runs against them |
| E6 | Cut the 9 edges (section 6); Git, Run, Notes, Changes, Board → official jars (no ctl). **After ef796432.** | 4 | 0 px incl. `board`, `registry`; removal test for each; ratchet count 1 down by 5 |
| E7 | **Split AppUi** (section 2): toast, connection items and the config host into the shell; Chat, Approvals, Header, Assistant, Sound+Voice as modules (in plugins/core until their ctl uses reach 0); overlay/diff/roles parts into their owners. agenthqAppUi dissolves. `App_UI.ihq` becomes an NS3 composition with `deps/*.ihq`. | 4 | 32/32 + dropin + empty at 0 px |
| E8… | `coreController` → ShellContext domains, one module per ticket (Roles, Attention, Profiles, Epics, …, Chat, Approvals). When a module reaches 0 uses it moves to `plugins/official`. When the ratchet reads 0/0, delete `coreController`/`coreShell`. | 2-3 each | 0 px each; ratchet down each time |

**Recommended first slice: E1+E2 with Infantry_Cli.**
- It is the smallest module: 29 lines, no edges, no controller use, so it can be official on day one.
- It exercises the whole path: separate project, signed jar, bundled root, signed tier, filtered loader, removal
  fallback, and the web/Android generated list.

The caveat is C3: Cli's transcript is still drawn by AppUi until E7.

## 9. Risks and decisions that are expensive to reverse

**Risks**
- **Start-up:** plugin load went from 226 to 436 ms (+93%) between 17 and 19 modules (BLOCKS-PERF-CHECK). Twenty-odd
  class loaders plus signature checks will add more. The cost is unmeasured, so I will not guess a number. Budget: plugin
  load <= 500 ms and first frame within +3%. If over budget, the fixes in order are:
  1. verify jars in parallel;
  2. a verified-jar cache keyed by (path, size, mtime, SHA-256), which only skips work and never grants trust;
  3. lazy loading of tab-only modules.
- **Approvals as a replaceable program (C5):** safe only while the daemon's tool policy is the enforcement point and
  the answer route is declared and gated (3d). A change that lets a UI module set policy would break this.
- **Open slots:** with chat and the sessions list as programs, the slots accept non-core fillers. Two plugins filling
  one slot need a clear owner choice in Settings (3c); the 0 px gate and the removal test cover the default.
- **Android:** about 20 more library projects add configuration time; measure it in E2. The 4dc8b33f srcDir workaround
  disappears.
- **Resources through class loaders** (fonts, icons): the 0 px gate at every step catches it.
- **Host RAM** (note `host-ram-exhaustion-jvm-crashes`): more projects mean more Gradle workers. One build at a time.
- **Parallel work:** SETTINGS-TAB-1 (ef796432) touches the same registrations. E6 waits for it.

**Expensive to reverse**
1. The signed tier claim plus the pinned Infantry key as the trust root for core and official. Rotating the key means
   re-signing everything, and the shell must pin both keys for one release.
2. Official = same filtered loader and API as community (no core-level trust). Once official plugins are built
   against only the public API, giving them back private access is cheap; taking it away again is not. This is the
   point of D4, so it is the intended direction.
3. Publishing `ui.shell.api` and `ui.kit`. Every exported type is frozen under `apiVersion`, and those types are what
   section 7 lists as the linked interfaces.
4. No class links between modules: easy to relax later, hard to take back once third parties rely on it.
5. Folder names `core` / `official` / `community` and the `<Name>/lib/<Name>.jar` layout. The generator, the loader,
   the installer and the MSI all read them.
6. `product.json` as the one composition manifest for all three platforms.
7. The empty-state contract (`shell-empty` shots) as the definition of "no modules", which is also the app with every
   official plugin removed.
