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

- **`LocalShell.current.controller` is public.** (S4d: closed by tier, see "Controller close (S4d)".) Any module, community ones included, can reach the whole `Controller`
  through it, and `coreController` does not close that path. The real fix is the S2 direction: blocks read
  `ShellContext` only, and `Shell.controller` goes internal once the last moved pane stops using it (S4c). Today 44
  files use `Controller` and 22 use `LocalShell`.
- **The Registered view attributes filled nodes to the core shell** (S4d: fixed, see "Cleanups and the empty-shell proof") (`NodeRegistry` keeps the label "CoreShell.tabs"
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

## Changes (Infantry_Changes)

Moved into plugins/core/Infantry_Changes: ProjectChangesPanel.kt, shell/blocks/ShellChanges.kt. The not-a-git-repository
test moves with `isNotGitRepository` (agenthqAppUi commonTest NotGitRepositoryTest). Migrated: `ChangesPaneFrame` and
`ChangesPaneHost` took `(state, controller)` and now take `LinkContext`, `SessionContext`, `GitContext` and
`ChangesContext`. The host's `state` and `controller` become `link`, `session`, `git` and `context`, and the private
`pollOnce` is gone (it lives in `CoreChanges`, prep). Pixel proof: `-PtabShotsExtra=changespane`, 6 of 6 shots at 0 px.

| File | Symbol | Decision |
|---|---|---|
| ShellChanges.kt | `LocalShell.current.state / controller` | (a) `link`, `session`, `git` + (b) `ShellContext.changes` (prep) |
| ShellChanges.kt | `LocalShell.current.tabs.tabTree` | (b): `ShellContext.layout` (prep) |
| ShellChanges.kt | `Block`, `BlockScope`, `Place`, `RenderPlaced`, `RenderPlain` | kit |
| ProjectChangesPanel.kt | `state.connection` (live, contract) | (a): `LinkContext.live`, `contractAtLeast` |
| ProjectChangesPanel.kt | `state.activeProject` | (a): `SessionContext.activeProject` |
| ProjectChangesPanel.kt | `state.selectedId` (the worktree list names the selected session) | (b): `SessionContext.selectedAgentId` (prep) |
| ProjectChangesPanel.kt | `state.projectGit`, `projectGitFailure`, `projectGitBranches`, `controller.loadProjectBranches` | (a): `GitContext.status / failure / branches / loadBranches` |
| ProjectChangesPanel.kt | `state.projectWorktrees`, `changesWorktree`, `changesWorktreeStatus`, `changesBranch`, `changesShownBranch`, `projectGitBranchChanges` | (b): `ChangesContext.worktrees / worktree / worktreeStatus / branch / shownBranch / branchChanges` (prep) |
| ProjectChangesPanel.kt | `controller.loadProjectTree / loadProjectWorktrees / loadWorktreeStatus / loadBranchChanges / selectChangesWorktree / selectChangesBranch` | (b): `ChangesContext.loadTree / loadWorktrees / loadWorktreeStatus / loadBranchChanges / selectWorktree / selectBranch` (prep) |
| ProjectChangesPanel.kt | `pollOnce` on `controller.client.frames`, `Envelope` | (b): `ChangesContext.pollWorktrees / pollWorktreeStatus` (prep); the frame wait stays in core |
| ProjectChangesPanel.kt | `ProjectChangesPanel(state, controller, ...)`: `AppState`, `Controller`, `CoreLink`, `CoreSession`, `CoreGit`, `CoreChanges` | (a) through the public adapters: the outside-a-shell entry (ProjectChangesPanelUiTest, IconButtonsUiTest) |
| ProjectChangesPanel.kt | `ChangeGroup`, `ChangedFileRow`, `ChangedPathText` | bridge (prep). With Git moved too, their only core user is core's own `ChangesSection`, so they can go to (c) when it does |
| ProjectChangesPanel.kt | `DiffTarget`, the change views (`worktreeChangesView`, `branchChangesView`, `projectChangesView`, `worktreeHeader`, `worktreeHistoryView`, `aheadHint`, `markers`, `optionKey`, ...), the wire types, `PROJECT_GIT_UNSUPPORTED`, `PROJECT_GIT_BRANCHES_UNSUPPORTED` | kit |

## Roles (Infantry_Roles)

Moved into plugins/core/Infantry_Roles: RolesAttention.kt (`RolesPane`; Attention left in S4a), RoleManager.kt,
TraitsEditor.kt, shell/blocks/ShellRoles.kt. Pixel proof: `-PtabShotsExtra=roles`, 4 of 4 shots at 0 px, and the default
set 32 of 32.

PM mail (2026-10-27): move without stopping, and list any big `ShellContext` addition in the report. Roles is not
migrated in S4b. `RolesPane`, `RoleManagerForm`, `RoleFormFrame` and `RoleTraitsEditor` still take `(state, controller)`
through the tab's `appController` and `LocalShell`. Every one of those uses is listed below as **hole (S4c)**, with the
member it needs. They wait for S4c because `ShellContext` can only ever grow. The same executor and profile helpers
(`ProfileExecutorField`, `ExecutorPicker`) serve the New agent dialog, which moves in S4c, so `RolesContext` should be
shaped once, there, for both.

| File | Symbol | Decision |
|---|---|---|
| TraitsEditor.kt | the whole file (`RoleTraitsEditor`, `TraitLibrary`, `TraitForm`, the trait tags) | (c): only the role editor uses it |
| RolesAttention.kt | `bindingSummary` | stays: the New agent box (ChatSurface) shows it too; it moved to core Profiles.kt |
| RoleManager.kt | `ROLE_MANAGER_OPEN_TAG` | stays: the tag of the Manage roles button, which is ConfigDialog's (core); it moved to ConfigDialog.kt |
| ShellRoles.kt | `LocalShellContext`, `RolesContext.managerOpen / closeManager / roles` | (a) |
| ShellRoles.kt | `LocalShell.current.state / controller` (into `RoleManagerForm`) | hole (S4c): `RolesContext` needs the actions below |
| RolesAttention.kt, RoleManager.kt | `state.roles`, `roleBindings`, `roleTraits` | (a) already on `RolesContext` (`roles`, `bindings`, `traits`), not wired yet: the readers take `AppState` until the actions move too |
| RolesAttention.kt | `state.orderedAgents`, `agentError`, `role(id)`, `profilesFor(role)`, `providers`, `modelCatalog`, `activeProjectId`, `connection` | hole (S4c): `RolesContext.agents`, `agentError`, `profilesFor`; a shared `ExecutorContext` (`providers`, `modelCatalog`) for the role editor, the profile form and the New agent dialog; `link` and `session` exist |
| RolesAttention.kt | `controller.saveRole`, `saveRoleBinding`, `saveProfile`, `deleteProfile`, `spawnAs` | hole (S4c): `RolesContext.saveRole / saveBinding / saveProfile / deleteProfile / spawnAs` |
| RoleManager.kt | `controller.deleteRole`, `setRoleTraits`, `loadMemory` (the role's notes count), `state.memoryNotes`, `state.traits` | hole (S4c): `RolesContext.deleteRole / setTraits`; the notes go through `NotesContext` (prep) |
| TraitsEditor.kt | `controller.saveTrait`, `deleteTrait`, `setRoleTraits`, `state.traits`, `traitsLoading`, `traitError`, `rolesBinding`, `traitIdsFor` | hole (S4c): a `TraitsContext` (the library, its load state and error, save / delete / bind) |
| RolesAttention.kt, RoleManager.kt | `ProfileExecutorField` | bridge (S4a), still used by the New agent dialog (core until S4c) |
| RolesAttention.kt | `EditorModal`, `ExecutorPicker`, `ExecutorPickerTags`, `AgentAvatar`, `RoleIcon`, `RoleIconSite` | stays: shared with panes still in core (Epics, Prompts, Skills, the New agent dialog, the chat) |
| all four | `Role`, `RoleBinding`, `AgentProfile`, `ExecutorPickerModel`, `PickedModel`, `RoleIcons`, `TraitLimits`, `TraitDraft` and the trait model functions, `NoteScope`, `watchFlagOf`, `stateLabel`, `stateColor`, `AhqIcons`, `IconAction`, `AhqAlertDialog`, the controls, `Palette`, `Mono` | kit |

Estimated S4c addition for roles: about 12 members on `RolesContext`, plus `TraitsContext` (about 7) and
`ExecutorContext` (2), which the New agent dialog needs as well.

## Board (Infantry_AppUi)

Moved into plugins/core/Infantry_AppUi: Board.kt, BoardModal.kt, BoardDropSession.kt, TicketExecutorField.kt,
shell/blocks/ShellBoard.kt. BoardDropTest moves to agenthqAppUi commonTest with `BoardDropSession`. Pixel proof:
`-PtabShotsExtra=board`, 4 of 4 shots at 0 px, and the default set 32 of 32.

Like Roles, the board is moved but not migrated in S4b. `BoardPaneFrame` and `BoardModal` still take
`(state, controller)` through `LocalShell`. Each of those uses is listed below as **hole (S4c)**. The open/close and
open-agent path already goes through `ShellContext.board` (a).

| File | Symbol | Decision |
|---|---|---|
| Board.kt, BoardModal.kt, BoardDropSession.kt, TicketExecutorField.kt | `BoardPane`, its frames and sections, `assigneeRole`, `boardEpics`, `EpicFilter`, `ticketCountLabel`, `BoardDrag`, `BoardModal*`, `BOARD_MODAL_TAG`, `BOARD_MODAL_CLOSE_TAG`, `TicketMoves`, `Controller.ticketMoves`, `BoardDropSession`, `TicketAssigneePicker`, `TicketExecutorField`, `TICKET_EXECUTOR_LINE_TAG_PREFIX` | (c): only the board uses them |
| Board.kt | `assigneeLabel` | (c): its users (the board, Infantry_Triggers) are both in the app UI now; its S4a bridge is closed |
| Board.kt | `WatchFlagChip` | stays, still a bridge: the sidebar (App.kt) draws it; it moved to core BoardSidebar.kt |
| Board.kt | `ReportFlagChip` | stays, new bridge: the sidebar and the chat (ChatSurface) draw it; made public in core BoardSidebar.kt |
| BoardModal.kt | `SidebarBoardButton`, `SIDEBAR_BOARD_BUTTON_TAG` | stays: the sidebar's own entry to the board (App.kt); moved to core BoardSidebar.kt |
| Board.kt | `TicketImagesSection`, `ImageViewerHost`, `LocalImageViewer`, `rememberImage` (TicketImages.kt) | stays: the shell hosts the image viewer (CoreShell) and the chat shows attachments; the two internals are now public |
| ShellBoard.kt | `LocalShellContext`, `BoardContext.open / close / openAgent`, `session.activeProject` | (a) |
| ShellBoard.kt | `LocalShell.current.state / controller` (into `BoardPaneFrame`) | hole (S4c): `BoardContext` needs the members below |
| Board.kt, BoardModal.kt | `state.tickets`, `visibleTickets`, `ticket(id)`, `column()`, `epics`, `ticketError`, `reportFlagFor`, `agents`, `role(id)`, `visibleProfiles`, `projects`, `activeProjectId`, `connection`, `panels.compactWindow`, `controller.state` (the flow the drop session collects) | hole (S4c): `BoardContext.tickets / epics / ticketError / reportFlag`, the agents and profiles a card can be assigned to, `LayoutContext.compact`; `link` and `session` exist |
| Board.kt, BoardDropSession.kt, TicketExecutorField.kt | `controller.createTicket`, `editTicket`, `deleteTicket`, `moveTicket`, `startNextTicket`, `loadEpics`, `uploadTicketImage`, `ticketMoves` | hole (S4c): `BoardContext.create / edit / delete / move / startNext / loadEpics / uploadImage` |
| all five | `EditorModal`, `ExecutorPicker`, `ExecutorPickerTags`, `ProfileExecutorField`, `AgentAvatar`, `RoleIcon` | stays: shared with panes still in core (see Roles) |
| all five | `Ticket`, `TicketColumns`, `Epic`, `WatchFlag`, `ReportFlag`, `watchFlagOf`, `AhqIcons`, `IconAction`, the controls, `Palette`, `Mono`, `Block`, `BlockScope`, `Place`, `RenderPlaced` | kit |

`humanDuration`, `nextFireLabel` and `lastFireLabel` (S4a bridges) are not used by the board after all. Their only
users are Infantry_Triggers and core tests, so they go (b) or (c) with Triggers in S4c, not here.

Estimated S4c addition for the board: about 14 members on `BoardContext` (the state above and 7 actions), plus
`LayoutContext.compact`.

# S4c: the shell blocks and the settings (ticket d7825a52)

S4c moves the rest of the app UI that S4a left in core: the slot fills (header, center/chat, left/right panels, compact
drawer, status bar, overlays) and the open settings group of the Config dialog (`infantry.shell.config.settings`, S3).
One commit per group, `git mv` with the package unchanged, the same letters as S4b (a / b / c / kit / stays / bridge,
and **hole** for a `LocalShell` use a `ShellContext` member should replace).

Like Roles and the Board in S4b, the blocks are **moved, not migrated**. They read `LocalShell.current` (`Shell`,
`AppState`, `Controller`, `UiState`), which is public, so the compiler lets them through. Each such use is a hole below.
Migrating them all at once means growing `ShellContext` by roughly 60 members across a dozen domains (see the estimate at
the end). `ShellContext` can only grow, so that shape is worth one design pass of its own rather than being grown block by
block here.

What stays in core, and why, is listed in "What stayed in core" at the end.

## Header (Infantry_AppUi)

Moved: shell/blocks/ShellHeader.kt (`ShellHeader`, `TitleBarBlock`, `HeaderLead*`, `HeaderOverflow*`, the 13 item blocks).

| File | Symbol | Decision |
|---|---|---|
| Contract.kt | `BlockScope.row` (was internal) | kit: the block contract's own field, made public (only visibility). The title bar's lead and agent strip emit into the bar's row with it |
| ShellHeader.kt | `TitleBarFrame`, `TitleBarToggleLeft`, `TitleBarBrand`, `TitleBarProject`, `TitleBarAgents`, `TitleBarOverflow*`, `TitleBarWindowControls`, `TitleBarConnection`, `TitleBarRightToggle`, `TitleBarAttention`, `LocalWindowChrome` | stays: TitleBar.kt is the window's chrome. The desktop window (agentHqUi AgentHqWindow.kt) and the splash draw `TitleBar` without the app UI, and the frame pieces are its parts |
| ShellHeader.kt | `ProjectPicker` | stays: the old `TitleBar` and the projects sidebar draw it |
| ShellHeader.kt | `ServerSwitcherChip` | stays: connect/servers is core |
| ShellHeader.kt | `AssistantEntry` | stays with AssistantPopup.kt (see Overlays) |
| ShellHeader.kt | `ATTENTION_TAB_ID`, `needsInput`, `PanelSide` | kit (model) |
| ShellHeader.kt | `LocalShell.current.state` (connection, assistant, waiting, openPrompts, openQuestionSessions, the agents) | hole: `LinkContext` (has the connection), a `ChatContext` / `AgentsContext` for the strip |
| ShellHeader.kt | `controller.togglePanel`, `s.showTab`, `s.changeConnection`, `ui.showConnect`, `ui.showServers`, `s.switcher`, `s.switchTo`, `s.assistantPanel`, `ui.flags.agentScope` | hole: a `WindowContext` (panels, tabs, the shell's dialogs) |

## Status bar (Infantry_AppUi)

Moved: shell/blocks/StatusBarBlocks.kt (`CoreStatusBar`, `StatusBarBlock`, the 8 `Status*` items) and ProcessesBadge.kt
(`ProcessesBadge`, `ProcessesDialog`, the `PROCESSES_*` tags), which only the status bar draws.

| File | Symbol | Decision |
|---|---|---|
| ProcessesBadge.kt | the whole file | (c): only `StatusProcessesBadge` uses it |
| StatusBarBlocks.kt | `FooterBuild` | stays: the About section (trust half) draws it too |
| StatusBarBlocks.kt | `SERVER_MISMATCH_TAG` | stays: ServerUpdateSection.kt (trust half) owns it |
| StatusBarBlocks.kt | `ConfigTab` | stays: the Config dialog's tabs (core) |
| StatusBarBlocks.kt | `ago`, `ConnState`, `AhqIcons`, `clickable`, `Palette`, `SpacerBlock`, `Block`, `BlockScope`, `Place`, `RenderPlaced` | kit |
| StatusBarBlocks.kt | `state.connection` (`isLive`, its state, `lastFrameAt`, the contract) | (a) in part: `LinkContext.live`, `contract`; hole: the connection state and the last frame time |
| StatusBarBlocks.kt | `s.serverMismatch`, `s.host.appBuild`, `state.panels.compactWindow`, `ui.showConfig`, `ui.configTab` | hole: the `WindowContext` (see Header) |
| ProcessesBadge.kt | `state.processReport`, `processAutoReap`, `controller.refreshProcesses / reapProcesses / setProcessAutoReap` | hole: a `ProcessesContext` (5 members) |

## Overlays (Infantry_AppUi)

Moved: shell/blocks/ShellAssistant.kt, ShellToast.kt, ShellDiff.kt, ShellSound.kt, ShellVoice.kt, ShellQuestionPill.kt,
and the composables only they draw: AssistantPopup.kt, ToastHost.kt, DiffModal.kt. Two pieces of those files stay in
core in files of their own, because core still uses them.

| File | Symbol | Decision |
|---|---|---|
| Contract.kt | `BlockScope.box` (was internal) | kit: as `row` (Header). The toast and the question pill align themselves in the dock with it |
| ResizeHandle.kt | `ResizeHandle` (was internal) | kit: a control, made public (only visibility). The assistant's drag edge uses it, and later the side panels |
| AssistantPopup.kt | `AssistantPopup`, `AssistantPopupFrame / Resize / Header / Chat`, `AssistantEntry`, the `ASSISTANT_*` and `TITLE_BAR_ASSISTANT_TAG` tags | (c) |
| AssistantPopup.kt -> AssistantPanelState.kt | `AssistantPanelState` | stays: the window owns it (`Shell.assistantPanel`, App.kt), so the popup keeps its width and draft while closed |
| ToastHost.kt | `ToastHost`, `toastColour`, the `TOAST_*` tags | (c) |
| ToastHost.kt -> ToastService.kt | `toastEvents` | stays: `CoreShellContext` serves it as `ToastContext.events` |
| DiffModal.kt | `DiffModal`, `DiffModalFrame / Header / Body`, `ProjectDiffModal`, `OpenDiff`, `openDiff`, the `DIFF_MODAL_*` tags | (c). `openDiff` and `ProjectDiffModal` were already only used by the moved Changes pane |
| DiffModal.kt -> DiffLine.kt | `DiffLine` (was internal), `DIFF_LINE_*` tags | stays: the chat's change box (ChangeBox.kt) draws it; made public for the moved `DiffModalBody` |
| AssistantPopup.kt | `ChatPanel`, `ChatPanelState`, `TITLE_BAR_HEIGHT` | stays for now: ChatPanel goes with the center (see Center), TitleBar.kt is the window chrome |
| ShellToast.kt | `LocalShellContext`, `ToastContext.service / events / open`, `LocalToast` | (a): the toast was migrated in S2 |
| ShellSound.kt, ShellVoice.kt | `IncomingCommsSoundEffect`, `VoiceAlerts` | stays: their files hold the Settings sections too (see Settings) |
| ShellQuestionPill.kt | `QuestionPill` | stays: QuestionWindow.kt holds `QuestionPanel`, which the chat draws (see Center) |
| ShellAssistant.kt, AssistantPopup.kt | `s.assistantPanel`, `s.assistantChat`, `state.assistant`, `connection.hasAssistant`, `controller.openAssistant` | hole: an `AssistantContext` (the panel, the session, open) |
| ShellDiff.kt, DiffModal.kt | `ui.diffTarget`, `s.root.requestFocus`, `state.projectGit*`, `worktreeStatus`, `controller.loadProjectDiff / loadBranchDiff` | (a) in part: `GitContext.patch / loadDiff`, `ChangesContext.branchChanges / worktreeStatus`; hole: the diff target, `loadBranchDiff`, the window's focus |
| ShellSound.kt, ShellVoice.kt | `controller.state`, `controller.spoken`, `ui.flags` | hole: the comms feed and the spoken lines, the UI flags (`WindowContext`) |
| ShellQuestionPill.kt | `state.openQuestions`, `dismissedQuestions`, `selectedId`, `agents`, `controller.openQuestion / showQuestions`, `s.homeTab`, `ui.centerTab`, `s.chat.spawnMode` | hole: a `QuestionsContext` (about 4 members) and the `WindowContext` |

## Center and chat (Infantry_AppUi)

Moved: shell/blocks/ShellCenter.kt (`ShellCenter`, `CenterBlock`, `CenterChat*`, `NewAgentBlock`, `NewAgent*`), and
the composables only it and the moved Assistant draw: ChatPanel.kt (`ChatPanel`, `ChatPanelHost`, `chatPanelHost`,
`chatPanelHeader` and its sections), NewAgentDialog.kt and QuestionWindow.kt.

**ChatSurface.kt stays in core in S4c.** It is the chat's whole drawing tree (60 KB: transcript, composer, status row,
cards), and it pulls about ten helper files with it (ChatAttachments, ChangeBox, OriginRenderer, CommsCardStyle,
SessionStartPane, SwitchAgentPicker, ChatTableView, ChatBlockRenderers, PromptCard, ProgressMeter, ReasoningPanelUi).
Several of them are also drawn by core panes and the sidebar (App.kt uses `TicketTag`, `NEW_AGENT_LABEL`,
`NEW_AGENT_SHORTCUT`, `SIDEBAR_NEW_AGENT_TAG`; ChatBlockRenderers and ChatTableView use `spansText`). Moving it is a
split of its own. It is the largest piece of app UI still compiled into core. The two internals that ChatPanel composes
are bridges until then.

| File | Symbol | Decision |
|---|---|---|
| ChatPanel.kt | `ChatPanel`, `ChatPanelHost`, `chatPanelHost`, `chatPanelHeader`, `Header / Transcript / Notices / SpawnRows / SwitchPicker / Composer` (were internal) | (c): the center and the Assistant are their only users |
| ChatPanel.kt -> ChatPanelState.kt | `ChatPanelState`, `rememberChatPanelState` | stays: the window owns the main chat's state (`Shell.chat`, App.kt) |
| NewAgentDialog.kt | `NewAgentDialog`, `NewAgentDialogFrame`, `NewAgentDraft`, the steps, `NEW_AGENT_*` tags | (c) |
| NewAgentDialog.kt -> ModalFieldColors.kt | `visibleBorderFieldColors` (was internal) | stays (kit): the executor picker and the searchable select (core) use it; public |
| QuestionWindow.kt | the whole file (`QuestionPanel`, `QuestionPill`, `QuestionHistory`, the `QUESTION_*` tags) | (c): the chat, the pill and Attention (all app UI) are its users |
| ChatSurface.kt | `PromptBox`, `SpawnRoleRow` (were internal) | bridge: ChatPanel composes them; they go back to internal when ChatSurface.kt moves |
| ShellLeftPanel.kt | `dockCompact` (was internal) | bridge: closed by the next commit (side panels) |
| ShellCenter.kt | `s.tabs`, `s.tabContext`, `ui.centerTab`, `ui.showBoard`, `ui.showNewAgent`, `controller.setCompactWindow` | hole: the `WindowContext` |
| ShellCenter.kt, ChatPanel.kt | `s.chat` / `ChatPanelState`, `s.selected`, `state.agents`, `role`, `roles`, `tickets`, `providers`, `modelCatalog`, `pickedModel`, `harnessPickerAsk`, `shownQuestionsFor`, `userPortrait`, `connection`, `controller.resume / startSession / switchAgent / spawn / pickModel / pickSpawnRole / refreshModelCatalog / answerQuestion / cancelQuestion / dismissQuestions` | hole: a `ChatContext` (about 20 members: the session, its transcript and questions, send / start / switch / spawn) plus the shared `ExecutorContext` (Roles) |
| NewAgentDialog.kt | `state.roles`, `roleBindings`, `providers`, `modelCatalog` | (a) in part: `RolesContext.roles / bindings`; hole: `ExecutorContext` |

## Side panels and compact drawer (Infantry_AppUi)

Moved: shell/blocks/ShellLeftPanel.kt, ShellRightPanel.kt, ShellCompactDrawer.kt (one commit, because the drawer draws
the two panels' content and all three read `dockCompact`). Moved with them: the agent list out of App.kt (Sidebar.kt in
the app UI: `Sidebar`, `AgentsList`, `AgentRow`, the row menu, `SidebarSpacing`, `CenterTabBar`), ProjectsSidebar.kt, and
the sidebar's board entry out of BoardSidebar.kt (SidebarBoardButton.kt: `SidebarBoardButton`, `WatchFlagChip`,
`SIDEBAR_BOARD_BUTTON_TAG`).

| File | Symbol | Decision |
|---|---|---|
| ShellLeftPanel.kt | `dockCompact` | (c): its bridge (Center) closes, internal again in the app UI |
| ShellLeftPanel.kt, ShellRightPanel.kt | `LeftPanelContent`, `RightPanelContent`, `rightPanelArea` (internal) | (c): internal in the app UI (the drawer is the only other user) |
| App.kt -> Sidebar.kt | `Sidebar` (was internal), `AgentsList`, `AgentRow`, `AgentRowMenu`, `AgentGroupHeader`, `Counter`, `SidebarSpacing`, `SIDEBAR_SEARCH_TAG`, `AGENT_GROUP_DIVIDER_TAG_PREFIX`, `NO_PROJECT_LABEL`, `SESSION_NO_PROJECT_TAG_PREFIX`, `SESSION_ASSIGN_TAG_PREFIX` | (c) |
| App.kt -> Sidebar.kt | `CenterTabBar`, `CENTER_TAB_BAR_TAG` | (c): only the moved center draws it |
| App.kt | `RemoveAgentDialog` (was internal), `SESSION_REMOVE_*`, `PROMPT_BOX_TAG`, `COMPOSER_TAG` | stays: the chat's composer (ChatSurface.kt) opens the dialog too; public |
| App.kt | `CompactNavRow` (was internal), `COMPACT_BACK_TAG`, `CENTER_PANE_TAG`, `CENTER_TRANSCRIPT_TAG`, `DRAWER_TAG`, `DRAWER_SCRIM_TAG` | kit: a control and test tags, public |
| TabStrip.kt | `TabBar` (was internal) | kit: the tab strip control (core's tab strip and `CenterTabBar`), public |
| BoardSidebar.kt -> SidebarBoardButton.kt | `SidebarBoardButton`, `WatchFlagChip`, `SIDEBAR_BOARD_BUTTON_TAG` | (c): the sidebar and Attention (app UI) are their users; the `WatchFlagChip` bridge (S4a) closes |
| BoardSidebar.kt | `ReportFlagChip` | stays, still a bridge: the chat (ChatSurface.kt) draws it |
| ProjectsSidebar.kt | `ProjectsSidebar` | (c) |
| ProjectsSidebar.kt -> ProjectRefusal.kt | `projectsRefusal` (was internal) | stays: the project picker (core) reads it; public |
| ShellLeftPanel.kt | `ResizeHandle`, `PanelSections`, `Panels`, `TabContainers`, `SidebarWidth`, `RightPanelWidth`, `PanelLayout`, `CompactScreen` | kit (`ResizeHandle` made public in Overlays) |
| ShellLeftPanel.kt, Sidebar.kt | `s.visible`, `ui.query`, `ui.roleFilter`, `s.searchFocus`, `state.agents / groupedAgents / selectedId / waiting / openPrompts / openQuestionSessions / reviewQueue / workingCount / needsInputCount`, `controller.select / removeSession / assignSessionProject / dismissProjectRefusal` | hole: an `AgentsContext` (the list, its filter and selection, remove / assign; about 12 members) |
| all three | `s.tabs`, `s.tabContext`, `ui.rightTab`, `ui.sidebarWidth`, `ui.rightPanelWidth`, `state.panels`, `controller.showCompactScreen`, `ui.showBoard`, `ui.showNewAgent` | hole: the `WindowContext` |

## Settings (Infantry_AppUi)

Moved: the open settings group of the Config dialog, `ShellConfigSettings` (`infantry.shell.config.settings`, its 15
section blocks), from core shell/blocks/ShellConfig.kt to Infantry_AppUi/ShellConfigSettings.kt. In core the group is an
empty slot now (`CONFIG_SETTINGS`, added to `CoreShell.slots`) inside the locked `infantry.shell.config` container, and
Infantry_AppUi fills it (module.json `fills`). The fill goes in before overrides and injections, so the S3 overrides of
the group and its sections (ConfigDialogOverridesUiTest, NodeRegistryTest) address the same ids as before.

| File | Symbol | Decision |
|---|---|---|
| ShellConfig.kt | `ShellConfigSettings` | (c) |
| ShellConfig.kt | `ConfigSectionBlock` (constructor was internal) | (d): the section block is the dialog's own contract, so it is public as it is; the app UI builds its sections with it |
| ShellConfig.kt | `sectionEntries` (read the settings group) | stays, rewritten: derived from `ConfigEntry` (`infantry.shell.config.<entry in lowerCamelCase>`), so core no longer names the app UI's blocks. Every section id already followed that rule |
| CoreShell.kt | `CoreShell.slots` + `infantry.shell.config.settings`, `CONFIG_SETTINGS` | (a): an addition to the slot list (tree order: before `infantry.shell.roles`) |
| ConfigDialog.kt | `ConfigThemeSection` ... `ConfigQuestionsSection` (the 15 settings composables), `ConfigSections`, `ConfigDialogHost` | stays: the dialog's default body (`ConfigSections`, which the dialog's own UI tests draw without a shell) calls them, and they read only `ConfigDialogHost`, the dialog's contract. They hold no window state. Moving them means the dialog's default body becomes the trust half only, which changes 11 dialog tests; that is a follow-up, not a leak |
| ShellConfig.kt | `ConfigBlock`, the frame's inputs (`state.cliLocations`, `harnessInstall*`, `toolApproval`, `limitCatalog`, `keepAliveMinutes`, `userPortrait`, `controller.set*`) | stays: the frame is locked core (the trust half's inputs come through it too) |
| sound/IncomingCommsSoundUi.kt, voice/VoiceUi.kt | `IncomingCommsSoundSection`, `VoiceSettingsSection` (and `IncomingCommsSoundEffect`, `VoiceAlerts`, see Overlays) | stays: the settings sections above draw them |

## Leak test (S4c)

Without agenthqAppUi the shell is the skeleton: the frame, connect/servers, and the Config dialog's trust half. The
dialog offers only the tabs whose sections are among its children (`ConfigDialog(drawn = ...)`), so a bare shell shows
Secrets / Devices / About (Plugins too where modules load at run time), never an empty Appearance tab. Shots:
`-PtabShotsExtra=empty` renders shell-empty-1400 and shell-empty-config-1400 (tab-shots-baseline/empty-shell/).

## Bridges closed (S4c)

| Bridge | Was | Now |
|---|---|---|
| `WatchFlagChip` | core, public for the sidebar | (c) moved with the sidebar (SidebarBoardButton.kt) |
| `ProfileExecutorField(state, ...)` | core Profiles.kt, public for Roles / Profiles | (c) Infantry_Profiles/ProfileExecutorField.kt, `internal`, reads an `ExecutorContext` (new, (b)) |
| `ChangeGroup`, `ChangedFileRow`, `ChangedPathText` (ChangesSection.kt) | core, public for Changes / Git | (c) the whole file moved to Infantry_Changes; core drew none of it (only a UI test) |
| `nextFireLabel`, `lastFireLabel` | core TriggerDraft.kt, public for Triggers | (c) Infantry_Triggers/TriggerLabels.kt, `internal`; their test moved to agenthqAppUi (TriggerLabelsTest) |
| `humanDuration` | core TriggerDuration.kt, "bridge" | kit: a pure formatter over core's internal `msToMinutesText` (which `TriggerDraft`, core, uses), so it stays public |
| `bindingSummary` | core Profiles.kt | kit: the chat (ChatSurface.kt, core) uses it |
| `ReportFlagChip` | core BoardSidebar.kt | **still a bridge**: ChatSurface.kt draws it; closes with the ChatSurface move (S4d) |
| `PromptBox`, `SpawnRoleRow` | core ChatSurface.kt | **still bridges**: ChatPanel (app) composes them; close with the ChatSurface move (S4d) |

## Core Config entry point (S4c)

The bare shell has no header and no footer, so no cog: `ShellConfigEntry` (core, shell/blocks/ShellConfigEntry.kt) is
a locked frame child that draws a "Configuration" button while no module fills `infantry.shell.header`
(`TabFactory.fills(slot)`), and nothing once one does, so the app window is unchanged. Ctrl+, still opens it too. The
empty-shell shots now show the button (shell-empty-1400) and are opened through it (shell-empty-config-1400).

## Roles migrated (S4c)

Infantry_Roles now reads only `ShellContext` domains, bundled as `RolesDeps(link, session, roles, traits, executor,
notes)` (plugins/core/Infantry_Roles/RolesDeps.kt). The shell block (ShellRoles) and the Roles tab build it from
`LocalShellContext`. `RolesPane(state, controller, ...)` and `RoleManagerDialog(state, controller, ...)` remain as
wrappers over `RolesDeps(state, controller)` (the Core* adapters), so the UI tests and any host without a
`ShellContext` draw them unchanged. Every hole (S4c) in the Roles table above is closed. The members added, each with a
default so an older shell compiles and refuses the edit (`SHELL_DOMAIN_MISSING`):

| Where | Added | Count |
|---|---|---|
| `RolesContext` | `canEdit`, `iconsSupported`, `role(id)`, `profilesFor`, `agents`, `agentError`, `save`, `saveBinding`, `delete`, `saveProfile`, `deleteProfile`, `spawnAs` | 12 |
| `TraitsContext` (new, `ShellContext.traits`, default `NoTraits`) | `all`, `loading`, `error`, `idsFor`, `rolesBinding`, `setForRole`, `save`, `delete` | 8 + 1 |
| `SessionContext` | `activeProjectId` | 1 |
| `ExecutorContext` (bridges commit, `ShellContext.executor`) | `providers`, `modelCatalog` | 2 + 1 |

Core adapters: `CoreRoles`, `CoreTraits` (CoreShellContext.kt); the window's `roles` delegates to `CoreRoles` and adds the
roles window's `managerOpen` / `closeManager`.

## Board (not migrated; S4d)

Board.kt (Infantry_AppUi, 971 lines) still reads `AppState` and calls the `Controller`. A migration needs, on
`BoardContext`: `live`, `activeProjectId` (session), `profiles`, `tickets`, `visibleTickets`, `column(status)`,
`ticket(id)`, `epics`, `agents`, `role(id)`, `reportFlagFor`, `ticketError`, `projects`, `create`, `edit`, `delete`,
`move`, `startNext`, `loadEpics`, `uploadImage` (about 20), plus `LayoutContext.compact` and a ticket-updates flow for
`BoardDropSession` (today `controller.state.collect` and `controller.ticketMoves()`). `BoardPaneHost.state /
controller` and `BoardCardHost.state / controller` are public and are read by BoardModal, ShellBoard and four UI tests
(BoardDrag, BoardBacklogRealDaemon, BoardReorderRealDaemon, TicketBranch), so closing them is an API change.
`TicketAssigneePicker`, `TicketExecutorField` and `assigneeLabel` (Triggers) take `AppState` too. All of these are
**holes**, to S4d.

## ShellContext additions in S4c (count)

| Domain | Members | |
|---|---|---|
| `ShellContext` | `executor`, `traits` | 2 |
| `ExecutorContext` (new) | `providers`, `modelCatalog` | 2 |
| `RolesContext` | see "Roles migrated" | 12 |
| `TraitsContext` (new) | see "Roles migrated" | 8 |
| `SessionContext` | `activeProjectId` | 1 |
| **Total** | | **25** |

Every member has a default, so a shell written against S4b compiles unchanged. Module-contract additions (not
`ShellContext`): `TabFactory.fills(slot)`, `ConfigDialog(drawn = ...)`, `ConfigCogLabel` (internal). Board would add
about 22 more (above).

## Holes still open after S4c

- `Shell.controller` / `LocalShell.current.controller` is public (S4d). In plugins/core, 43 files still name `Controller`
  (19 of them in Infantry_AppUi), 24 name `AppState`, and 12 read `LocalShell.current`.
- Board (above).
- `ReportFlagChip`, `PromptBox`, `SpawnRoleRow` are still bridges until ChatSurface moves (S4d).
- The `(state, controller)` entry points (`RolesPane`, `RoleManagerDialog`, `BoardPane` and the S4b panes) stay public
  as wrappers for the UI tests and hosts without a `ShellContext`.
- The NodeRegistry label "CoreShell.tabs" (see "Holes that stay open").

## What stayed in core

- **The frame and its slots** (CoreShell, the `PlaceholderBlock` slots): the shape a module fills. Without it nothing draws.
- **Connect, servers, switch error, server update** (ShellConnect, ShellServers, ShellSwitchError, ShellServerUpdate): a
  window with no modules must still reach a daemon and recover from a bad one.
- **The Config frame and its trust half** (ShellConfig, `ShellConfigTrust`: plugins, modules, registry, secrets, pair,
  about, updates, server update, logs) and **ShellConfigEntry**. This is where a bad module is turned off, so no
  module may replace it (all locked).
- **The Config settings composables** (`ConfigThemeSection` ... in ConfigDialog.kt): drawn by the dialog's default
  body and its tests; they read only `ConfigDialogHost`. See the Settings table.
- **Window, images and dock** (the platform hosts) and **the placeholders**: platform glue, not UI a module owns.
- **ChatSurface** (and with it `ReportFlagChip`, `PromptBox`, `SpawnRoleRow`, `bindingSummary`): moves in S4d.
- **Kit**: the controls, `Palette`, icons, the model and protocol types, the pure formatters (`humanDuration`, ...).

# S4d: the board, the chat and the controller (ticket 1dadfcc4)

S4d closes the holes S4c left open, one green commit each: the board, the chat tree, then `LocalShell.controller`.
Same letters as S4b/S4c (a / b / c / kit / stays / bridge / hole).

## Board migrated (S4d)

The board (Board.kt, BoardModal.kt, BoardDropSession.kt, TicketExecutorField.kt, ShellBoard.kt) reads only
`ShellContext` domains, bundled as `BoardDeps(link, session, tickets, roles, executor, layout)`
(plugins/core/Infantry_AppUi/BoardDeps.kt), the Roles pattern. `ShellBoard` builds it from `LocalShellContext`;
`BoardPane(state, controller)` stays as a wrapper over `BoardDeps(state, controller)` (the Core adapters) for the four UI
tests that draw it without a shell.

Design choice: the backlog is a **new sub-interface, `TicketsContext`** (`ShellContext.tickets`, default `NoTickets`),
not twenty more members on `BoardContext`. `BoardContext` stays the board *window* (open / close / openAgent); the
backlog is a domain other blocks need too (the chat's ticket tag, Triggers, Epics), and a block that files a ticket
should not depend on the board modal. The assignees come from `RolesContext` (which already had `agents` and `role`),
the pins from `ExecutorContext`, the compact flag from `LayoutContext`.

| File | Symbol | Decision |
|---|---|---|
| Board.kt | `BoardPaneHost.state / controller`, `BoardCardHost.state / controller` | closed: replaced by `deps: BoardDeps` (API change; no caller outside the board read them, the four UI tests go through `BoardPane(state, controller)`) |
| Board.kt | `state.tickets / visibleTickets / ticket / column / epics / ticketError` | (b) `TicketsContext.all / visible / ticket / column / epics / error` |
| Board.kt | `state.reportFlagFor(t)`, `state.agents[id]` | (a) `RolesContext.agents` + `reportFlagOf` (kit), in `BoardDeps.agent / reportFlag`. `agents` leaves out the global Assistant, which never runs a ticket |
| Board.kt, TicketExecutorField.kt | `state.visibleProfiles`, `state.role(id)` | (b) `RolesContext.profiles`; (a) `RolesContext.role` |
| TicketExecutorField.kt | `state.providers / modelCatalog` | (a) `ExecutorContext` |
| Board.kt | `state.projects` (the ticket's directory, for its images) | (b) `SessionContext.projects` |
| Board.kt | `state.panels.compactWindow` | (b) `LayoutContext.compact` |
| Board.kt | `state.connection.isLive`, `activeProjectId` | (a) `LinkContext.live`, `SessionContext.activeProjectId` |
| Board.kt, BoardDropSession.kt | `controller.createTicket / editTicket / deleteTicket / moveTicket / reorderTicket / startNextTicket / loadEpics / uploadTicketImage` | (b) `TicketsContext.create / edit / delete / move / reorder / startNext / loadEpics / uploadImage` |
| BoardDropSession.kt | `Controller.ticketMoves()`, `controller.state.collect` | (b) `ticketMoves { tickets }` over `TicketsContext`, `TicketsContext.updates` (the visible tickets after each change). The drop session reads the current context at each send: the shell hands a new one on every change |
| Board.kt | `assigneeLabel(state, id)` | now `assigneeLabel(profiles, id)`; Triggers passes `state.visibleProfiles` until it migrates (below) |
| BoardModal.kt | `BoardModal(state, controller)`, `BoardModalPane(state, controller)`, `BoardModalHeader(state, ...)` | now over `BoardDeps` / the active project; no caller used the `(state, controller)` forms |
| ShellBoard.kt | `LocalShell.current.state / controller` | closed: `BoardDeps(LocalShellContext.current)` |

Core adapter: `CoreTickets` (CoreShellContext.kt). Pixel proof: default 32 of 32 and `-PtabShotsExtra=board`, base
(5424a3df) and head rendered with the same `agenthq.ui.blur=off`.

| Where | Added | Count |
|---|---|---|
| `ShellContext` | `tickets` | 1 |
| `TicketsContext` (new) | `all`, `visible`, `updates`, `epics`, `error`, `ticket`, `column`, `create`, `edit`, `delete`, `move`, `reorder`, `startNext`, `loadEpics`, `uploadImage` | 15 |
| `RolesContext` | `profiles` | 1 |
| `SessionContext` | `projects` | 1 |
| `LayoutContext` | `compact` | 1 |
| **Total** | | **19** |

HandCursorGuardTest: the `Text` raw-call ratchet reads 1052 against a baseline of 1049 already at the base (5424a3df,
before any S4d change; recounted from git), so its "never grow" check fails on dev as it is. S4d adds no raw call.

## Chat moved (S4d)

Moved into plugins/core/Infantry_AppUi (`git mv`, package unchanged): ChatSurface.kt (the chat's drawing tree:
header, transcript, status row, composer, spawn row), and the helpers only it draws: OriginRenderer.kt,
SessionStartPane.kt, PromptCard.kt, CommsCardStyle.kt (its test moves to agenthqAppUi commonTest), ChangeBox.kt, the
attachment strip and sent images (ChatAttachments.kt split: ChatAttachmentViews.kt), and `ReportFlagChip`
(BoardSidebar.kt -> ReportFlagChip.kt). Like the S4c blocks the chat is **moved, not migrated**: its `(state,
controller)` uses are holes, listed below and taken up by the controller close (next section).

| File | Symbol | Decision |
|---|---|---|
| ChatSurface.kt | `PromptBox`, `SpawnRoleRow` | (c): their bridges close, `internal` again (ChatPanel is their only caller, same module) |
| ReportFlagChip.kt | `ReportFlagChip` | (c): its bridge closes, `internal` (the board, the sidebar, the chat) |
| ChatSurface.kt -> ChatSpans.kt (core) | `ENDED_STATES`, `spansText` (was internal) | stays (kit): core's ChatPanelState reads `ENDED_STATES`; the markdown renderers and the chat table (core) draw `spansText`, now public |
| ChatAttachments.kt -> ChatAttachmentViews.kt | `ChatAttachmentChips`, `ChatSentImages` and their thumbnails | (c): only the chat draws them |
| ChatAttachments.kt | `ChatAttachment`, `ComposerTags`, `ChatImageLines`, the `CHAT_IMAGE_*` / `ATTACH_*` constants | stays: the window owns the draft (ChatPanelState, core) |
| TicketImages.kt | `rememberImage`, `LocalImageViewer` (were internal) | kit (d): the shell's image viewer host (CoreShell provides it), made public as it is; S4b's table said so but the visibility had not changed |
| UsagePane.kt | `compact(n)` (was internal) | kit: a pure token-count formatter the chat header uses, public |
| ChatSurface.kt | `ChatBlockRenderers`, `LocalChatMarkdown`, `LocalReasoningPlacement`, `ProgressMeter`, `ChatTable`, `ChatAttachment`, `ComposerTags`, `SwitchAgentAsk`, `DiffLine` | stays: App.kt / ConfigDialog / ChatPanelState (core) use them; already public |
| ChatSurface.kt | `TicketTag(a, state)`, `CenterHeader(.., state)`, `NoticeStrip(state)`, `SpawnRoleRow(state, controller)`: `state.ticket / ticketBranchFor / sessionExecutor / agentTitle / agentSecondaryId / noticesForSession / roles / roleBindings / spawnRole / pickedModel / waiting / openPrompts / openQuestionSessions` | hole: a `ChatContext` (see the controller close) |
| ChatSurface.kt | `InterruptButton(.., controller)`, `PromptBox(.., controller)`: `controller.interrupt / kill / removeSession / resume / restartSession / answerPrompt / requestCommands / refreshModelCatalog / pickSpawnRole / state` | hole: the same `ChatContext` |
| all moved | the model and wire types, `Palette`, `Mono`, the controls, `AgentAvatar`, `RoleIcon` | kit |

Core no longer holds any chat drawing code; what it keeps of the chat is the window's state (`ChatPanelState`,
`ChatAttachment`, `ComposerTags`) and the kit renderers the markdown engine owns.
Proof: default shots 32/32 at 0 px; opt-in `assistant,newAgent` base (5424a3df) vs head 4/4 at 0 px, both rendered
with blur off. agentHqUi chat-scoped tests and agenthqAppUi jvm tests pass; wasm, Android compile. Two core jvmTest
failures are not this change: HandCursorGuardTest (the Text ratchet, above) and ShellOverridesTest "a module replaces a
placeholder" (`infantry.shell.configEntry` locked), which fails the same way on base 5424a3df.

## Controller close (S4d)

**Decision: gate, not migrate wholesale.** Moving every remaining `(state, controller)` pane onto ShellContext is
migrating the whole app shell (chat composer and its `ChatPanelState`, sidebar, project picker, title bar, assistant,
processes, diff, the right panel's tabs): the inventory reads about 260 distinct members across 21 files that name
`Controller` and 25 that name `AppState`. Done as ShellContext getters that would be ~150 additions, most of them read
by exactly one pane: the pile of one-off getters this count is meant to catch. Instead the door closes at the tier:

- `LocalShell` is `internal` to core. Core's own blocks (connect, servers, config, config entry, server update, switch
  error) keep it; no module can name it.
- `coreShell` (public, `@Composable`) is the window's `Shell` in a **core** module's composition and **null in a
  community module's** (its tab, panel section, interceptor, slot block, shell override, injection). It is the block
  twin of `TabContext.coreController`, which already gates the tabs the same way. `LocalCoreTier` (internal) carries
  the tier; `ModuleTier` sets it at each of the six community render sites (`TabFactory.Content` tab and slot blocks,
  `TabInterceptor.atTier`, `PanelSection`, `OverrideBlock`, `InjectedBlock`). A community wrap's inner node (`TierBlock`)
  is drawn back at the tier around it, so the app UI node it wraps still draws.
- The app UI reads it as `appShell` (Infantry_AppUi/ShellAccess.kt, `checkNotNull`: its blocks are fills, which only a
  core module may register, so a null is a wiring bug like `appController`'s).
- Proof: CoreTierGateUiTest, in the real window: a community injection and a community wrap of
  `infantry.shell.center` see `coreShell == null` and a ShellContext; a core injection sees the Shell; the wrapped
  centre (app UI, `appShell`) still draws.

So `LocalShell.controller` is no longer reachable by modules: a community module cannot name `LocalShell`, and
`coreShell` is null for it. Compile time cannot enforce more: Kotlin `internal` is per Gradle module and the app UI is
its own module (`-Xfriend-paths` would hand it all of core's internals and blind the per-module leak check S4a-c rely
on). `Shell`, `Controller` and `AppState` stay public types; reaching an instance needs the core tier.

**Migrated to ShellContext** (what a community module's equivalent block would need, so a drop-in can be written):

| Block | Reads now |
|---|---|
| ShellQuestionPill | `questions.open / dismissed / cancel / show / showAll`, `session.selectedAgentId / hasSession`, `window.showChat` |
| StatusContractWarning | `link.live / contract` (a) |
| StatusVersionMismatch | `link.live`, `window.serverMismatch` |
| StatusShortcutHint | `layout.compact` (a) |
| StatusBuildStamp | `layout.compact`, `window.appBuild / openConfig(ABOUT)`, `link.daemonBuild` |
| StatusConfigCog | `window.openConfig()` |

**Additions (b)**, all with defaults, two new sub-interfaces and two members on existing ones:

| Sub-interface | Members | Count |
|---|---|---|
| `WindowContext` (new) | `appBuild`, `serverMismatch`, `showChat`, `showTab`, `openConfig(tab)`, `openConnection` | 6 |
| `QuestionsContext` (new) | `open`, `dismissed`, `cancel`, `show`, `showAll` | 5 |
| `LinkContext` | `daemonBuild` | 1 |
| `SessionContext` | `hasSession(id)` | 1 |
| `ShellContext` | `window`, `questions` | 2 |
| **total** | | **15** |

**Stays on `appShell` (core tier), each a hole for a later step**, because the pane it feeds takes `(state,
controller)` and is the app's own:

| Block | What it hands on |
|---|---|
| ShellCenter | `ChatPanel(state, controller, chat)`, `chatPanelHeader`, `CenterTabBar(state)`, `tabs.Content(tabContext)` |
| ShellLeftPanel, ShellCompactDrawer | `Sidebar(state, ...)`, `controller.select / removeSession / assignSessionProject / showCompactScreen`, `PanelSections` |
| ShellRightPanel | `tabs.title(it, state)`, `state.panels`, `tabContext` |
| ShellHeader | `TitleBar*(state)`, `ProjectPicker(state, controller)`, `controller.togglePanel`, `switcher`, `assistantPanel` |
| ShellAssistant | `AssistantPanel(state, controller, assistantPanel)`, `AssistantPopupChat(.., assistantChat)` |
| ShellDiff | `openDiff(state, controller, ui.diffTarget)` |
| StatusConnStatus, StatusLastFrame | `state.connection` (state, detail, provider, lastFrameAt) |
| StatusProcessesBadge | `ProcessesBadge(state, controller)` |
| ShellSound, ShellVoice | `IncomingCommsSoundEffect(controller.state)`, `controller.spoken`, `ui.flags` |

The core tabs (Attention, Epics, Pods, Profiles, Prompts, Review, Roles, Skills, Stats, Triggers, Usage) keep passing
`TabContext.appController` to their `(state, controller)` panes: the same tier gate, unchanged since S4.

**Verdict.** 15 additions in two coherent sub-interfaces (the window's chrome actions, the owner's questions) plus two
natural fields (`daemonBuild`, `hasSession`): good, not duct tape. What is **not** done is the drop-in for the
heavy panes: a community module can replace the status bar or the question pill on ShellContext alone, but not the
chat, sidebar, title bar or assistant. That needs a ChatContext (the composer's submit/attach/resume off `ChatPanelState`,
~30 members), an AgentsContext (~12), a ProcessesContext (~5) and the title bar's kit taking views instead of
`AppState`; each is its own ticket and is listed above so it can be counted when it is taken.

Proof: default shots 32/32 at 0 px; opt-in `config,compact` base (5424a3df) vs head 18/18 at 0 px (blur off both).
Core jvmTest 744 and agentHqUi scoped 241 (shell, header, status, questions, diff, drawer, assistant, panels, plugins,
tabs, registry, overrides, injections, config, chat, voice, sound, CoreTierGateUiTest) pass but for three failures that
fail the same on base 5424a3df: HandCursorGuardTest (Text ratchet), ShellOverridesTest and InjectorUiTest "a module
replaces the frame placeholder" (both: `infantry.shell.configEntry` is locked). App UI jvm, wasm and Android compile.

## Cleanups and the empty-shell proof (S4d)

- **Registered view names the filler.** `NodeRegistry` lists a filled slot, and every node under it, as the module that
  fills it (`ChildSource.FILL`, "Fill", today always `Infantry_AppUi`) instead of "Infantry core" / "Core tree"
  (`TabFactory.fillModules`, by slot id). The tab-tree source reads "Tab tree", no longer the pre-S4 "CoreShell.tabs".
  NodeRegistryTest gains a test for it; the config settings' own nodes now read as the app UI's (they are in its
  fill). The opt-in `registry` shots change by exactly those labels (base vs head below); the default set does not
  draw the Registered view.
- **Empty shell** (app UI module absent): opt-in `empty` base vs head, below. The skeleton, connect / servers and the
  Configuration button with the trust-only Config draw as at base.

## S4d count, by sub-interface

| Sub-interface | Step | Members | Count |
|---|---|---|---|
| `TicketsContext` (new) | board | `all`, `visible`, `updates`, `epics`, `error`, `ticket`, `column`, `create`, `edit`, `delete`, `move`, `reorder`, `startNext`, `loadEpics`, `uploadImage` | 15 |
| `WindowContext` (new) | close | `appBuild`, `serverMismatch`, `showChat`, `showTab`, `openConfig`, `openConnection` | 6 |
| `QuestionsContext` (new) | close | `open`, `dismissed`, `cancel`, `show`, `showAll` | 5 |
| `ShellContext` | board, close | `tickets`, `window`, `questions` | 3 |
| `SessionContext` | board, close | `projects`, `hasSession` | 2 |
| `RolesContext` | board | `profiles` | 1 |
| `LayoutContext` | board | `compact` | 1 |
| `LinkContext` | close | `daemonBuild` | 1 |
| **total** | | | **34** |

The chat step added none (it moved code; two kit helpers and the image viewer host became public, see "Chat moved").

**Plainly:** 34 additions, 26 of them in three new sub-interfaces that each describe one domain (the backlog, the
window's chrome, the owner's questions), 3 accessor fields, and 5 single members that are natural fields of an existing
domain (`projects`, `hasSession`, `profiles`, `compact`, `daemonBuild`). No one-off getter for a single pane. That is
"few coherent additions", **but it is not the whole drop-in path**: `LocalShell.controller` is closed by tier (a
community module cannot reach it), not by migration. The chat, sidebar, title bar, assistant, diff, processes badge and
the connection items still hand `(state, controller)` to the app's panes through `appShell`, and the core tabs through
`appController`. A community module can replace the board, roles, notes, git, run, changes, question pill and status bar
on ShellContext alone; it cannot yet replace those. Closing them honestly is ChatContext (~30), AgentsContext (~12),
ProcessesContext (~5) and view-typed title bar kit: roughly another 50, which should be judged by the same count when
taken.

Proof: default shots 32/32 at 0 px. Opt-in `empty` base (5424a3df) vs head 2/2 at 0 px (`shell-empty-1400`,
`shell-empty-config-1400`). Opt-in `registry` base vs head: `config-registry` differs by 3552 px, all of them the source
chip of the Notes, Run and Git rows ("CoreShell.tabs" now "Tab tree") and the chips after it on those rows; checked by
eye, nothing else moves. No committed baseline holds that shot. NodeRegistryTest, RegistrySectionUiTest,
CoreShellTreeTest, ConfigDialogOverridesUiTest pass. All renders with blur off (renderTabShotsForDiff).
