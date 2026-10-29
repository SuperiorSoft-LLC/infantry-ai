# GAP-AUDIT-1: the demo as a compass (ticket d6931ba8)

Owner principle (2026-10-29): no hacks, no work-arounds. A gap in overrides, hooks or tools is a blocker, and the answer
is to build the tool. Core is the abstraction layer and concrete things are plugins. When something migrates out of core,
it is deleted there.

Method: read the demo-build port (docs/DEMO-MERGE.md, 31 commits, rows 1-27) and the POLICY-1 port (docs/POLICY.md).
For each place the demo needed a hand edit in core, a check in one pane, or a special case, this doc names the
universal tool that would have avoided it. A read-only sweep then went over the rest of the app (core UI, SDK, daemon,
build scripts, official plugins) looking for the same smell. Sizes: **S** = under a day, **M** = 1-3 days,
**L** = more than 3 days.

Legend for "exists": **yes** = the tool is built and the demo uses it; **part** = built but with a hole;
**no** = not built; **closed here** = built by this ticket (Part 2, section 4).

## 1. What the demo needed, one row per hand edit

| # | demo edit (DEMO-MERGE row) | what it does | universal tool | exists | size |
|---|---|---|---|---|---|
| 1 | DemoLock class in daemon core (11, 14, 16, 17, 22, 25) | seeded project and seats, visitor token, per-IP turn limit, scoped reads, refusal of interrupt/kill | a policy preset (data) plus a **daemon plugin** that owns the seated-lockdown mechanism | part: the values are data (`policies/demo.json`), but the mechanism `DemoLock` is still a core class named after the demo | L (DAEMON-PLUGINS-1) |
| 2 | `FrameGate.DEMO_LOCK_ALLOWED` list (SEC-FIX-1) | read-only frame list when `AGENTHQ_DEMO_LOCK=1` and no preset is installed | the policy preset (`*` off, `kind:read` on) is the same thing as data | part: kept as a fail-closed fallback | S (delete once the preset ships in every image) |
| 3 | `AGENTHQ_DEMO_*` env vars read by core (`DemoLock.fromEnvironment`) | deployment overrides of seat values | preset `seats` fields plus a generic `AGENTHQ_POLICY_<KEY>` override, or a daemon config field | part | S |
| 4 | `/demo/visitor-token` route in core, `withDemoToken` in AccessPolicy | public visitor token for anonymous visitors | **daemon plugin route** (`endpoints`/`routes` served by a plugin) plus a generic "public guest token" access tier | no (a daemon plugin cannot declare a route) | M |
| 5 | wasm `Main.kt` fetches `/demo/visitor-token`; offline `WebMode.Demo` with `DemoFixtures` / `FakeDaemonClient` | land a visitor connected; the offline web demo | a **web bootstrap hook** (`BootstrapProvider`: "how do I get a token / a client") that a compiled-in plugin provides | no | M |
| 6 | `SessionManager.demoLocked` and "demo: new sessions are disabled in this demo" | refuse `new` under the lock | the policy gate (`agents.spawn` off) already refuses it; the flag is now redundant | part | S |
| 7 | `SESSION_CAP_REFUSED = "Demo busy"` | the message at the session cap, shown in **every** deployment | a config field / the preset's text (`limits.concurrency.refusal`) | no | S |
| 8 | RunManager / KeystoreHandler "under the demo lock" refusals | refuse interactive runs and secret changes | the policy gate on `runs.start` / `secrets.save` with the preset's refusal text | part (the gate is total; the extra text is a leftover) | S |
| 9 | `ModulesConfig.demoLock` (`allowRoutes`, `allowEndpoints`) in the SDK | which plugin routes pass under the lock | a policy rule on route ids (`route:<id>` actions in the same matcher) | no | M |
| 10 | Model/role allowlist (rows 4, 5) | ExecutorPicker hides models and roles | `limits.models.allowed` / `limits.roles.allowed` plus preset `allowedModels` / `allowedRoles` | yes | - |
| 11 | Dimming write controls one pane at a time (rows 15-24) | Board, Roles, Skills, picker, `/` catalog, role Edit | named actions plus the policy gate (POLICY-1), `canWrite` for undeclared writes | yes, but see 12-14 | - |
| 12 | Sidebar agent menu and RoleManager delete on the generic write gate (POLICY-1 port) | the menu opened, and delete worked, only while any write was allowed | per-item action ids in module.json | **closed here (g)** | - |
| 13 | A control that checks neither `canWrite` nor a gate (raw clickable) | cannot be dimmed; its click got a daemon error | send-side gate plus a coverage guard | **closed here (h)** | - |
| 14 | Seated preset switched at runtime: a seating failure was only logged | the owner saw "Demo" active with no seats | the seating outcome in the `policy` frame, shown in the Policy card | **closed here (f)** | - |
| 15 | `Demo_Banner` depends on `Demo_Lock` (Gradle dependency and `LocalDemoLock` import) | the banner reads the lock's notice | a **plugin service** (`services`: Demo_Lock provides `notice`, Demo_Banner consumes it by key) or `readyFields` directly | part (the services registry exists, unused here) | S |
| 16 | Daemon `installDist` copies `plugins/demo/Demo_Lock/policies` | the preset reaches the daemon | **plugin `policies/` folders packaged by the plugin's own build** (any plugin, not one named one) | part (the provider is generic, but the copy names Demo_Lock) | S |
| 17 | `-Pdemo=true` module list checked by hand (two-line diff) | prove the demo set is only the demo modules | automated generator test | **closed here (d)** | - |
| 18 | A throwing override cannot fall back (Compose forbids try/catch) | a buggy demo override would take the window down | error boundary in the Block API | **closed here (a)** | - |
| 19 | Non-overridable = only `CoreShell.locked` | a plugin cannot protect its own nodes | `overridable: false` in module.json `nodes[]` | **closed here (b)** | - |
| 20 | No "who overrides what" view | the owner cannot see what the demo replaced | read-only list in Settings > Plugins > Registered | **closed here (c)** | - |
| 21 | Community grants are exact strings | a grant for a pane's subtree needs every id listed | glob patterns in `allowOverride` / `allowInject` | **closed here (e)** | - |
| 22 | Docker: compose overlay, seed env, sandbox (rows 1, 3, 7, 13) | deployment | infra, not core | n/a | - |

About (b): About is `infantry.shell.config.about`, a section of the locked trust kernel (`ShellConfigTrust`). It is
in `CoreShell.locked` through `+ ShellConfigTrust.map { it.id }`, so no change was needed. `OverrideGapsTest` pins it.

## 2. The same smell in the rest of the app

Ranked by how often a third-party plugin author would hit it. File references are at the time of writing.

### 2a. What a plugin cannot extend (it has to edit core)

1. **A community module cannot fill a shell slot or add a tab tree** (`TabFactory`: "only a core module fills a
   shell slot"). This is the first wall an author hits when building a pane-level app. Tool: tiered fill grants,
   where the owner grants `allowFill` for a slot, like `allowOverride`. M.
2. **Closed enums for Configuration**: `ConfigTab` and `ConfigEntry` (`ConfigTabs.kt`). A plugin cannot add a Settings
   tab or a search entry; it can only add fields to an open section. Tool: a `config.tabs[]` declaration in module.json
   plus a registry. M.
3. **Fixed shell slots and tab strips**: `CoreShell.slots` lists them by feature (board, diff, sound, voice, questions,
   theme, roles, processes badge), and `TabContainers` is CENTER/RIGHT only. Tool: plugin-declared slots
   (`slots[]` in module.json, placed by an injection) and named containers. M.
4. **`CONFIG_OWNED_SECTION_SLOTS`** (`ShellConfig.kt`) is tied to five named official plugins. Tool: the same
   declared-slot mechanism. S once 3 exists.
5. **Plugin frames cannot declare their own policy/owner-only status**: `FrameGate.OWNER_ONLY` and `ActionCatalog` are
   core constants. A daemon plugin's frame (DAEMON-PLUGINS-1) would be refused under any non-production preset.
   Tool: daemon plugins declare `actions` with frames, and the catalog merges them. M (with DAEMON-PLUGINS-1).
6. **A daemon plugin cannot add a route or run code** (PLUGIN-OVERRIDES section 8). This blocks rows 1, 4 and 9 above.
   L (DAEMON-PLUGINS-1, owner decision on isolation pending).
7. **Model catalog and device-scope migrations are constants** (`ClaudeModelCatalog.kt`, `DeviceScope.kt`). Tool: a
   provider registry and plugin-declared migrations. S each.
8. **Feature UI still in agenthqUiCore** (`ChatPanelState`, `AssistantPanelState`, `AttentionKit`, `RunPills`,
   `LimitsSection`, `voice/`, `sound/`). Changing those features means editing core. This is the D4 migration
   backlog, not a new tool. L, spread over migration tickets.

### 2b. Core names a specific plugin

1. `ATTENTION_TAB_ID = "infantry.attention"` lives in core (`App.kt`). Infantry_Attention and Infantry_Header both
   import it, so two plugins are coupled through core. Tool: a well-known-id service (`services`), or the tab id in
   the provider's module.json, looked up by the consumer. S.
2. `BOARD_SLOT`, `REVIEW_TAB`, `openBoard` and `reviewLoaded` in `CoreShellContext` serve Infantry_Agents' sidebar.
   Tool: a plugin service ("open the board") that Infantry_Board registers and Sidebar asks for by key. S-M.
3. `FreshShell.kt` and `agentHqUi/build.gradle.kts` filter shell jars by the literal `"Infantry_Board"`. Tool: a
   manifest flag (`shellJar: true`) or the drop-in list. S.
4. **The module-list generator read only `Infantry_*/module.json` and `Demo_*/module.json`**, while the projects
   compiled every folder (an `Acme_X` official module was built but never listed). **Closed here (d)**: every folder
   not starting with `_`.
5. `agenthqAppUi/build.gradle.kts` still adds only `plugins/core/Infantry_*` folders as srcDirs. The folder is empty
   (RUNTIME-26), so this is latent. S.
6. No guard covers Infantry_ names in core. `NoDemoInCoreGuardTest` covers only "demo" in agenthqUiCore plus official.
   `NoPresetInCoreGuardTest` covers only the demo preset's values. The daemon, SDK, agentHqWeb and build scripts are
   unguarded. Tool: one `NoPluginNameInCoreGuardTest` over every core module and the build scripts, with a ratchet. S.

### 2c. Core names a deployment or tier

1. The daemon's `DemoLock`, `DemoTurnLimit`, `/demo/visitor-token`, `withDemoToken` and the "demo lock" messages
   (section 1, rows 1-9).
2. SDK: `ModulesConfig.demoLock` (`DemoLockConfig`), `PluginFolders.DEMO_LOCK_ENV`, and the `demoLock` parameter threaded
   through `ModuleResolver`, `EndpointGate` and `ServiceRegistry`. The ratchet of `NoDemoInCoreGuardTest` allows these
   (DesktopPlugins 10, DesktopDropIn 5 and others). Tool: the drop-in lock becomes a policy action
   (`plugins.dropin`), so the env switch becomes the preset's. M.
3. UI copy that says "demo lock" (`ConfigDialog.kt`, `ShellDropIn.kt`, `DesktopDropIn.kt`). Same fix as item 2.
4. `Tier` (core/official/community) is a trust mechanism, not a deployment, and is legitimate. `Distribution.DOCKER`
   in `ServerUpdateService` is only for update instructions, so it stays.

### 2d. One plugin reaching into another's classes

1. `Demo_Banner` → `Demo_Lock` (`LocalDemoLock`, Gradle dependency): the only direct case (row 15).
2. Indirect coupling through core constants (section 2b, items 1-2).
3. Infantry_Agents, Infantry_Cli and Infantry_Settings share the package `com.superiorsoftllc.agenthq.plugins.official`.
   There are no cross-references today, but nothing stops one. Tool: one package per plugin, plus a guard that a
   plugin's sources import no other plugin's package. S.

### 2e. Write controls not gated (the POLICY-1 hole, before (h))

These panes checked only `live`, so they were never dimmed under a policy: `Infantry_Notes/MemoryPane` (note save and
delete), `Infantry_Attention/AttentionPane` (answer, cancel, ack), `Infantry_Chat/ChatPanel` (answerQuestion,
cancelQuestion, startSession/resume) and `ChatSurface` (answerPrompt), `Infantry_Board` (drag handle on `live`, role
nudge), `Infantry_Header/ProjectPicker` (lane cap, pipeline toggles, directory save/clear), and `Infantry_Run`
(command edit/delete on `canEdit = live`).
**Since (h)** none of them can send a write the policy refuses: the send-side gate stops it with the policy's reason.
They still are not *dimmed*, because a control that never asks cannot be dimmed. Fix per pane: declare the action in
module.json and ask `gate(id)`. The coverage guard then keeps each declaration honest. S per pane; one ticket for all
of them.

## 3. Ranked gaps (launch impact × how often a plugin author hits it)

| rank | gap | launch impact | author frequency | recommended ticket | size |
|---|---|---|---|---|---|
| 1 | Ungated write controls in Notes, Attention, Chat, Header, Board, Run (2e) | high: the public demo shows enabled buttons that answer with an error (no longer a leak since (h)) | high | **GATE-PANES-1**: declare actions and ask the gate in each pane | M |
| 2 | Community module cannot fill a slot or add a tab tree (2a.1) | medium | very high | **FILL-GRANT-1**: `allowFill` grant, the same trust rule as `allowOverride` | M |
| 3 | `SESSION_CAP_REFUSED = "Demo busy"` in every deployment (row 7) | high: production users see "Demo busy" | n/a | **COPY-1**: neutral text, preset/config override | S |
| 4 | Visitor token and offline demo in wasm `Main.kt` (row 5) | medium (the demo works, but core names it) | low | **WEB-BOOT-1**: bootstrap hook a compiled-in plugin provides | M |
| 5 | Plugin-to-plugin coupling through core constants (2b.1-2, 2d.1) | low | high | **SERVICES-1**: well-known plugin services (open board, attention tab, notice) and remove the constants | M |
| 6 | Closed `ConfigTab` / `ConfigEntry` enums (2a.2) | low | high | **CONFIG-TABS-1**: plugin-declared Settings tabs | M |
| 7 | Plugin-declared slots and tab containers (2a.3-4) | low | high | **SLOTS-1** | M |
| 8 | DemoLock / visitor route / turn limit in daemon core (rows 1, 4, 9; 2a.5-6) | medium | medium | **DAEMON-PLUGINS-1** (exists, owner decision pending), then **DEMO-DAEMON-PLUGIN-1**: move the seated lock into it | L |
| 9 | Drop-in lock named after the demo in the SDK (2c.2-3) | low | low | **DROPIN-POLICY-1**: the drop-in lock as policy action `plugins.dropin` | M |
| 10 | No guard for plugin names in core; shared official package (2b.6, 2d.3) | low | medium | **GUARD-2**: one name guard over every core module and the build scripts; one package per plugin | S |
| 11 | Demo_Lock policies copied by name in the daemon build (row 16) | low | low | fold into GUARD-2: package every plugin's `policies/` | S |
| 12 | Redundant demo leftovers: `demoLocked`, "under the demo lock" texts, `FrameGate.DEMO_LOCK_ALLOWED` (rows 2, 6, 8) | low | n/a | **DEMO-CLEANUP-1** (after 8) | S |
| 13 | An override that throws on a *later* recomposition (not the first) (section 4a) | low | medium | **OVERRIDE-WATCHDOG-1**: in-flight marker plus desktop crash handler, which disables the culprit on the next start | M |

## 4. What this ticket closed (Part 2, additive)

**(a) A throwing override falls back.** `block/layout/OverrideGuard.kt`. Before a module's block (replace, wrap,
before, after, and every injection) is drawn the first time, it is composed once in a throwaway `Composition` of its
own. That composition has a no-op applier, and its effects dispatcher never runs, so no `LaunchedEffect` starts. It
gets the window's composition locals. This composition is plain code, so its exception is caught. Until the probe
answers (one frame), the original is drawn. A block that threw is never drawn: the core node is drawn instead for a
replace or wrap, and nothing for before/after/injection. Its fault is listed in Settings. A wrap's inner node is
movable content, so the switch from the fallback to the wrap keeps the inner node's state. In the probe, the wrap gets
an empty stand-in for its inner node, so core's subtree is never composed twice. The verdict is kept per block
instance, so a reloaded module is probed again.
Not covered: a throw only on a later recomposition, and lazy/subcomposition content, which composes at measure time
(rank 13).

**(b) `overridable: false`.** module.json `nodes: [{"id": "...", "overridable": false}]` (`NodeDeclaration`). The
effective lock set is `TabFactory.locked` = `CoreShell.locked` plus the declared ids. A community module may lock only
`<name>.*` ids; any other lock is refused with a line. About was already locked (section 1).

**(c) Who overrides what.** `TabFactory.overrideMap` (`OverrideEntry`: module, tier, mode, selector, resolved node,
full path from the root, priority, drawn, problem) is built from the resolved trees, not a second resolve. It is shown
read-only in Settings > Plugins > Registered (`OverrideMapList`), together with any probe fault from (a).

**(d) The demo module list is tested.** The generator is one function, `writeModuleList`, in
`agenthqAppUi/build.gradle.kts`. The `testModuleListGenerator` task (wired into `jvmTest` and `check`) runs it with
demo off, demo on, and off again, on the real manifests. It checks that:
- demo off names no demo module;
- demo on is the live list followed by exactly the demo modules, in name order;
- the desktop list and the common file are byte-identical both ways;
- the config is the live config plus only demo modules' config;
- two runs give the same output.

The generator now reads every plugin folder not starting with `_`, not only `Infantry_*` / `Demo_*` (section 2b.4).

**(e) Glob grants.** `GrantPattern` (SDK): exact strings, `*` = any characters except `/`, `**` = anything. Used by
every grant check: the desktop loader (`ModuleResolver`), the compile-time path (`TabFactory`), injections
(`admitInjections`), layout fills, the registry's `granted`, and config ownership. A grant never reaches a locked node.

**(f) Seating failure surfaced.** `PolicyRuntime.seatingFailed` is called by the daemon's seat listener with the
failure or null. `PolicyWire.seatingError` is sent in the next `policy` frame (the per-connection pushes run after the
seat listener) and is cleared on the next switch. The Policy card shows "Not fully applied: ..." (polite live region).

**(g) Per-item gates.** Sidebar agent menu: it opens while live (Open is a read). Assign is gated on
`agents.project.set` (new declaration in Infantry_Agents, frame `session_project`), and Remove keeps `agents.delete`.
RoleManager Delete is gated on `roles.delete` (new declaration in Infantry_Roles, frame `role_delete`). Both ids
already existed in the daemon catalog, so `PolicyActionParityTest` holds.

**(h) Raw sends cannot slip.** Two tools:
1. **Send-side gate.** The `policy` frame now carries the daemon's frame→action catalog (`PolicyWire.frames`, empty
   under production, without the switch frames). `Controller.send`, which every client command passes through, asks
   `PolicyWire.refusesFrame`. That is the same matcher as the daemon, and `PolicyRefusal.text` gives the same refusal
   text. So a write the policy turns off never leaves the client, even from a raw clickable. Unmapped frames are left
   to the daemon. An older daemon sends no catalog, so nothing is refused client-side.
2. **Coverage guard.** `PolicyGateGuardTest` fails when a plugin declares a non-read action that no control in its own
   sources asks through `gate` / `Gated` / `ActionButton` / `rememberGate` / `can`.
