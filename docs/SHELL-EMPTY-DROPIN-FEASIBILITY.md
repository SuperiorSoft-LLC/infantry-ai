# SHELL-EMPTY-DROPIN: Windows empty shell + a drop box that loads the whole app UI (feasibility, read-only)

Ticket c6290fde. Base: `feature-agenthq-mvp` @ 25d1980 (L15 step 1). Scope: the **Windows desktop JVM only**. Nothing was
built or changed; every figure below is from reading the tree (`wc -l`, the BLOCKS-NEXT-SPLITS survey, LICENSE-0).

Vocabulary: *plugin / subplugin* are relative (a parent is a plugin to its children; the app root is the top plugin).
*Components / subcomponents* are the primitives (text, form controls, data) that plugins are drawn with.

**Verdict.** Feasible on Windows JVM, and the code already has most of the right shape (folder scan, trust tiers, node
overrides that keep a node's id and place, a 0 px shot gate that loads modules through the real loader). It is **not** a
configuration change: today every pane and dialog is compiled into `agenthqUiCore` (about 34,100 lines of commonMain)
and plugin jars compile *against* it. The "empty shell" is real only when the app UI is moved into a separate Gradle
module that the shell does **not** depend on, so that the shell compiling on its own *is* the leak test. Estimated
**7 steps (S0-S6), total weight 22** (section 4), with three decisions that are expensive to reverse (section 3d).

---

## 1. The minimal non-removable Windows shell today

### 1a. What `CoreShell.root` draws (`agenthqUiCore/.../ui/shell/blocks/CoreShell.kt`)

```
infantry.shell.window            (locked, GroupBlock)
  infantry.shell.images          (locked, ImageViewerHost)
    infantry.shell.dock          (locked, Stack, FillMax)
      infantry.shell.frame       (locked, Column, surface)
        ShellHeader              (open)   header, lead, overflow
        infantry.shell.body      (locked, Stack, weight 1, measure)
          infantry.shell.panes   (locked, Row, FillMax)
            ShellLeftPanel, ShellCenter (chat), ShellRightPanel   (open)
          ShellCompactDrawer     (open)
        CoreStatusBar            (open)   footer
      ShellAssistant, ShellToast, ShellBoard, ShellDiff,
      ShellSound, ShellVoice, ShellQuestionPill               (open overlays)
  infantry.shell.windowLayer     (locked, GroupBlock)
    ShellConfig (locked: config), ShellServerUpdate (locked), ShellRoles (open),
    ShellServers (locked), ShellSwitchError (locked), ShellConnect (locked)
```

Plus `CoreShell.tabs` = `ShellGit`, `ShellRun` (core tab trees, L14/L15), and `App` (App.kt, 934 lines; the `App`
composable is 186) which owns the window state, the `Controller`, the effects, and wires `CoreShell.root`.

### 1b. The locked set, split by what it really is

| Locked node | Kind | Keep in the shell? |
|---|---|---|
| window, images, dock, frame, body, panes, windowLayer | skeleton (layout only, a few lines each) | **yes**: this *is* the empty shell |
| connect (token entry), servers, switchError | getting to a daemon at all | **yes**: without them the shell cannot reach anything |
| serverUpdate | approving an update | **yes**: trust decision |
| config (`ConfigDialog`, 239-line composable in a 1,060-line file) | the whole Settings dialog | **only its trust sections** (Plugins, Secrets, Pair, Updates, About, Logs). Theme, blur, markdown, pacing and the rest are app UI that happen to be locked because the node is one leaf |

So the minimal shell is: the skeleton nodes, the five small dialogs, a **cut-down** Settings (the sections where a token is
typed, an update approved, a plugin enabled or disabled, a secret touched), the drop box (new), the theme/tokens and the
component primitives (Button, text, fields; they are the API the dropped plugin draws with), the block layout and
resolver, the plugin host (`DesktopPlugins`, `agenthqPlugins`), and the daemon connection.

### 1c. What must move out of core into the dropped "app UI" plugin

Everything open in 1a, and every pane those nodes call. Approximate sizes (file lines; composable lines in brackets):

| Moves out | Files (lines) |
|---|---|
| Header, left/right panels, center/chat, compact drawer, status bar, 7 overlays, Roles window | `shell/blocks/Shell*.kt` + `StatusBarBlocks.kt` (about 2,000) and the composables they call: chat/transcript, `QuestionWindow.kt` 306, `SwitchAgentPicker`, `ExecutorPicker`, toasts, diff, assistant |
| Board | `Board.kt` 781 (BoardPane 131, TicketEditForm 123, TicketCard 96) |
| Roles | `RoleManager.kt` 336 (RoleForm 133) |
| The 17 core modules' panes (`plugins/core/*` are 17-34-line manifests; the panes are in `agenthqUiCore`) | `RunPane.kt` 559, `ProjectGitPane.kt` 564, `ProjectChangesPanel.kt` 577, `MemoryPane.kt` 280, `SkillsPane.kt` 178, `PromptsPane.kt` 163 + `PromptCard.kt` 176, Epics, Pods, Profiles, Triggers, Stats, Usage, Review, Attention, Agents (`Sidebar`, `AgentRow`) |
| The non-trust half of Settings | most of `ConfigDialog.kt` 1,060 |
| The `ShellGit` / `ShellRun` tab trees | `ShellGit.kt`, `ShellRun.kt` (they belong with Git/Run) |

Order of magnitude: about **25-28k of the ~34k lines** of `agenthqUiCore` commonMain go to the plugin; the shell keeps
roughly 6-9k (layout, resolver, primitives, theme, plugin host, connect/servers/update, trust-Settings, App state). That
matches LICENSE-0's split (92.3 % "core", of which most UI is core only because nothing has been cut yet).

### 1d. The coverage-gap list, in the order it matters for the drop-in

1. **Locked containers refuse injection** (`Resolver.kt:108`): frame, body, panes, dock and windowLayer are locked, so a
   dropped plugin cannot add a header, panes or overlays into them today. Fix: the shell keeps every open child as an
   **empty placeholder block with the same id and `place`** (header, leftPanel, center, rightPanel, compactDrawer,
   footer, each overlay, roles); the plugin `replace`s them. `OverrideBlock` already keeps the node's id and place, so
   the parent places the replacement exactly as before; this is what makes pixel identity achievable. Weight-bearing.
2. **`App` state is not an API.** The open nodes read the `Controller`, window state and composition locals directly.
   The app UI plugin needs them; today plugins get only gated `PluginServices` (LICENSE-0: API 1.0 %). Fix: a
   `ShellContext` interface (controller state flows, actions, locals) that the shell provides and the app UI plugin
   consumes. This is the largest real gap and the one that "leaks" most.
3. **`ConfigDialog` 239, locked as one leaf.** Split into a locked trust kernel (Plugins, Secrets, Pair, Updates, About,
   Logs) and `config.<entry>` children the plugin supplies (BLOCKS-NEXT-SPLITS #1). Without it the "empty" shell still
   contains a quarter of the app's settings UI.
4. **Overlays not covered by the 32 default shots** (assistant, board, diff, roles, ticket edit, config sections): they
   need their opt-in 0 px shots (`-PtabShotsExtra=...`, as L10/L11/L15) *before* they move, or the move is unproven.
5. **`TicketEditForm` 123** (inside `board.pane`, no node) and **`RoleForm` 133** (inside `roles`): move as a whole with
   Board / Roles; splitting them is not needed for the drop-in, only for later subplugins.
6. **Module tabs with no tree**: `MemoryPane` 152, `ProjectChangesPanel` 115, `SkillsPane`, `PromptsPane`. For the drop-in
   they move as blobs; their module-owned trees (BLOCKS-NEXT-SPLITS section 2) are a later concern.
7. **Core tab trees in `CoreShell.tabs`** (`ShellGit`, `ShellRun`): must become contributions of the plugin, not a
   hard-coded list in the shell.
8. **Per-item templates** (toast card, `TicketCard`, `AgentRow`): not needed for the drop-in; noted so nobody blocks on it.

---

## 2. Packaging on Windows JVM

### 2a. How modules load today

- `PluginFolders.root`: `AGENTHQ_PLUGINS_ROOT`, else the `agenthq.plugins.root` property (dev tasks: repo `plugins/`),
  else `<state dir>/plugins` (installed app: `%USERPROFILE%\.agenthq\plugins`).
- `<root>/modules.json` (`ModulesConfig`): the folders to scan with their **tier** (`core`, `community`), the owner's
  per-module `enabled`, `allow`/`deny` services, `allowOverride`, `allowInject`, and `demoLock.allowRoutes`.
- `PluginFolders.scan`: every subfolder with a `module.json` (`ModuleManifest`: name, version, apiVersion, author,
  license, dependencies, `entry`, `jar`, routes, events, services, tabs, preferences, interceptors, shell, inject).
- `ModuleResolver.resolve`: trust rules, dependency graph (Kahn order, core first), shell overrides and injections.
- `DesktopPlugins.load`: core modules come from `CoreModules.all` (compiled in); a community module's jar is loaded with
  a `URLClassLoader` whose parent is the app's loader (it must sit inside its folder), its entry class is a `UiPlugin`,
  `registerModule` binds its contributions into a `TabFactory`. Built **once** (`JvmPlatformHost.tabFactory by lazy`).
  Enable/disable from Settings writes `modules.json` and "applies at the next start".
- A plugin jar holds only its own classes and is `compileOnly` against `agenthqUiCore` + Compose (Acme_Hello's
  `build.gradle.kts`). That is fine for the shell API, and is exactly why moving the app UI out is a module split.

### 2b. The single droppable file

A **`.ihq` file = a zip** (a jar is a zip; a separate extension stops Windows from offering to "open" it with Java):

```
App_UI.ihq
  module.json            the manifest as today (entry, jar, shell replaces, tabs, apiVersion, version)
  lib/App_UI.jar         the code
  lib/*.jar              optional private dependencies (one classloader per module)
  META-INF/MANIFEST.MF + *.SF/*.RSA   jarsigner signature over every entry (section 3)
```

Install = verify, then unzip into `<root>/<tier folder>/<name>/` (atomic: unzip to `_incoming-<name>` then rename, so the
`_` prefix keeps a half-written copy invisible to `scan`), then add its `modules.json` entry. The loader is unchanged.

### 2c. Where the drop box lives

In the empty shell, the drop box is the **placeholder content of `infantry.shell.center`** (the open slot the app UI later
replaces), so it disappears by the same mechanism that restores the UI. The same component appears in Settings > Plugins
(trust kernel) once an app UI is loaded. It accepts a dropped file through the AWT `DataFlavor.javaFileListFlavor` path
`JvmPlatformHost` already uses for attachments, plus a "Choose file" button.

### 2d. Loading: restart vs. live

| Option | What it takes | Trade-off |
|---|---|---|
| **A. Soft restart (recommended first)** | after install, the shell re-runs `DesktopPlugins.load` and re-creates the window composition with the new `TabFactory` (no JVM restart) | Simple; the resolver already resolves "once per module set". Window state is rebuilt, which the empty shell has none of. |
| B. Full JVM restart | relaunch the exe | Simplest and most robust; worst UX; the installed launcher must support relaunch. Fallback when A fails. |
| C. Live add/remove | `TabFactory` becomes observable state; remove = close the `URLClassLoader` and drop every reference | Adding is easy; **removing is not** (Compose keeps lambdas, coroutines and the class graph alive; a leaked loader holds the jar open, and Windows then refuses to delete or replace it). Not worth it for this test. |

Windows-specific: an open jar is file-locked. Replacing an installed version must go to a new folder name or happen at
start before the loader opens it ("pending-replace" marker); the soft restart must close the old loader first.

---

## 3. Trust and safety: dropping code is running code

### 3a. What exists

- **Tier by folder, never by the manifest** (`Tier` doc). Acme_Hello's own `"tier": "core"` is ignored.
- Community: needs author + license, off until `modules.json` enables it, services by `allow`, may replace a core
  contribution or shell node only if listed in `allowOverride`, inject only per `allowInject`.
- **Locked nodes** refuse every override and injection, whatever the tier.
- **Demo lock** (`AGENTHQ_DEMO_LOCK`): no community module loads; `modules.json` is never written; daemon side, no
  interactive run, no raw shell keystrokes, no secrets, every non-allowlisted frame refused (RunManager, KeystoreHandler,
  1.136.0 FrameGate).
- `DesktopPlugins` says it plainly: *a trust label, not a sandbox*; a loaded jar has the app's full JVM access.

### 3b. What the drop-in needs on top

1. **Consent step, in the shell (locked)**: before unzipping, show name, author, version, signer, the services and routes
   it asks for, and **every shell node it would replace**. An app UI plugin replaces nearly every open node: the dialog
   must say "this file replaces the whole interface" in those words.
2. **Signature**: jarsigner-verify every entry (`JarFile` with verify = true, reject unsigned or partly signed entries).
   An Infantry-signed file may install to the **core** folder; anything else installs to **community** only, and needs
   the owner's explicit `allowOverride` grant for each placeholder it replaces (the consent step writes it). The key is
   pinned in the shell, not read from the file.
3. **Placeholders are not locked, the kernel is.** The app UI plugin may replace header/panes/overlays; it may never
   replace connect, servers, serverUpdate, switchError or the trust half of config. This is what lets a user always get
   back to Settings > Plugins and disable a bad drop. Add a **safe-mode start** (a key held at start, or a
   `--no-plugins` flag) that loads the shell only.
4. **Demo interaction**: under the demo lock the drop box is shown **disabled** (installing would write to the plugins
   folder, which the lock forbids). A demo build that must show the app UI ships it pre-installed in the **core** folder
   (signed), so the demo looks normal while terminal and secrets stay refused daemon-side, unchanged. The UI plugin cannot
   lift those: they are enforced by the daemon, not the UI.

### 3c. What stays unsolved, honestly

No in-JVM sandbox is possible (the Java SecurityManager is gone). A signed core plugin is trusted fully; a community one is
trusted fully once consented. Mitigations are provenance (signature), consent and recoverability (locked kernel, safe
mode), not isolation. Out-of-process UI plugins would be isolation, at a cost far outside this test.

### 3d. Decisions expensive to reverse

- **Placeholder node ids and their `place`** become plugin API the moment one third-party app UI ships against them (the
  L5 "never rename a node id" rule, applied to the empty slots).
- **The `ShellContext` interface** (gap 2): whatever the app UI plugin is allowed to see of `Controller`/state becomes a
  compatibility promise under `apiVersion`; too wide is a leak forever, too narrow forces a v2.
- **Signing key and tier mapping**: once files are signed and installed into tiers, changing the key or the rule means
  re-signing and re-consent for every user.

---

## 4. Minimal path (S-steps, weights 1-5)

| Step | What | Weight | Proof |
|---|---|---|---|
| **S0** | Opt-in 0 px shots for every overlay and dialog the move touches that the 32 default shots miss (board open, ticket edit, roles, assistant, diff, config sections), as L10/L11/L15 did | 2 | `diffTabShots` 0 px, extras green |
| **S1** | Placeholders: the shell's open children become empty blocks with unchanged id and `place`; the current UI re-enters through `shell(replace)` from a **compiled-in** `Infantry_AppUi` core module | 3 | 32 shots + extras 0 px (nothing moved yet, only the wiring) |
| **S2** | `ShellContext`: the open nodes read state and actions through an interface the shell provides, not `App` internals | 4 | 0 px; a test that a stub `ShellContext` renders the placeholders |
| **S3** | Split `ConfigDialog`: locked trust kernel in the shell, other sections as `config.<entry>` from `Infantry_AppUi` | 3 | 0 px incl. config shots |
| **S4** | **The leak test**: move `Infantry_AppUi` and every pane of section 1c into a new Gradle module `agenthqAppUi`; the shell (`agentHqUi` desktop) no longer depends on it. Every compile error in the shell is a leak, fixed by moving the code or widening `ShellContext` | 5 | shell compiles alone; shell with the module on the classpath = 0 px |
| **S5** | Packaging: `agenthqAppUi` builds `App_UI.ihq` (zip of module.json + jars, jarsigned); the installer unzips atomically into the tier folder; the loader loads it from disk like Acme_Hello | 2 | `DesktopPluginsJarTest`-style test of install + load + refusal of an unsigned file |
| **S6** | Drop box in the empty `center` placeholder + consent dialog + soft restart + safe mode + demo-lock disabled state | 3 | empty-shell shot (new baseline: drop box); then **install `App_UI.ihq` into a temp plugins root and run the 32-shot `diffTabShots` against the current baseline: 0 px** |
| | **Total** | **22** | |

`TabShotsMain` already builds its modules through `DesktopPlugins.load` on a temp copy of the plugins root, so S6's final
gate is the existing gate with one more file in that root, not a new harness.

Order rationale: S0 first because nothing may move unproven; S1 before S2 so the wiring change is pixel-tested alone; S4 is
the one that finds the gaps nobody listed, so it comes only once the obvious ones (S1-S3) are closed.

---

## 5. Risks, and what we would learn ("water in a tire")

S4 is the tire in the water: compiling the shell without the app UI shows every hidden dependency as a bubble. Expected
bubbles and risks:

- **Composition locals and singletons** read deep inside panes (theme, `LocalNewAgentDraft`, toast controller, sound,
  voice): each becomes a `ShellContext` member or moves wholesale. Risk: `ShellContext` grows into "all of `Controller`".
- **Shared primitives** used by both shell and plugin (Button, SearchableSelect, ModalBlur, markdown engine): they stay in
  the shell as component API, which freezes their signatures under `apiVersion`.
- **Two copies of a class**: if the plugin jar bundles a library the shell also has (Compose, kotlinx), the parent-first
  `URLClassLoader` hides it, but a version skew surfaces as `LinkageError` at load, not at compile. The `.ihq` build must
  keep those `compileOnly`.
- **Pixel identity through a classloader**: fonts and resources loaded via the plugin's loader (`painterResource`, icons)
  can resolve differently than from the app jar; one icon not found = shots that differ by a few hundred pixels.
- **Startup cost**: loading a 25k-line UI jar from disk instead of the app jar; small on JVM, but measure it in S6.
- **Windows file locks** on replace/uninstall (section 2d); the soft restart must close the loader.
- **Recoverability**: a broken app UI leaves the user in the empty shell; that is acceptable only if the trust kernel and
  safe mode work. Test it explicitly (drop a deliberately failing `.ihq`).
- **What we learn either way**: the true size of the shell API (the `ShellContext` surface after S4 is the honest
  version of LICENSE-0's 1.0 % API figure), and whether "the app is a plugin of the shell" holds without the core
  quietly re-growing.

---

## 6. Wasm and Android: the starter-repo model (no design)

For wasm and Android nothing is loaded at run time: a **starter/shell repository** gives users a ready work environment.
It would contain the shell module (the same empty shell, as a Gradle dependency), a plugin template (the `_template`
folder with `module.json`, a `UiPlugin` entry and its `build.gradle.kts`), and a build config that lists the plugins to
include and compiles them into one app per target. The user adds the plugins they want, builds, and gets their own app;
same plugins, same behaviour after compilation, sorted out later.
