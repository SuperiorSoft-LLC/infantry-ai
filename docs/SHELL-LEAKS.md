# Shell leaks (S4, drop-in path)

S4 moves the app UI out of `agenthqUiCore` into `:superiorSoftLibrary:agenthqAppUi`, which reaches core only through its
public API. Each place where moved code reached into core internals is a leak, and each one gets a decision:

- **(a)** add a small method to the shell's API (additions only)
- **(b)** move the dependency with the code
- **(c)** move the code back to core
- **(d)** the symbol is the shell's own API, so make it public as it is (no new surface, only visibility)
- **bridge**: made public only until its users move in S4b/S4c, where it becomes (b). Each bridge carries an
  `// S4 bridge (docs/SHELL-LEAKS.md)` line at its declaration.

S4 is split into three steps (PM mail, 2026-10-27):

- **S4a** (ticket 842cebf1): the module and the inversion.
- **S4b**: the panes (git, run, roles, board, memory, changes).
- **S4c**: the shell blocks, overlays and settings, plus the final gate.

This file lists the S4a leaks. S4b and S4c append their own.

## The inversion (S4a)

| File | Symbol | Leak | Decision |
|---|---|---|---|
| agenthqUiCore/build.gradle.kts | `commonMain.srcDir(plugins/core)` | core compiled the app's modules into itself | (b): plugins/core compiles into agenthqAppUi |
| App.kt, shell/PlatformHost.kt | `CoreModules.factory` (default `tabs`) | the shell named the app's module list | (a): `CompiledModules.factory`; the modules come from a `ModuleSource` (ServiceLoader on jvm/android, `CompiledModules.install` on wasm) |
| jvmMain plugin/DesktopPlugins.kt | `CoreModules.all` (compiled modules) | same | (a): `CompiledModules.all` |
| shell/blocks/CoreShell.kt | `ShellHeader`, `ShellLeftPanel`, ... as the placeholders' defaults (14 slots) | the shell's tree held the app's blocks | (b): the slots are empty `PlaceholderBlock`s (`CoreShell.slots`); `Infantry_AppUi` fills them (`ModuleManifest.fills`, `AgentHqModule.fills`, `UiRegistrar.fill`) |
| shell/blocks/CoreShell.kt | `CoreShell.tabs`, `tabTreeTabs` (ShellGit, ShellRun, ShellNotes, ShellChanges) | same | (b): `AgentHqModule.tabTrees` / `UiRegistrar.tabTree`, read back as `TabFactory.tabTreeRoots`, `tabTreeTabs` |
| plugin/TabFactory.kt | `TabContext.controller` (internal) | 11 core modules read the controller through `internal`, which only worked while they compiled inside core | (a): `TabContext.coreController`, tier-gated (null for a community module); the modules read it through `appController` (plugins/core/CoreModules.kt) |
| App.kt | `ReviewPane`, `PodsPane`, `podColor`, `nodeColor` (internal) | panes of a module lived in core | (b): plugins/core/Infantry_Review/ReviewPane.kt, Infantry_Pods/PodsPane.kt |
| RolesAttention.kt | `AttentionPane`, `WatchSection` | same | (b): Infantry_Attention/AttentionPane.kt |
| Profiles.kt | `ProfilesPane`, `ProfileRow`, `ProfileForm`, `PROFILE_FORM_ERROR_TAG`, `PendingSave` | same | (b): Infantry_Profiles/ProfilesPane.kt |
| Triggers.kt | the Triggers pane (whole file) | same | (b): git mv to Infantry_Triggers/Triggers.kt |
| commonTest BoardTriggersModelTest.kt | the `triggerLanes` test | the test reached a function that moved | (b): agenthqAppUi commonTest TriggerLanesTest.kt |
| moved panes | `LocalHostSurface` (internal) import | an unused import copied with the code | none: the import is dropped (not a leak) |
| Board.kt | `assigneeLabel`, `WatchFlagChip` | the moved Attention pane uses board helpers | bridge: they go to (b) with the board in S4b |
| Profiles.kt | `ProfileExecutorField` | ProfilesPane uses it; the new-agent dialog (core until S4c) uses it too | bridge: (b) in S4c |
| TriggerDuration.kt | `humanDuration` | the Triggers pane and the board both use it | bridge: (b) with the board in S4b |
| TriggerDraft.kt | `nextFireLabel`, `lastFireLabel` | same | bridge: (b) with the board in S4b |
| TriggerDraft.kt | `TriggerDraft`, `MIN_TRIGGER_INTERVAL_MINUTES` | the Triggers pane builds the draft that the controller saves | (d): the draft is the controller's input type |
| net/Controller.kt | `Controller.saveTrigger` (internal) | it was the controller's only internal function (146 public) | (d) |

Counts for S4a (18 rows): a 3, b 8, c 0, d 2, bridge 4 (6 symbols), and 1 dead import.

## API added to the shell

None of these additions is on `ShellContext`; they all belong to the module contract:

- `ModuleManifest.fills`
- `AgentHqModule.fills` and `AgentHqModule.tabTrees`
- `UiRegistrar.fill` and `UiRegistrar.tabTree`
- `TabFactory.baseRoot`, `TabFactory.tabTreeRoots` and `TabFactory.tabTreeTabs`
- `CompiledModules` and `ModuleSource`
- `TabContext.coreController`
- `CoreShell.slots`

## Holes that stay open

- **`LocalShell.current.controller` is public.** Any module, community ones included, can reach the whole `Controller`
  through it, and `coreController` does not close that path. The real fix is the S2 direction: blocks read
  `ShellContext` only, and `Shell.controller` goes internal once the last moved pane stops using it (S4c). Today 44
  files use `Controller` and 22 use `LocalShell`.
- **The Registered view attributes filled nodes to the core shell** (`NodeRegistry` keeps the label "CoreShell.tabs"
  so the P1 shots stay the same). It should name `Infantry_AppUi` once a baseline refresh is acceptable.

## Decisions that are expensive to reverse

- **Fill before resolve.** A fill replaces the empty slot in the base tree before overrides and injections resolve.
  Node ids therefore stay addressable, and the drawn tree is the old one by construction.
  - Only core-tier modules may fill a slot, only one module per slot, and only slots declared in module.json.
- **ServiceLoader as the jvm/android discovery path.** The service file is
  `META-INF/services/com.superiorsoftllc.agenthq.ui.plugin.ModuleSource`.
  - Untested on a minified (R8) Android release: a keep rule for `ModuleSource` implementations may be needed.
- **coreController is gated on tier, not on the classloader.** A module declared core in its manifest gets the
  controller.
