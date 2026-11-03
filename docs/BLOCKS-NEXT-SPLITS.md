# BLOCKS-NEXT-SPLITS: what is left to split (survey, read-only)

Base: dev 5958392. Scope: `infantryUi`, `infantryUiCore` (commonMain + androidMain) and `plugins/core/*`. Method: an awk brace-depth
counter over every `@Composable fun` in the 256 non-test `.kt` files (first line of the function to its closing brace,
comments and blank lines included). Test code and build output are excluded. Nothing was built.

Result: **no composable in `infantryUi` or `plugins/core/*` reaches 80 lines** (`UpdatePrompt` is 69; the plugin files are
17-34 lines of manifest and call a pane that lives in `infantryUiCore`). All 23 composables of 82+ lines are in `infantryUiCore`.

## 1. Every @Composable of about 80 lines or more

Node status: "leaf" = a node whose insides are one composable; "none" = not reachable by node id. Locked = in `CoreShell.locked`.
Files are under `infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/`.

| Lines | Function (file:line) | Node status | Natural children |
|---|---|---|---|
| 239 | `ConfigDialog` (ConfigDialog.kt:74) | leaf `infantry.shell.config`, **locked** | tab strip, search box, one child per `FlagSection` (theme, blur, markdown, ..., plugins, secrets, pair, about, updates, logs), scroll cue |
| 219 | `RunPane` (RunPane.kt:117) | none (module tab Infantry_Run) | command pills row, run toolbar (start/stop/type), output lines, `CommandEditor`, refusal notice |
| 186 | `App` (App.kt:226) | none: builds the window state and wires `CoreShell.root`; not a view | stays (state owner and effects) |
| 168 | `ProjectGitPane` (ProjectGitPane.kt:71) | none (module tab Infantry_Git) | base branch, directory, working tree, diff, branches, pipeline, history (the file already has `// ----` banners for each) |
| 152 | `MemoryPane` (MemoryPane.kt:75) | none (module tab Infantry_Notes) | scope tabs, search, note list, note modal, refusal strip |
| 133 | `RoleForm` (RoleManager.kt:103) | inside leaf `infantry.shell.roles` (not locked) | id/name, icon picker, effort, instructions, delete confirm |
| 131 | `BoardPane` (Board.kt:201) | body of `infantry.shell.board.pane` (leaf) | filter row, Start next, status columns (each: header chip + cards) |
| 123 | `TicketEditForm` (Board.kt:604) | inside `board.pane`, no node | fields, images (`TicketImagesSection`), role/provider/model, Save row |
| 115 | `ProjectChangesPanel` (ProjectChangesPanel.kt:195) | none (module tab Infantry_Changes) | branch select, worktree select, worktree header line, changes list |
| 113 | `SkillsPane` (SkillsPane.kt:66) | none (module tab Infantry_Skills) | search, list, skill card, editor |
| 108 | `Button` (Clickables.kt:63) | primitive wrapper, not a layout | stays |
| 107 | `PromptCard` (PromptCard.kt:59) | none (card in Prompts tab) | title row, body, variables, actions |
| 106 | `PromptsPane` (PromptsPane.kt:58) | none (module tab Infantry_Prompts) | search, list of cards, editor |
| 104 | `ExecutorPicker` (ExecutorPicker.kt:55) | inside `newAgent.executor` (leaf) | harness, provider, model dropdowns |
| 103 | `PacingDialog` (UsagePacingSection.kt:142) | inside `config` (locked) | stays |
| 100 | `SwitchAgentPicker` (SwitchAgentPicker.kt:66) | leaf `chat.switch` | role list, executor picker, confirm |
| 99 | `QuestionPanel` (QuestionWindow.kt:103) | inside `chat.transcript`, no node (6f) | question text, option rows, other field, answer row |
| 96 | `TicketCard` (Board.kt:469) | inside `board.pane` | per-ticket template (section 4) |
| 90 | `PairDeviceSection` (pair/PairDevice.kt:91) | inside `config` (locked) | stays |
| 89 | `CommandEditor` (RunPane.kt:339) | inside RunPane | form rows, save/cancel |
| 88 | `BaseBranchPicker` (ProjectGitPane.kt:360) | inside GitPane | stays (child of a Git `base` node) |
| 82 | `BranchSwitcher` (ProjectGitPane.kt:253) | inside GitPane | stays (child of a Git `branches` node) |

Already node containers (their bodies are short now): header and its lead/overflow, left/right panels, center and its chat,
board, assistant, diff, newAgent, footer (status bar). Locked: window, images, dock, frame, body, panes, windowLayer,
connect, servers, switchError, serverUpdate, config.

The 50-79 band (listed so nobody re-surveys): `EpicsPane` 72, `Sidebar` 70, `ProjectConfigDialog` 70, `TriggerForm` 68,
`ProfileForm` 68, `RoleDetail` 67, `ProjectPicker` 63, `PodsPane` 61, `RolesPane` 55, `AttentionPane` 51.

## 2. Module tabs and plugin panes

Under 6e / 6f a module tab's content is the module's own: the core has no structure there. That rule stands. What can
change is that a module publishes its **own** tree (BLOCKS-1 section 5 already addresses `<module>/<file>#<id>`), so a pane
with real sections is split by its owner without the core touching it.

| Module tab | Pane (lines) | Verdict |
|---|---|---|
| Infantry_Run | `RunPane` 219 | benefits: separable pills, toolbar, output, editor |
| Infantry_Git | `ProjectGitPane` 168 | benefits: seven independent sections; a plugin wants to add or swap one |
| Infantry_Notes | `MemoryPane` 152 | benefits moderately (scope, list, modal) |
| Infantry_Changes | `ProjectChangesPanel` 115 | benefits moderately (selectors above a list) |
| Infantry_Skills, Infantry_Prompts | `SkillsPane` 113, `PromptsPane` 106 (+`PromptCard` 107) | list + editor; benefits only with the per-item template (section 4) |
| Infantry_Epics, Infantry_Pods, Infantry_Roles | 72, 61, 55 | stay a blob (one list, one editor) |
| Infantry_Attention | `AttentionPane` 51 | stays a plugin-owned blob (one list; its header chip and overflow entry are nodes already) |
| Infantry_Agents | `Sidebar` 70 + `AgentRow` 74 | stays: the agent list is a module section, not core (6e). `AgentRow` is the second candidate for a per-item template |
| Infantry_Profiles, Stats, Usage, Review, Triggers, Cli | each under 50 (Cli has no pane) | stay blobs |

## 3. Top 8, ranked by size x chance a plugin author overrides a part

| # | Target | Why | Split sketch |
|---|---|---|---|
| 1 | `ConfigDialog` 239 (`infantry.shell.config`) | biggest, and every plugin with a setting wants a section | keep `config` and its Plugins/Secrets/ServerUpdate sections locked; make each other `FlagSection` a child `config.<entry>` so one can be wrapped, replaced or inserted beside |
| 2 | `ProjectGitPane` 168 (Infantry_Git tree) | seven independent sections, already banner-commented | `git.base`, `git.directory`, `git.tree`, `git.diff`, `git.branches`, `git.pipeline`, `git.history` as a column of children |
| 3 | `BoardPane` 131 + `TicketCard` 96 (`board.pane`) | plugins add per-ticket badges, columns, filters | `pane.filters`, `pane.columns`; a column is a template node, a card a per-ticket template (section 4) |
| 4 | `RunPane` 219 (Infantry_Run tree) | largest tab; buttons and output are what a run plugin changes | `run.pills`, `run.toolbar`, `run.output`, `run.editor` |
| 5 | `RoleForm` 133 (`infantry.shell.roles`) | role traits and icons keep growing; a trait plugin needs a field slot | `role.identity`, `role.icon`, `role.effort`, `role.instructions`, `role.delete` as a column |
| 6 | `MemoryPane` 152 (Infantry_Notes tree) | notes plugins want extra scopes and a list row | `notes.scope`, `notes.search`, `notes.list`, `notes.modal` |
| 7 | `TicketEditForm` 123 (`board.pane`, modal) | ticket fields are the other thing plugins extend | `ticket.fields`, `ticket.images`, `ticket.executor`, `ticket.actions`; form state in a composition local like `LocalNewAgentDraft` |
| 8 | `ProjectChangesPanel` 115 (Infantry_Changes tree) | selectors above a list; a VCS plugin swaps the selector | `changes.branch`, `changes.worktree`, `changes.header`, `changes.list` |

Just below: `QuestionPanel` 99, `PromptsPane`/`PromptCard` 106/107, `SkillsPane` 113, `SwitchAgentPicker` 100. `App` (186),
`Button` (108), `PacingDialog` and `PairDeviceSection` (inside locked `config`) should not be split.

Cautions: (a) items 2, 4, 6, 8 are module tabs, so they need the module-owned tree path of section 2, not a core change;
(b) the overlays (items 1, 3, 5, 7) need an opt-in 0 px shot each, as L10/L11 did, because none of the 32 default shots opens them.

## 4. The toast card as a per-item template node (note 6j)

The resolver resolves a fixed tree once per module set, so a node cannot stand for "one per toast"; it would need a
**template node** (`infantry.shell.toast.card`) whose subtree is resolved once like any other, then drawn per item with the
item bound in a composition local (as `LocalNewAgentDraft` does), so an override of the card or one of its children applies to
every toast and reaches the item through the local, never through a child id. It also needs an addressing rule (an override
targets the template id, never an instance) and a guarantee that an override cannot change the list's order or length, since
the host owns the runtime list; the same node shape would serve `TicketCard` and `AgentRow`.
