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

# S4b: the panes (ticket 8724e046)

S4b moves the pane groups git, run, roles, board, memory and changes into agenthqAppUi, one commit each, after one
prep commit that holds everything two groups share. That way any pane commit can be dropped or reverted on its own.
The compiler only catches `internal` leaks. `Controller`, `LocalShell` and `AppState` are public, so each moved file
gets a symbol audit: every core symbol it touches, and a decision for each one. S4b uses the ticket's letters:

- **(a)** already on `ShellContext`
- **(b)** added to `ShellContext` as an addition-only member with a default
- **(c)** belongs to the app UI and moves too
- **kit**: the shell's public toolkit, which any block may use and which stays in core as it is. That covers the
  theme (`Palette`, `Mono`), the controls (`Clickables.kt`, `Chip`, `IconAction`, `AhqAlertDialog`, `SearchableSelect`,
  `LocalDirectoryPicker`), the block contract (`Block`, `BlockScope`, `Place`, `RenderPlaced`), and the model views and
  wire types (`model/*`, `protocol/*`, `CommandResult` and the `*_UNSUPPORTED` reasons). They are not leaks: they hold
  no window state.
- **stays**: core still uses it, so it stays in core (public where the moved code needs it)
- **bridge**: as in S4a. Public only until its last core user moves, marked `// S4 bridge (docs/SHELL-LEAKS.md)`.

Each pane keeps its `(state, controller)` entry (`ProjectGitPane`, `RunPane`, `MemoryPane`, `ProjectChangesPanel`) for
tests and for drawing outside a shell. It builds the shell's public adapters (`CoreLink`, `CoreSession`, `CoreGit`,
`CoreRun`, `CoreNotes`, `CoreChanges`) and hands them to the same frame the block draws, so the frames and sections
read the context only. Only that one line names `Controller` or `AppState`.

## Prep commit (shared by the pane commits)

| File | Symbol | Why | Decision |
|---|---|---|---|
| shell/ShellContext.kt | `ShellContext.layout` (`LayoutContext.tabTree`, default `NoLayout`) | the Git, Run, Notes and Changes tabs read their resolved tree through `LocalShell.current.tabs.tabTree` | (b) |
| shell/ShellContext.kt | `ShellContext.notes` (`NotesContext`, default `NoNotes`) | the Notes pane reads `state.memoryNotes`, `memorySearch` and calls 5 `controller.*Memory` | (b) |
| shell/ShellContext.kt | `ShellContext.changes` (`ChangesContext`, default `NoChanges`) | the Changes pane reads `projectWorktrees`, `changesWorktree(Status)`, `changes(Shown)Branch`, `projectGitBranchChanges`, calls 6 controller requests and polls on `controller.client.frames` | (b): `pollWorktrees` and `pollWorktreeStatus` hide the frame wait (BUG-CHANGES-BRANCH-1) in the core adapter |
| shell/ShellContext.kt | `SessionContext.selectedAgentId`, `selectedAgent` (default null) | Notes (role / session scope) and Changes (the worktree of the selected session) read `state.selected` / `selectedId` | (b) |
| shell/CoreShellContext.kt | `CoreNotes`, `CoreChanges`, `pollOnce` | the core's implementations; `pollOnce` moved here from ProjectChangesPanel.kt (it reads `controller.client`) | stays |
| ProjectGitPane.kt -> ChangesSection.kt | `ChangeGroup`, `ChangedFileRow`, `GIT_LIST_TOGGLE_TAG` (were internal) | the Git and Changes panes both draw them | bridge: they go to (c) once both panes are in agenthqAppUi |
| ChangesSection.kt | `ChangedPathText` (was internal) | the Git and Changes panes and core's `ChangesSection` draw it | bridge |
| block/layout/Placement.kt | `RenderPlaced` (Row, Column, Box; were internal) | every moved container block draws its children with it | kit: the block contract's own function, made public (no new surface, only visibility) |
| block/layout/Placement.kt | `RenderPlain` (was internal) | the moved Notes and Changes trees draw their axis-less groups with it | kit: as `RenderPlaced` |

A shell built without these domains (a third-party `ShellContext`) compiles unchanged: `NoNotes` and `NoChanges` refuse
every request with `SHELL_DOMAIN_MISSING`, and `NoLayout` makes each tab draw its default tree.

## Git (Infantry_Git)

Moved into plugins/core/Infantry_Git (`git mv`, packages unchanged): ProjectGitPane.kt, ProjectPipelineSection.kt,
shell/blocks/ShellGit.kt. Nothing of the group stays in core. Pixel proof: `-PtabShotsExtra=git` against the
pre-move render, 4 of 4 shots at 0 px.

| File | Symbol | Decision |
|---|---|---|
| ShellGit.kt | `LocalShell.current.tabs.tabTree` (the resolved Git tree) | (b): `LocalShellContext.current?.layout?.tabTree` (prep) |
| ShellGit.kt | `LocalShellContext`, `ShellContext.link / session / git` | (a) |
| ShellGit.kt | `Block`, `BlockScope`, `Place`, `RenderPlaced` | kit (`RenderPlaced` made public in prep) |
| ProjectGitPane.kt | `GitContext`, `LinkContext`, `SessionContext` (`GitPaneFrame`, `GitPaneHost`, every section) | (a) |
| ProjectGitPane.kt | `ProjectGitPane(state, controller)`: `AppState`, `Controller`, `CoreLink`, `CoreSession`, `CoreGit` | (a) through the public adapters: the outside-a-shell entry (ProjectGitPaneUiTest, ProjectGitRealDaemonUiTest, ProjectBaseBranchRealDaemonUiTest) |
| ProjectGitPane.kt | `ChangeGroup`, `ChangedFileRow`, `ChangedPathText`, `GIT_LIST_TOGGLE_TAG` | bridge (prep) |
| ProjectGitPane.kt | `LocalDirectoryPicker` | kit: the platform's folder picker, which the host provides |
| ProjectGitPane.kt | `SearchableSelect`, `OutlinedButton`, `clickable`, `Palette`, `Mono` | kit |
| ProjectGitPane.kt | `BASE_BRANCH_DEFAULT_LABEL`, `baseBranchChoices`, `isCurrentBase`, `projectChangesView`, `projectHistoryView`, `projectPatchView`, `ChangesView`, `HistoryView`, `PatchView`, `headline`, `whenText`, `badge`, `display`, `label`, `summary`, `ProjectView`, `Notice`, `contractAtLeast`, `ChangeKind`, `ChangedFile`, `CommandResult`, `PROJECT_GIT_UNSUPPORTED`, `PROJECT_GIT_BRANCHES_UNSUPPORTED`, `PROJECT_GIT_CHECKOUT_UNSUPPORTED`, `PROJECT_BASE_UNSUPPORTED` | kit (model views, wire types, refusal reasons) |
| ProjectPipelineSection.kt | `GitContext`, `LinkContext` | (a) |
| ProjectPipelineSection.kt | `AhqAlertDialog`, `TextButton`, `OutlinedButton`, `Palette`, `Mono`, `PipelineSectionView`, `pipelineSectionView`, `shortCommit`, `silentMs`, `PipelineCheckView`, `PipelineEntryView`, `parseStageNames`, `PIPELINE_UNSUPPORTED`, `CommandResult` | kit |

## Run (Infantry_Run)

Moved into plugins/core/Infantry_Run: RunPane.kt, shell/blocks/ShellRun.kt. The form-rules test moves with
`RunCommandForm` (agenthqAppUi commonTest RunCommandFormTest; the rest of RunModelTest tests the core model and
stays). Pixel proof: `-PtabShotsExtra=run`, 4 of 4 shots at 0 px.

| File | Symbol | Decision |
|---|---|---|
| ShellRun.kt | `LocalShell.current.tabs.tabTree` | (b): `ShellContext.layout` (prep) |
| ShellRun.kt | `LocalShellContext`, `ShellContext.link / run` | (a) |
| ShellRun.kt | `Block`, `BlockScope`, `Place`, `RenderPlaced` | kit |
| RunPane.kt | `RunContext`, `LinkContext` (`RunPaneFrame`, `RunPaneHost`, every section) | (a) |
| RunPane.kt | `RunPane(state, controller)`: `AppState`, `Controller`, `CoreLink`, `CoreRun`, `LocalRunConsole` | (a) through the public adapters: the outside-a-shell entry (RunPaneUiTest, RunCommandEditorUiTest) |
| RunPills.kt | `RunPillChip`, `RunConsoleHost`, `LocalRunConsole`, `runPillTag` | stays: the desktop's console window (agentHqUi RunConsole.kt, which compiles against the shell only) draws `RunPillChip`, and App.kt provides `LocalRunConsole` to `CoreShellContext` |
| RunPane.kt | `RunsState`, `RunState`, `CommandSpecView`, `runPills`, `CommandResult` | kit (model) |
| RunPane.kt | `Checkbox`, `DropdownMenuItem`, `OutlinedButton`, `TextButton`, `Palette`, `Mono` | kit |

## Memory (Infantry_Notes)

Moved into plugins/core/Infantry_Notes: MemoryPane.kt, shell/blocks/ShellNotes.kt. Migrated: `NotesPaneFrame` and
`NotesPaneHost` took `(state, controller)` and now take `LinkContext`, `SessionContext` and `NotesContext`. The host's
public `state` and `controller` are gone, and `link`, `session` and `context` replace them. `NotesPaneBlock` reads
`LocalShellContext` instead of `LocalShell`. Pixel proof: `-PtabShotsExtra=memory`, 6 of 6 shots at 0 px.

| File | Symbol | Decision |
|---|---|---|
| ShellNotes.kt | `LocalShell.current.state / controller` (the frame's inputs) | (a) `link`, `session` + (b) `ShellContext.notes` (prep) |
| ShellNotes.kt | `LocalShell.current.tabs.tabTree` | (b): `ShellContext.layout` (prep) |
| ShellNotes.kt | `Block`, `BlockScope`, `Place`, `RenderPlaced`, `RenderPlain` | kit (`RenderPlain` made public in prep) |
| MemoryPane.kt | `state.connection.isLive`, `state.activeProject` | (a): `LinkContext.live`, `SessionContext.activeProject` |
| MemoryPane.kt | `state.selected` (the role / session scope's owner) | (b): `SessionContext.selectedAgent` (prep) |
| MemoryPane.kt | `state.memoryNotes`, `state.memorySearch` | (b): `NotesContext.notes(key)`, `NotesContext.search` (prep) |
| MemoryPane.kt | `controller.loadMemory / getMemory / searchMemory / saveMemory / deleteMemory` | (b): `NotesContext.load / get / search / save / delete` (prep) |
| MemoryPane.kt | `MemoryPane(state, controller)`: `AppState`, `Controller`, `CoreLink`, `CoreSession`, `CoreNotes` | (a) through the public adapters: the outside-a-shell entry (MemoryPaneUiTest, NotesScopesUiTest) |
| MemoryPane.kt | `EditorModal` | stays: the shared editor modal, which the role and profile panes also open |
| MemoryPane.kt | `AgentView`, `MemoryHitView`, `MemoryNoteView`, `NoteScope`, `ProjectView`, `CommandResult` | kit (model) |
| MemoryPane.kt | `AhqIcons`, `IconAction`, `IconActionStyle`, `Button`, `OutlinedButton`, `clickable`, `Palette` | kit |
