# EMPTY SHELL: core modules out of the shell build

Ticket 73b920ff. Design only: no code changed, nothing built. Base: `feature-agenthq-mvp` @ 758149a5 (after 4dc8b33f).
Builds on: SHELL-EMPTY-DROPIN-FEASIBILITY.md (S0-S7), SHELL-LEAKS.md (S4a-S4d), NORTH-STAR-DESIGN.md (NS1-NS10),
BLOCKS-PERF-CHECK.md, plugins/GUIDE.md, and LICENSE-0 (memory note `license-0-report-2026-10-26`).
Related board work: SETTINGS-TAB-1 (ef796432, in progress) touches the Notes/Git/Triggers/Profiles/Run registrations
and must merge before steps E3/E4 below. NS6b-NS9 (scoped grants) do not overlap: they are daemon-side and unchanged
by this note. No other open ticket touches this.

**Principle (owner): a module that has to be compiled in is not a module.**

## Summary for the owner (one page)

```
 1. The shell (agenthqUiCore + agenthqPlugins + the daemon link) already starts with ZERO modules: it shows the drop box
    and Configuration. Evidence: DropInUiTest, the `empty` 0 px baselines (shell-empty-1400), runShell (NS4), FreshShell (NS10).
 2. What is wrong is how the full app gets its 19 modules: one hand-written list (CoreModules.kt) inside one Gradle module
    (agenthqAppUi) that the desktop app puts on its classpath. That makes them compiled in, on Windows too.
 3. Desktop target: every core module is its own Gradle project and its own jar, installed as
    <install>/modules/core/<Name>/{module.json, lib/<Name>.jar}. The loader finds them by scanning folders, the same way
    it finds community modules. No list names them.
 4. "Core tier" = in a core folder AND signed by the Infantry key, which is pinned in the shell and checked when the
    module loads. The tier comes from where the module sits plus who signed it, never from the file's own claim and never
    from a compiled-in list.
 5. "Locked core" = it cannot be uninstalled from the UI (the installer owns those files) but it can be disabled, unless
    the product marks it `required`. The shell stays safe with no modules at all, so nothing needs to be locked to keep
    the app safe.
 6. Web and Android cannot load code at run time. A Gradle task generates the module list from plugins/core/*/module.json
    plus a product.json (which modules this product ships). Nobody edits that list by hand. The same product.json tells
    the desktop installer which jars to put in the folder.
 7. Rule: modules never link to each other's classes. Shared widgets go into the kit (the shell). Shared state goes
    through ShellContext. Today there are 9 such edges, all small (section 4).
 8. The coreController leak (11 tabs + the app UI) does NOT block the jar split: core jars load unfiltered. It is closed
    later, one module at a time, behind a ratchet test that only lets the count go down.
 9. Order: E1 generated list -> E2 one module (Pods) as a real jar from disk -> E3 the leaf modules -> E4 cut the edges,
    the rest -> E5 the app UI itself -> E6 locked-core rules. Every step 0 px. First slice: E1 (weight 2).
10. Risk: start-up. Plugin load already went from 226 to 436 ms. Nineteen class loaders plus signature checks will add
    more. E2 measures it and sets a budget (<= 500 ms). If a step goes over budget, a verified-jar cache is the fix.
```

---

## 1. What the empty shell contains, and does it start with zero modules?

**Measured from the tree: yes, it starts with zero modules today.** `CompiledModules.all` is empty when no `ModuleSource`
is on the classpath. `CoreShell.slots` then draw nothing. The centre slot draws the drop box, and the trust-kernel
Config still opens. Proof already in the repo:
- `agentHqUi/.../DropInUiTest` "the empty shell draws the drop box, the app UI does not" (`TabFactory(emptyList())`)
- baselines `agentHqUi/tab-shots-baseline/empty-shell/shell-empty-1400.png` and `shell-empty-config-1400.png` (S4d,
  0 px base vs head)
- `:agentHqUi:runShell` (NS4) and "Try in a fresh shell" (NS10) launch the real window on a classpath without
  agenthqAppUi/Infantry_Board

I did not re-run them: none is a one-command check without a full Compose build. The command is
`gradlew.bat :superiorSoftLibrary:agentHqUi:test --tests "*DropInUiTest*"`.

**So the shell is empty at run time but not in its source.** These pieces are still in it:

| Belongs in the shell (keep) | Not shell, still in agenthqUiCore (moves out) |
|---|---|
| Frame: `CoreShell` skeleton, locked nodes, the 15 empty slots, window layer | `EpicsPane` 192, `PromptsPane` 163, `SkillsPane` 178, `StatsPane` 437, `UsagePane` 241 (= **1,211 lines** of 5 modules' panes; their modules are 30-line manifests) |
| Loader: `DesktopPlugins`, `PluginFolders`, `ModuleResolver`, `DependencyGraph`, `PluginClassLoader`, `IhqInstaller`, drop box, consent, soft restart, safe mode | `UsagePacingSection` 269 (a Usage setting in the core Config) |
| Tiers/permissions: `Tier`, `ServiceRegistry`, `RouteGate`, signer pinning, demo lock | `TitleBar` 466 and `ProjectPicker` 442: app chrome that the header fill uses (S4d "hole") |
| Daemon link: `Controller`, `DaemonLink`, `WireProtocol`, `Model`/`AppState`, connect/servers/switchError/serverUpdate | the `agentsList` lambda on `TabContext` (Infantry_Agents is a manifest; the list is supplied by the app) |
| Theme and kit: `Palette`, tokens, controls, `AhqIcons`, `AhqRoleIcons`, markdown engine | |
| Layout engine: `Block`, `Resolver`, registry, overrides, injectors, `ShellContext` + `CoreShellContext` | |
| Trust-kernel Config: Plugins/Modules, Secrets, Pair, Updates, About, Logs | |
| Bundled daemon (connect-or-bundle; a thin flavour without it is NS-L1) | |

**The empty state, defined:** the drop box in `infantry.shell.center`, the Configuration button (top right),
connect/servers when no daemon is reachable, and (NS4 2b) a one-line daemon status. This is the current
`shell-empty-1400` shot. It is a contract: a product with zero modules enabled must look exactly like this.

## 2. Desktop: core modules as jars in folders

### 2a. Layout on disk

```
<install>\app\resources\modules\          (bundled root: written by the MSI, replaced on upgrade)
    core\Infantry_Pods\module.json         entry + jar filled in by the build
    core\Infantry_Pods\lib\Infantry_Pods.jar  (jarsigned)
    core\Infantry_AppUi\...
%USERPROFILE%\.agenthq\plugins\           (user root, as today)
    core\<Name>\...                        signed core .ihq dropped by the owner (drop box)
    community\<Name>\...
    modules.json                           owner's enabled/allow/deny, unchanged
```

- `PluginFolders` gains a second root, the **bundled root** (`compose.application.resources.dir` + `/modules`; in dev,
  `build/modules` of the product). It scans `core` only. The user root works exactly as it does today.
- Name clash rule: the bundled module wins unless the user root holds the same name from the **same signer** with a
  **higher version**. That gives us the upgrade path for a core module dropped as `.ihq`, which IHQ-PACKAGE says is
  missing.
- **Entry contract:** each core module ships a 3-line `ModuleSource` class in its own folder (for example
  `class PodsSource : ModuleSource { override fun modules() = listOf(InfantryPods) }`), named by `module.json`'s
  `entry`. `DesktopPlugins.bundles()` already loads a core-tier folder whose entry is a `ModuleSource` and checks that
  the bundle names itself. A per-module jar is a bundle of one, so **the loader needs no new load path**, only the
  second root and the signature check below.
- Each jar gets its own `PluginClassLoader` (unfiltered for core, as today). The `agenthqAppUi` runtime dependency of
  the desktop app goes away, and so does `isShellJar`: the desktop app becomes the shell plus a modules folder, and
  `runShell` becomes "the app with an empty modules folder".

### 2b. How "core tier" stays a trust level without compile-time membership

| Option | How | For | Against |
|---|---|---|---|
| A. Location only (today's rule) | core folder = core | free, already built | `perUserInstall = true`: the install dir and the user root are both user-writable. Copying a folder into `core\` makes it core with no checks. |
| **B. Location + pinned signature (recommended)** | core folder AND every jar entry verifies against the Infantry certificate compiled into the shell (`agenthqPlugins`), checked at load | one trust root, no module list, works for bundled and dropped modules alike, reuses `IhqInstaller`'s verification | verification costs time at start (measured in E2); the key becomes expensive to rotate |
| C. Manifest hash | the shell (or a ledger file) holds SHA-256 of each core jar | fast check | in the shell it is a compiled-in list again (every module update needs a shell rebuild); in a ledger it lives in the same writable folder as the jars, so it proves nothing |

**Recommendation: B.** The trust root is a key, not a list, so the shell names no module. A core folder whose jar does not
verify is **refused, not demoted**. Silently making it community would change its permissions without telling anyone.
Dev runs (repo `plugins/` root, `agenthq.plugins.root` set) skip the signature through an explicit
`agenthq.plugins.devTrust=true` property. The packaged launcher never sets it, and a test asserts that.
`trusted-keys/` (InstallSettings) stays for *additional* signers the owner adds. The Infantry key itself is pinned in
the binary, so a file dropped into `trusted-keys/` cannot replace it.

What B does not do: it does not stop a local attacker who can already write to `%LOCALAPPDATA%`. That attacker can edit
the shell's own jars. B is provenance against mistakes and social engineering, the same honest limit as
SHELL-EMPTY-DROPIN 3c.

### 2c. "Locked core"

- **Uninstall:** never from the UI for a bundled module (the MSI owns those files; `IhqInstaller.uninstall` already
  refuses core). A user-root core module can be removed in safe mode, which reveals the bundled version again.
- **Disable:** allowed (today's `DependencyGraph` rules, dependants cascade), **unless** the product's `product.json`
  lists it in `required`. For the Infantry product that is `Infantry_AppUi` and its dependencies, so the owner can never
  disable the app's UI by accident. Recovery does not depend on locking: the trust kernel and safe mode are in the shell.
- **Upgrade:** by the installer (bundled), or by a newer signed `.ihq` into the user root (2a).

## 3. Web (wasm) and Android: the product build

Neither can load code at run time (wasm: no dynamic linking in Kotlin/Wasm; Android: possible but restricted, see below).
So the module set is composed **at build time** from data. Nobody types the list.

- **`product.json`** (one per product, in the product's Gradle project, for example
  `superiorSoftLibrary/agentHqWeb/product.json`):
  `{ "modules": "all-core" | ["Infantry_AppUi", ...], "required": ["Infantry_AppUi"], "community": [] }`.
  The default is `all-core`. The desktop installer reads the **same** file to decide which jars go into
  `app\resources\modules\core`, so one manifest drives all three platforms.
- **`generateModuleList`** (a convention script, `gradle/product.gradle.kts`): scans `plugins/core/*/module.json`,
  filters by `product.json`, orders the modules exactly as `ModuleResolver` would (Kahn over `dependencies` + `sequence`,
  ties by name), and writes `build/generated/modules/GeneratedModules.kt`:
  `object CoreModules { val all = listOf(PodsSource(), ...).flatMap { it.modules() }; val factory by lazy { TabFactory(all) } }`.
  It keeps the name and API of today's `CoreModules`, so the ~20 UI tests that use `CoreModules.factory` do not change.
  It also adds `implementation(project(":plugins:core:<Name>"))` for each chosen module.
- `settings.gradle.kts` includes every `plugins/core/*` folder that has a `build.gradle.kts`, as it already does for
  `plugins/community`. Each module's build script is one line, `apply(from = "gradle/core-module.gradle.kts")`: the
  Infantry_Board pattern, written once.
- **The Android srcDir workaround from 4dc8b33f goes away.** It existed because one project compiled
  many folders as srcDirs and `compileAndroidMain` ignored the exclude. When each module is its own project with
  `kotlin.srcDir("src")`, there is nothing to exclude.
- Guard: a unit test regenerates the list and compares it with the committed product's expectation (module names and
  order), so a module folder added without a `module.json` `entry` fails the build, not the app.

**What Android can do dynamically later, in order of value:**
1. **Compositions, not code (now, NS3):** `layout.json` pages (`fills[].layout`) are data. Any block type compiled into
   the product can be rearranged, exported and dropped at run time on every platform. That covers most "new app" cases.
2. **Sideloaded builds: `DexClassLoader`** from app-private, read-only files (Android 14 requires read-only) of a signed
   `.ihq` carrying a dex jar. This is technically sound, but Play policy forbids it for Play-distributed builds. Only the
   Tailscale/sideload APK could use it.
3. **Play Feature Delivery:** dynamic feature modules. This is still build-time composition, only downloaded later. It
   is useful for size, not for third-party modules.

Wasm: compositions only (1). Code loading waits on Kotlin/Wasm component-model support. Not planned.

## 4. Cross-module dependencies and the coreController leak (S4)

### 4a. Class links between core modules today (from a scan of plugins/core, false positives removed)

| From -> to | Symbols | Fix |
|---|---|---|
| AppUi -> all 18 | `InfantryX` values in `CoreModules.all` | disappears (E1: generated) |
| AppUi -> Git, Run, Notes, Changes | `ShellGit`, `ShellRun`, `ShellNotes`, `ShellChanges` registered as AppUi's `tabTrees` | each module registers its own tab tree (`AgentHqModule.tabTrees` already exists) |
| AppUi -> Roles | `ShellRoles` registered as AppUi's fill of `infantry.shell.roles` | Infantry_Roles fills its own slot |
| AppUi -> Board (Gradle `api`) | `BoardModal*`, `BoardBody`, `BoardOverlay`, `LocalBoardOverlay` (the overlay frame `ShellBoard`) | Infantry_Board fills `infantry.shell.board` itself (move `ShellBoard.kt` to it) |
| AppUi, Board -> Board | `ReportFlagChip` (chat, sidebar, card) | kit |
| Attention -> AppUi | `WatchFlagChip` | kit |
| Attention -> AppUi | `QuestionHistory` (only Attention calls it) | move into Infantry_Attention |
| Roles -> Profiles | `ProfileExecutorField` (`internal`: it only links because both are one Gradle module today) | kit (it reads `ExecutorContext` only) |

**Rule (expensive to reverse, see 6):** a core module links only against the shell (kit + `ShellContext` + plugin API),
never against another module's classes. `module.json` `dependencies` stay for order and enablement, not for class
linkage. This keeps one class loader per module (no loader graph), lets any module be replaced by a community one, and
turns the jar split into a leak test: **a module that compiles alone in its own project with only `agenthqUiCore` on
its classpath is a module.** If a real need for module-to-module classes appears later, a loader that delegates to its
declared dependencies' loaders can be added then. It is not needed for any edge above.

### 4b. `TabContext.coreController` / `appShell`

- Users: 11 tabs through `appController` (Attention, Epics, Pods, Profiles, Prompts, Review, Roles, Skills, Stats,
  Triggers, Usage), and the app UI's fills through `appShell` (center/chat, sidebar, header, assistant, diff, processes,
  sound, voice, connection items: SHELL-LEAKS S4d "stays on appShell").
- **It does not block the split:** core-tier jars load through an unfiltered `PluginClassLoader`, and `coreController`
  is gated on tier, which B (2b) now backs with a signature. The split and the API work are independent, so they are
  sequenced separately.
- **The API it becomes** is the S4d path: `ShellContext` domains, judged by the S4d count (coherent sub-interfaces, no
  one-off getters). Already there: tickets, roles(+profiles), questions, window, session, git, run, notes, changes,
  executor. Still to add: `ChatContext` (~30), `AgentsContext` (~12), `ProcessesContext` (~5), `UsageContext`
  (Stats/Usage), `LibraryContext` (Prompts/Skills: list/insert), `PodsContext`, `TriggersContext`, `ReviewContext`.
- **Ratchet:** a test counts `appController`/`appShell` uses in `plugins/core`. The count may only go down. When it
  reaches 0, `coreController` and `coreShell` are deleted, and core differs from community only in its default
  permissions.

## 5. Migration (smallest first; each step shippable, default 32 shots + `empty` shots at 0 px)

| Step | What | Weight | Proof |
|---|---|---|---|
| **E1** | **Generated list.** Each module folder gets its `XSource : ModuleSource` and `entry` in module.json. `generateModuleList` writes `CoreModules` (same name/API) for agenthqAppUi. Hand-written `CoreModules.kt` deleted. agentHqWeb `Main.kt` unchanged (it calls `CoreModules.all`). Order = resolver order; `sequence` entries added where needed to match today's order exactly. | 2 | generator unit test; 32/32 + empty 0 px; wasm + Android compile |
| **E2** | **Tracer: Infantry_Pods as a real jar.** Pods becomes its own project (`gradle/core-module.gradle.kts`). Product build stages `modules/core/Infantry_Pods` (signed) into the desktop app resources. `PluginFolders` bundled root + signature check (2b) + `devTrust`. Web/Android get Pods through the generated project deps. **Measure** plugin load and first frame with the BLOCKS-PERF harness and set the budget. | 3 | 0 px; a test that an unsigned jar in `core\` is refused; a test that a renamed/copied folder is refused; perf numbers in this doc |
| **E3a** | Leaf modules whose code is already in their folder and with no edges: Review, Triggers, Cli, Agents → own projects/jars | 2 | 0 px |
| **E3b** | Move the 1,211 lines of Epics/Prompts/Skills/Stats/Usage panes (+ `UsagePacingSection` via a `config.<entry>` contribution) out of agenthqUiCore into their modules → own jars. `compact(n)` and other kit helpers stay in core. | 3 | 0 px incl. `config` shots; agenthqUiCore no longer contains any `*Pane` of a module |
| **E4** | Cut the 4a edges (kit moves, own tab trees/fills), then Git, Run, Notes, Changes, Roles, Profiles, Attention, Board → own jars (Board already a project: drop the `api` edge from AppUi). **After SETTINGS-TAB-1 (ef796432) merges.** | 4 | 0 px incl. `board`, `roles`, `registry` shots (registry source chips change only where a fill moves module; check by eye as S4d did) |
| **E5** | **The app UI itself.** Infantry_AppUi becomes a project/jar. `agenthqAppUi` dissolves. The desktop app drops `runtimeOnly(agenthqAppUi)` and `isShellJar`. `appUiIhq` becomes an NS3 composition `.ihq` with `deps/*.ihq` (the 19 modules). The desktop app's classpath is now the shell. | 3 | 32/32 + dropin + empty 0 px; `runShell` = app with empty modules dir |
| **E6** | Locked core: `product.json` `required`, Settings > Modules shows source (bundled/user) and signer, uninstall/disable rules of 2c, bundled-vs-user upgrade rule | 2 | unit tests on DependencyGraph + IhqInstaller; `config` shots |
| **E7…** | coreController → ShellContext, one module per ticket, ratchet test from E2 on | 2-3 each | 0 px each; ratchet count down |

**Recommended first slice: E1.** It is pure build work, has no runtime change, and is 0 px by construction. It removes
the hand-written list on all three platforms at once. E2 is the step that proves the owner's principle on Windows, so
start it right after E1.

## 6. Risks and decisions that are expensive to reverse

**Risks**
- **Start-up.** BLOCKS-PERF-CHECK: `DesktopPlugins.load` went from 226 to 436 ms (+93%) between 17 and 19 core modules (+
  the one big bundle jar). Separate jars add one `URLClassLoader` and one `JarFile(verify=true)` pass per module.
  This is unmeasured, so I will not guess a number. E2 measures it. Budget: plugin load <= 500 ms, first frame within
  +3% of today. If over budget, the fixes in order are: verify in parallel; then a verified-jar cache keyed by
  (path, size, mtime, SHA-256), which only skips work and never grants trust (a stale or forged entry just re-verifies);
  then lazy loading of tab-only modules on first open.
- **Resources through class loaders** (fonts/icons in a module jar resolve through its loader): the
  SHELL-EMPTY-DROPIN 5 risk. The 0 px gate per step catches it.
- **Android build** (4dc8b33f): the per-project layout removes the srcDir workaround. The new risk is
  18 more Android library projects (configuration time, AGP). Measure the configuration time in E2.
- **Parallel work:** SETTINGS-TAB-1 (ef796432) edits the same registrations; E3/E4 go after it.
- **Host RAM** (note `host-ram-exhaustion-jvm-crashes`): more projects mean more Gradle workers. Keep one build at a
  time.
- **License (LICENSE-0, LICENSE-1 0fc727a6):** what stays in the shell is the platform: kit, `ShellContext`, plugin API,
  loader, trust kernel, daemon link. Moving widgets to the kit (4a) moves them into the platform's license, and that
  license is still undecided. Per-module jars with `module.json` `license` make per-module licensing real for the first
  time. The platform license must allow proprietary third-party modules to link against the kit; MPL 2.0, the LICENSE-1
  candidate, does. Decide LICENSE-1 before E5 publishes the API as the only way in.

**Expensive to reverse**
1. **The pinned Infantry key as the core trust root.** Rotating it means re-signing every core jar and shipping a shell
   that pins both keys for one release.
2. **No class linkage between modules.** Once third parties ship modules built against that rule, allowing it later is
   cheap, but taking it back is not. Every widget moved to the kit becomes frozen API under `apiVersion`.
3. **The module.json `entry` = `ModuleSource` contract and the folder layout** (`modules/core/<Name>/lib/<Name>.jar`).
   The generator, the loader, the installer and the MSI all read it.
4. **`product.json` as the single composition manifest** for desktop staging and for the web/Android compile.
5. **The empty-state contract:** the `shell-empty` shots become the definition of "a product with no modules".
