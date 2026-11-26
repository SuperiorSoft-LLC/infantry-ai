# NORTH STAR: the shell is a replicable canvas; a section exports as a plugin and drops into a fresh shell

Ticket b9644864. This is a design note only: no code, no Gradle, no tests. Base: `feature-agenthq-mvp` @ a6660b31.
It builds on:
- docs/BLOCKS-LAYOUT-DESIGN.md: 6p-6r (placeholders, `ShellContext`, the trust kernel), 6t (PAGE BUILDER: types,
  instances, the export format, `fills[].layout`).
- docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md and S4/S4d/S6/S7 as built: infantryAppUi split out, empty slots, the drop box,
  consent, soft restart, safe mode, the signed 18-module App_UI.ihq.
- docs/IHQ-PACKAGE.md (S5).
- docs/PLUGIN-ENDPOINTS-DESIGN.md (D1-D5, as built in a073edfa and 779d5845).
- PLUGIN-PLATFORMS-1 (25d2b689).
- PAIR-1 (`PairingLink`, `QrCodeView`).
- SEC-FIX-1 (`OwnerCredential`, `FrameGate`).

Related open tickets:
- 7a062c28 (BLOCKS-1 P1, block registry) overlaps PB1 (section 5).
- eb5a962f (demo visitor tier, a weaker token) is the same mechanism as the scoped grants in section 4.
- No other board ticket touches this work.

**For the owner, in ten lines.**

```
1. The shell is the body: window skeleton, trust settings, plugin host, daemon client. It is locked and the same in every app.
2. An app is the shell plus a composition: a layout.json (which block types sit where) plus the code modules of those types.
3. "Export a section" = take node N's subtree, write it as layout.json, and pack it with the module packages of its types into one .ihq.
4. A fresh shell (the shell-only build, empty plugins folder) shows the drop box. Drop the .ihq, consent, soft restart, and it is that app.
5. Code is never cut out at run time. A section can be exported only once its code is its own module and its nodes are registered types.
6. The first example is the scrum board. Today zero sections are exportable; the board needs 14 types and a module split first.
7. Data comes from a daemon. The exported app connects to a configured daemon, or else starts its own bundled one (connect-or-bundle).
8. Attaching to someone's daemon needs a grant the owner approves (or a one-time QR code). The app gets a SCOPED token, never the owner's.
9. A hub where every exported app registers is later. The daemon's list of grants is its seed.
10. The weekend target: the board exported, dropped into a fresh shell running its own bundled daemon. Connect mode follows, with scoped grants.
```

---

## 0. What exists today, and what is missing (from the code, not from plans)

| Piece | State |
|---|---|
| Empty slots | Exists. `CoreShell.slots`: header, left, center, right, drawer, footer, assistant, toast, board, diff, sound, voice, questions, config.settings, roles. `UiRegistrar.fill(node, block)`; `Infantry_AppUi`'s module.json `fills` lists all of them. |
| Shell without the app UI | Exists. infantryUi has infantryAppUi `runtimeOnly`. The `-PtabShotsExtra=dropin` acceptance runs the shell with infantryAppUi filtered off the classpath, installs App_UI.ihq through the drop box and matches 32/32 at 0 px. |
| Drop box, consent, soft restart, safe mode, unload | Exists (S6, S7). |
| `.ihq` | Exists. A signed zip of `module.json` + `lib/*.jar`; a bundle carries many modules (S7). Tier comes from a pinned signer; there is no upgrade path. |
| `ShellContext` | Exists. link, session, git, run, board, roles, toast, config, layout, notes, changes, executor, traits, tickets, window, questions. It is the only state a block may read; additions only. |
| Board tree | Exists as **Kotlin blocks with ids**: `infantry.shell.board.pane` and its sections, plus the `infantry.shell.board.card` template (6m). They read `TicketsContext`/`BoardContext` only (S4d). |
| Block **type** registry (PB1) | Missing. 6t designs it; nothing is built. |
| Export / `fills[].layout` (PB6) | Missing. |
| Shell-only *package* | Missing. A shot task proves it; no launcher or MSI exists. |
| Daemon | Bundled **in-process** in the installed app (`AppMain` -> `runRealDaemonUi`, state dir and port pinned by `InstallProfile`, `StateDirLock`). Dev connects to 8787. |
| Tokens | Two: the **access token** (every agent and every paired device has it; it reaches the whole non-owner API) and the **owner token** (`owner.token`, needed for `FrameGate.OWNER_ONLY`). There is no scoped token. |
| Pairing | `infantry://connect?url&token&name` as a QR or link. It carries the **access token**, so it is too broad for an app (section 4). |

The image measured on disk: `infantryUi/build/compose/binaries/main/app` = **234 MB** (jlink `includeAllModules` JDK +
Compose/Skiko + daemon 4.9 MB + infantryUiCore 3.6 MB). Every other size in this note is an estimate and is labelled as one.

---

## 1. What "export a section as a plugin" means

### 1a. Three things, and only these three

An exported section is a **composition**:
1. **Layout**: node N's *effective* subtree, in the 6t.5 page format:
   `{"schema":1, "modules":[{name,version}], "root":{blockType,id,config,children}}`.
   Instances carry their declared config fields (6s A). There are no class names, colours or pixels.
2. **Code**: the module packages that register every `blockType` named in the layout. These are existing `.ihq` packages,
   each with its own signature and tier. **Export never builds or carves code.** A JVM cannot pull a module's classes back
   out of `App_UI.jar` in a form anyone could sign. So a section whose code shares a jar with unrelated modules can be
   exported only after its module is split into its own package (the board: NS2).
3. **Daemon needs**: the union of the `daemon.needs` capabilities the modules declare (new module.json field, section 4c),
   for example `["projects.read","tickets.read","tickets.write","epics.read"]`. Optional capabilities go under
   `daemon.wants`. This is what the app asks a daemon for when it attaches, and what its blocks degrade on when refused.

### 1b. The file: one droppable `.ihq`, extended

```
Board.ihq                         (signed as a whole, like every .ihq)
  module.json                     composition module: name, version, apiVersion, NO jar/entry,
                                  "fills": [{"node":"infantry.shell.center","layout":"layout/board.json"}],
                                  "dependencies": [{"name":"Infantry_Board","version":"1.0.0"}],
                                  "app": {...optional, section 2c}
  layout/board.json               the page format above
  deps/Infantry_Board.ihq         the code package, nested and byte-identical to the published one (its own signature)
```

- `IhqInstaller` today accepts only `module.json` and `lib/*.jar`. It gains `layout/*.json` and `deps/*.ihq`. Each nested
  package is installed by the **same** checks, recursively one level deep (a nested package may not nest again), and gets
  **its own tier from its own signer**. If a dependency is already installed with the same name and version, it is skipped.
  A different version is refused, because there is no upgrade path yet (section 6).
- The outer package is **data**: it carries no code, so it can never be core tier through its own signer. Its fill of a
  slot goes through the normal `allowOverride` consent. The consent dialog lists the outer signer and each nested signer
  separately.
- Signing an export: the first export creates a per-installation **export key** (in the daemon keystore, owner-only). On the
  owner's own shells it is trusted as a community publisher, never as the Infantry core key. An exported board therefore
  installs as a community composition that wraps the Infantry-signed core `Infantry_Board` package.

### 1c. Refusals (export is all-or-nothing, as 6t.5 says)

The export refuses and lists one line per cause:
- a node in the subtree that is not an instance of a registered type ("not a type yet: infantry.shell.board.card.edit");
- a type whose module is not available as a separate package ("Infantry_Board is inside App_UI.ihq; publish it as its own package");
- a locked node in the subtree (trust nodes never travel);
- an instance config value that is a secret reference (secrets never travel; the target owner sets their own,
  PLUGIN-ENDPOINTS D3).

### 1d. Standalone daemon vs shared daemon

The composition does not know or care which daemon it is on. Its blocks read `ShellContext` exactly as in the main app;
the shell's core decides which daemon feeds `ShellContext` (section 4). This is the main contract of this note:
**a section's portability = it reads only `ShellContext` + its own endpoints (PLUGIN-ENDPOINTS) + its declared daemon
capabilities.** S4d already holds the board to the first of these.

---

## 2. The shell-only build target

### 2a. What changes in the build

- New tasks in infantryUi (not a new Gradle module, to avoid copying 36 KB of packaging script):
  - `runShell`: a `JavaExec` with the classpath filtered exactly as the dropin shot task does today
    (`!"infantryAppUi" in path`).
  - `createShellDistributable` / `packageShellMsi`: a second Compose `nativeDistributions` flavour. Its
    `packageName`, `upgradeUuid` and icon come from the app manifest (2c), so a shell-built app never upgrades over
    Infantry AI. Compose Desktop allows one `application {}` per project, so the second flavour is a copy step plus
    `jpackage` over the shell runtime image, the way PKG-4 already post-processes jlink output.
- The launcher gets `--app <appDir>`. It reads `<appDir>/app.json` (2c) and uses `<appDir>/plugins` as the plugins root,
  through the existing `INFANTRY_PLUGINS_ROOT` mechanism. The state dir is `%USERPROFILE%\.infantry\apps\<appId>\`, never the
  Infantry install's.
- The daemon stays **in** the shell-only build: `:infantry` stays a dependency, because connect-or-bundle needs a bundled
  daemon. A **thin** flavour (no daemon jar, no SQLite native, a trimmed jlink module list) is a later ticket (NS-L1). The
  daemon boot moves behind a `DaemonLauncher` found through ServiceLoader, so its absence is a classpath fact and not an
  `if`.

### 2b. What the empty shell shows (unchanged from S6, plus one line)

- The locked skeleton.
- The **drop box** in the empty `infantry.shell.center` slot: "Drop a .ihq here, or Choose file".
- Configuration's trust kernel: plugins, modules, secrets, pair, about, updates, serverUpdate, logs.
- connect / servers / switchError.
- New: a one-line daemon status under the drop box ("Own data on this computer" / "Attached to <name>" / "No daemon"),
  which opens Servers.

Every other slot is empty and draws nothing.

### 2c. The app manifest (`app.json`, beside the plugins folder; or the `app` object of a composition's module.json)

```json
{ "appId": "com.example.board", "name": "Board", "icon": "icon.ico", "windowTitle": "Board",
  "daemon": { "mode": "connect-or-bundle", "url": null, "seedProject": "Board" } }
```

- `mode` is one of `connect-or-bundle` (default), `connect`, `bundle`.
- `seedProject` creates one project on the **first** start of a bundled daemon, so a board has somewhere to put tickets.
- A composition dropped into a shell with no `app.json` offers to adopt its `app` object (title, icon). The `appId` is
  fixed once it is written, because it names the state dir and the MSI upgrade identity.

### 2d. Size and start-up (estimates, not measured, except the 234 MB above)

- The shell-only image is about the same as today: **~225-230 MB**. It drops only the app UI jar (a few MB). The size
  comes from the JDK runtime (`includeAllModules`) and Skiko.
- A thin flavour with a trimmed jlink is estimated at **~120-150 MB**. That needs a `suggestRuntimeModules` pass on a real
  install.
- Start-up is dominated by JVM + Compose init and the in-process daemon (SQLite open, migrations), not by the UI jar. The
  empty shell should start *slightly faster* than the full app. Loading one community module from disk adds its
  classloader cost (small).
- **Measure it in NS10. Do not promise a number before that.**

**NS10 measured (2026-10-28, from the build output on disk; no new distributable was built):**

| What | Size | Source |
|---|---|---|
| Full app image `infantryUi/build/compose/binaries/main/app/Infantry` | **234 MB** | `du`, build of 2026-10-23 |
| of which the jlink runtime (`runtime/`, `includeAllModules`) | **155 MB** | `du` |
| of which the jars (`app/`, Compose + Skiko + ktor + SQLite + ours) | **79 MB** | `du`; ours: infantry 4.9 MB, infantryUiCore 3.7 MB, infantryUi 0.1 MB |
| Installer `main-release/exe/Infantry-1.0.1.exe` (compressed) | **118 MB** (123,331,072 B) | `stat` |

- The shell-only image is that image minus the app UI and board jars (`isShellJar`), so **~230 MB**: the estimate
  holds. The runtime is two thirds of it; the thin flavour (trimmed jlink) is where the size can drop.
- The 10-23 image predates the NS2 module split, so it has no separate infantryAppUi/Infantry_Board jar to subtract.
  An exact shell number needs `gradlew :superiorSoftLibrary:infantryUi:packageShellMsi` (or `createRuntimeImage` +
  `shellJars`, then `du build/shell/input` + the runtime).
- The exported `Board.ihq` itself is small (layout JSON + the nested Infantry_Board package), not part of the image.
- **Start-up: not measured yet.** It needs a timed window launch, which this ticket did not run (no waits). To measure:
  Registered > `infantry.shell.board.pane` > **Try in a fresh shell**, and time from the click to the drop box; the
  child's log is `<stateDir>/fresh-shells/<appId>/shell.log` and its `[infantry-app] ... daemon=` line marks the daemon
  choice. Record it here when the owner runs it.
- Each running exported app is its own JVM. With the PERF-2 heap caps, expect a few hundred MB RSS per app, plus a second
  daemon when bundled. That matters on this host (knowledge note `host-ram-exhaustion-jvm-crashes`).

---

## 3. First example: the scrum board, exported and dropped into a fresh shell

### 3a. What must become registered types first

The section to export is `infantry.shell.board.pane`, not `infantry.shell.board`. The latter is the modal frame (backdrop,
Esc, close), which belongs to the overlay in the main app. In a fresh shell the board **fills the center slot** directly.

| Node (6m) | Type to register | `needs` / note |
|---|---|---|
| `board.pane` | `board.pane` | column; `takesChildren`; owns `BoardPaneHost` (filter, drag, drop session) |
| `pane.filters` | `board.filters` | parents: `board.pane` |
| `pane.unassigned` | `board.unassigned` | parents: `board.pane` |
| `pane.newTicket` | `board.newTicket` | parents: `board.pane`; max 1 |
| `pane.empty` | `board.empty` | parents: `board.pane` |
| `pane.columns` | `board.columns` | parents: `board.pane`; max 1; template host |
| `board.card` | `board.card` | **template**; parents: `board.columns`. The page format gains `"template": true` on a child: drawn per item, never a fixed instance |
| `card.summary` | `board.card.summary` | `takesChildren` |
| `card.summary.title` / `.badges` / `.details` | `board.card.title` / `.badges` / `.details` | parents: `board.card.summary` |
| `card.note`, `card.edit`, `card.actions` | `board.card.note` / `.edit` / `.actions` | parents: `board.card` |

That is **14 types**. Each gets a factory (`BlockType.create(id, children)` wrapping the existing section composable) and
a render test under its declared parent. These nodes already render only inside their host locals (6m: "a section
drawn outside its pane draws nothing"), so `parents` is a declaration of what is already true.

Instance config to declare now (6s A): `board.pane.columns` (multiSelect of statuses, default all five) and
`board.filters.showStartNext` (checkbox). Two fields are enough to prove per-instance config travels. Add no more.

### 3b. The module split

The board's code moves from the 18-module bundle into its own module **`Infantry_Board`** (plugins/core/Infantry_Board,
its own Gradle project and jar, `fills` none, `blockTypes` the 14 above).
- infantryAppUi depends on it and still draws the board overlay from those types, so the main app is pixel-identical:
  the board shots (`-PtabShotsExtra=board`, 4 shots) stay at 0 px.
- `appUiIhq` keeps bundling it for the main app.
- A new `boardIhq` task publishes it alone as `Infantry_Board.ihq`, signed with the same key, so it is core tier.

### 3c. What it needs from the daemon (frames that exist today)

| Capability | Frames / events | Used by |
|---|---|---|
| `projects.read` | `project_list`, project events | the active-project filter, `board.unassigned` |
| `tickets.read` | `ticket_list`, `ticket_get`, `tickets` events | columns, cards |
| `tickets.write` | `ticket_save`, `ticket_move`, `ticket_reorder`, `ticket_delete` | new ticket, edit, drag, delete |
| `epics.read` | `epic_list` | the Epic filter, the card epic badge |
| `agents.read` *(wants)* | agent/role list (read) | assignee chip, role pick |
| `tickets.start` *(wants)* | `ticket_start_next`, ticket start | Start next, Start; **hidden** when not granted |

There is **no** transcript, mail, run, secrets, git or harness access. `TicketsContext` already exposes exactly the needs
rows. The wants rows are where the board must degrade: a new `LinkContext.can(capability)` (an addition with a default of
`true`, so nothing changes in the main app) hides Start and Agent when the grant lacks them.

### 3d. The exact step list (the acceptance NS10 automates)

1. In the full app, open Configuration > Registered (the locked core view, 6s), select `infantry.shell.board.pane`, and
   choose **Export section…**
2. The export resolves the effective subtree and checks 1c. It refuses with its list until NS1-NS2 are in, then writes
   `Board.ihq`: `layout/board.json`, `deps/Infantry_Board.ihq` (copied from the plugins root or the build), and
   `module.json` with `fills[{node: infantry.shell.center, layout}]`, `dependencies`, `daemon.needs`/`wants` and an `app`
   object. It is signed with the export key.
3. Choose **Try in a fresh shell**. This launches `runShell --app <temp>` (installed: the shell launcher) with an empty
   plugins root and a temp `appId`. No in-process second window: a real fresh process is the claim being tested.
4. The fresh shell starts. `daemon.mode` is connect-or-bundle and nothing is configured, so it starts its **bundled**
   daemon on a loopback ephemeral port with its own state dir and seeds the project "Board". It shows the drop box.
5. Drop `Board.ihq`. The locked consent dialog lists: composition "Board" (export key, community), which **fills the
   center**; dependency `Infantry_Board` 1.0.0 (Infantry, core); daemon needs: projects.read, tickets.read/write,
   epics.read; wants: agents.read, tickets.start.
6. Install. The nested package goes in first, then the composition and the `allowOverride` grant for
   `infantry.shell.center` (written by consent). Then a soft restart.
7. The center draws the board from `layout/board.json` through the type registry. Create a ticket, drag it to To do, and
   reopen the shell: it is still there (the bundled daemon persisted it).
8. Acceptance shot: `board-fresh-shell` (new baseline). Plus the main app's board shots at 0 px (the split changed nothing).

---

## 4. Daemon options, decided

### 4a. Decision: **connect-or-bundle**, attach always through a scoped grant, hub later. This is the PM's recommendation, confirmed.

| Option | For | Against | Verdict |
|---|---|---|---|
| (a) bundle the daemon | works offline and alone; it is how the installed app already runs (`runRealDaemonUi`); zero setup | an app cannot show the owner's existing tickets; every app ships the whole daemon (harness spawning, keystore); two daemons on one machine | **yes, as the fallback** |
| (b) connect to an existing daemon | the board app shows the owner's real board; one data layer for many apps (VISION: "the daemon is the data layer") | needs a credential; today the only non-owner credential is the access token, which reaches the whole API | **yes, the first choice when configured, but only with scoped grants** |
| (c) pair via QR | already exists for phones; good for "attach this app/phone to that daemon" | today's link **carries the access token**: wrong for an app | **yes, as one of the ways to obtain a grant** (one-time code, not a token) |
| (d) hub registry | discovery, cross-app identity, marketplace path | a new service, identity and availability problem; nothing needs it for one exported board | **later**; the daemon's grant list (4c) is its seed |

**Resolution order at start** (pure function `DaemonChoice.resolve(app, saved, buildHasDaemon)`, unit-tested):
1. `app.json` `daemon.url` + a stored grant for it (keystore, as `KeystoreSavedServers`) -> connect.
2. A saved server the user picked in Servers -> connect.
3. `mode != connect` and the build has a daemon -> start the bundled one (own state dir, loopback, ephemeral port,
   `StateDirLock`).
4. Otherwise -> the Connect dialog (locked).

If a connect fails, the app **never silently falls back** to the bundled daemon. Otherwise the user would type tickets
into the wrong daemon. It shows switchError with "Use own data instead" as an explicit choice.

The shell **never auto-attaches** to the Infantry install's daemon, even though `owner.token` is readable on the same user
account. Reading it would hand the owner credential to a process running community code.

### 4b. Security model (every attach goes through the owner)

- **Bundled**: the shell core generates and holds that daemon's owner token (as today). Plugin code gets only
  `ShellContext` and its `EndpointAccess`; `DaemonLink` is hidden by the filtered class loader (PLUGIN-ENDPOINTS D2).
  The blast radius is that app's own data.
- **Connect**: the app holds an **app grant token**, which is neither the access token nor the owner token. A grant is
  `{grantId, appId, name, capabilities[], projectIds[] | all, createdBy, createdAt, lastSeen, revoked}`, stored hashed in
  the daemon (new table, one migration).
- **How a grant is obtained** (both end at the same owner approval):
  1. *Request*: the app sends `grant_request {appId, name, needs, wants, signer}` to the daemon URL, unauthenticated,
     rate-limited, held 10 min. Every **owner-connected** client (desktop window, phone) shows a locked dialog
     `infantry.shell.grant` in windowLayer: "Board (signed by X) asks for: read projects, read and write tickets, read
     epics; optional: start tickets. Project: [pick]". Approve / Deny. On approve, the app's pending connection receives
     the token once.
  2. *QR / one-time code*: in Configuration > Devices (locked, `pair`), the owner creates "Pair an app" with a capability
     preset and project. This gives `infantry://grant?url=…&code=<one-time, 10 min, single use>`. The app (or a phone
     running a shell) scans or pastes it and redeems the code for the token. **The token is never in the QR.**
     Today's `infantry://connect` with the access token stays for the owner's own devices only.
- **Enforcement is daemon-side**: `FrameGate` gains a per-capability frame allowlist (the `DEMO_LOCK_ALLOWED` machinery,
  generalised). It is **default-deny**: a frame or event type not classified under any capability is refused to a grant
  connection. **Outgoing events are filtered too**: a grant connection receives only events of its capabilities and
  projects. A guard test fails when a new frame or event type is added without a capability class (the LimitsGuardTest
  pattern).
- **Revocation**: Configuration > Devices lists the grants (name, signer, capabilities, last seen) and offers Revoke, which
  closes its live connections at once.
- **eb5a962f (demo visitor tier)** becomes one preset of this mechanism (`visitor` = read-only capabilities), not a second
  token kind.
- **Honest limit** (same as PLUGIN-ENDPOINTS D2): community code in the JVM can read files the user can read, including
  the Infantry install's `owner.token`. Grants close the *designed* paths and make attach auditable and revocable. They do
  not sandbox a malicious plugin; signature, consent and safe mode remain the boundary.

### 4c. module.json additions (contract)

```json
"daemon": { "needs": ["tickets.read","tickets.write","projects.read","epics.read"], "wants": ["agents.read","tickets.start"], "minContract": "1.139" }
```

- The capability names are the API. They start with the six above and grow by addition only.
- The full capability-to-frame map lives in the daemon (one file, with the guard test) and is published in CONTRACT.md.

---

## 5. Implementation tickets (ready for the PM; weights 1-5, in order)

Prerequisites already merged: L5 (80690a98), S4-S7, PLUGIN-PLATFORMS-1, PLUGIN-ENDPOINTS-1/2.

**Weekend-critical** (the board exported and running in a fresh shell on its bundled daemon). Total weight is 21, which is
a full weekend for the pipeline. NS1-NS3 are serial; NS4/NS5 can run alongside NS2.

| # | Ticket | W | Depends | Done when |
|---|---|---|---|---|
| NS1 | **PB1 type registry** (6t.6): `UiRegistrar.blockType`, module.json `blockTypes` (type, title, fields, takesChildren, needs, parents, max, template), layout primitives as core types, Registered lists types. **Supersedes or absorbs 7a062c28** (BLOCKS-1 P1). Close that ticket in favour of this one. | 3 | - | unit tests; 32/32 0 px |
| NS2 | **Board as types + `Infantry_Board` module split** (3a, 3b): 14 types with render tests, two instance fields, its own Gradle project/jar, `boardIhq` task; infantryAppUi draws the overlay from the types | 4 | NS1 | board shots 4/4 + 32/32 at 0 px; `Infantry_Board.ihq` builds signed |
| NS3 | **Export + `fills[].layout`** (PB6, 1b-1c): `LayoutExport` (pure: subtree -> page JSON + refusal list), template children, `IhqInstaller` accepts `layout/*.json` + one level of `deps/*.ihq`, `fills[].layout` instantiates through the registry, the export key, "Export section…" in Registered | 4 | NS2 | unit tests for export, refusal and install; round-trip test: export board -> install in temp root -> resolved tree equal |
| NS4 | **Shell-only target**: `runShell` and the shell distributable/MSI flavour, `--app <dir>`, `app.json` (2c), per-app plugins root; empty-shell daemon status line | 3 | - | `runShell` shows the drop box; a packaging config test (no jpackage run in tests) |
| NS5 | **Per-app isolation of the bundled daemon**: state dir `~/.infantry/apps/<appId>`, loopback ephemeral port, `StateDirLock` per app, `seedProject`, `DaemonLauncher` behind ServiceLoader | 2 | NS4 | test: two app ids start two daemons with separate state dirs; the Infantry install untouched |
| NS6a | **DaemonChoice** (4a) with the bundle path only; no silent fallback; Servers integration | 1 | NS5 | pure unit tests of the resolution order |
| NS10 | **Acceptance**: "Try in a fresh shell" + the 3d steps automated in the shot harness (export -> fresh root -> drop -> consent -> soft restart -> `board-fresh-shell`); measure size and start-up (2d) | 4 | NS3, NS6a | new baseline shot; measured numbers written into this note |

**Next** (connect mode, needed before anyone attaches an exported app to the owner's daemon):

| # | Ticket | W | Depends |
|---|---|---|---|
| NS7 | **Scoped app grants, daemon**: grant table + migration, capability map + default-deny guard test, FrameGate per-capability gate **including outgoing event filtering**, project scoping, revoke closes connections, contract bump; eb5a962f becomes a preset | 5 | - |
| NS8 | **Attach flow, UI**: `grant_request` + locked `infantry.shell.grant` approval dialog on owner clients; "Pair an app" one-time code QR (`infantry://grant`); grants list + Revoke in Devices | 3 | NS7 |
| NS6b | DaemonChoice connect path: stored grant per URL in keystore, the switchError "use own data" choice | 2 | NS7, NS8 |
| NS9 | **Capability-aware blocks**: module.json `daemon.needs/wants/minContract`, `LinkContext.can(...)`, consent lists them, board hides Start/Agent without `tickets.start`/`agents.read` | 2 | NS7 |

**Later** (not needed for the north-star proof):
- NS-L1 thin shell flavour (no daemon jar, trimmed jlink): weight 3.
- NS-L2 `.ihq` upgrade/uninstall (versions, pending-replace for Windows file locks). Exported apps need it before anyone
  ships one: weight 3.
- NS-L3 wasm/Android starter shell compiles a layout.json + modules in (6t.5): weight 4.
- NS-L4 more sections as types, one per ticket (Run, Git, Notes, Changes, chat last).
- NS-L5 daemon-side plugin modules (data the daemon does not have today, so that, for example, an email app does not
  invent a backend in every plugin): its own design ticket.
- NS-L6 (d) the hub: its own design ticket.
- PB2-PB5 (move/add in the page builder) are independent of export and proceed on their own track.

Routing (D2): NS2, NS3, NS7 at weight 4-5 go to opus; NS1, NS4, NS8, NS10 at 3-4 per weight; NS5, NS6a, NS6b, NS9 at
1-2 go to haiku.

---

## 6. Cost and risk, honestly

1. **"Any section exports" is not true today and will not be soon.** Zero sections are exportable now. Each one costs a
   module split plus its types and render tests (the board: weight 4). Chat, the header and the side panels read the most
   context and are the most expensive; they come last, if ever.
2. **The locked core is not changeable and stays in every app**: skeleton (window, images, dock), connect / servers /
   switchError / serverUpdate, the Configuration frame and its eight trust sections, the resolver, primitives, plugin host,
   installer, the daemon client and `ShellContext`. An exported app cannot remove Configuration > Plugins, and that is
   on purpose (recoverability).
3. **The shell is not daemon-agnostic.** `ShellContext` is the Infantry daemon's model. A plugin needing other data uses
   its own backend (PLUGIN-ENDPOINTS) or waits for daemon-side modules (NS-L5). A "video player" app is possible. An
   "email" app needs a backend that the daemon does not provide.
4. **Size**: a bundled app is about the full app's size (~230 MB, estimated from the 234 MB measured). Shipping a
   200+ MB agent daemon inside a board app is heavy. The thin flavour helps connect-only apps, not bundled ones.
5. **A bundled daemon is the whole daemon**: harness spawning, keystore, sessions. That is more attack surface than a
   board needs. It binds loopback only. LAN exposure, for phone pairing to a bundled app, is an explicit owner toggle.
6. **Outgoing event filtering (NS7) is invasive.** The daemon broadcasts state today. One unclassified event type leaks
   data to a scoped client. Default-deny plus the guard test is the control. Expect NS7 to find more event types than
   anyone remembers.
7. **The in-process JVM limit** (4b) is unchanged. Grants are a designed boundary, not a sandbox.
8. **Two or more daemons and JVMs on one machine**: RAM pressure on this host (knowledge note). Each exported app is its
   own process by design (a "separate app/window").
9. **Pixel gates cover the shipped tree and one acceptance shot.** Other users' compositions are untested by definition.
10. **No upgrade path** for `.ihq` yet (S5 decision 3). An exported app that cannot update is a dead end; NS-L2 must land
    before anyone outside ships one.
11. **The Windows desktop is the only drop target.** wasm/Android get compositions only at compile time (NS-L3).

## 7. Decisions taken here, and the ones expensive to reverse

Taken (no owner wait):
- An export is a composition: layout + nested signed code packages + daemon needs. Code is never carved out at run time.
- The exported section is `board.pane` filling `center`, not the modal.
- The template marker goes in the page format.
- connect-or-bundle, with no silent fallback and no auto-attach to the Infantry daemon.
- Attaching uses scoped grants only. QR carries a one-time code, never a token.
- The visitor tier (eb5a962f) is a grant preset.
- The shell-only build is a flavour of infantryUi with the daemon kept in; the thin flavour comes later.
- The hub (d) is later.

**Expensive to reverse:**
- (a) Capability names and their frame map: they become the contract every exported app declares against.
- (b) The composition `.ihq` layout (`layout/`, `deps/`, one level of nesting, tier per nested signer).
- (c) `app.json`'s `appId` semantics: it names the state dir and the MSI upgrade identity, so a change orphans user data
  and breaks upgrades.
- (d) Board type names and the `template` marker, once a layout.json names them.
- (e) The grant token model (per app, per project, revocable) once third-party apps hold grants.
- (f) The export key's trust level (community publisher, never core).

## 8. Open questions (decided as above; the PM may overrule)

1. Grant scope default: one project picked at approval (decided) or all projects?
2. Should 7a062c28 be closed as superseded by NS1, or kept as the JSON-tree track? Recommendation: close it. 6t
   replaced its model.
3. Should eb5a962f wait for NS7, or ship its own weaker token now and migrate later? Recommendation: wait. Two token
   kinds is the expensive mistake.
4. Is "Try in a fresh shell" also wanted in the installed app (it launches a second process from the install dir), or
   dev only for the weekend? Recommendation: both, since it is the same launcher. **Decided yes; done in NS10**
   (`FreshShell`: Registered > "Try in a fresh shell" exports, writes `<stateDir>/fresh-shells/<appId>` with the
   `.ihq` beside an empty plugins root, and starts `java.home`'s javaw with the shell-only classpath and `--app`).
