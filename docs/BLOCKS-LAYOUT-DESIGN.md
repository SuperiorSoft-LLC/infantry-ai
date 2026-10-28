# BLOCKS-LAYOUT: layout is a block type (design only)

Ticket fa41d42d. Read on feature-agenthq-mvp @ 21b5905, plus the BLOCKS-SHELL-3 tip 64de8b1 (a3923bd0, not merged yet).
Inputs: `ui/block/Block.kt`, `ui/AppShell.kt`, `ui/shell/blocks/*`, `App.kt:380-410` and `:898-929` (StatusBar), BLOCKS-1 note
(f706b748), LAYOUT-1 note (2f71cd83), superPayload `BlockComponent.kt`, `RowBlock`, `ColumnBlock`, `SectionBlock`.

Owner direction: the system knows no header/left/right/middle/footer. Layout is a block TYPE. Decomposition is continuous
and top-down, so this page fixes the **contract** and a **starting** set, not the final primitives.

## 0. What today's code already gets right, and what it still bakes in

- Right: a block is a class with `blockType`, `id`, `children`, `Render`; context comes down through `LocalShell` and
  `LocalDockSize`; a parent hands a child a `DockScope.modifier` (the centre gets `weight(1f)` this way).
- Baked in: `Edge` (TOP/BOTTOM/START/FILL/END/FILL_OVERLAY/OVERLAY/WINDOW) and `DockHost` are header/left/middle/right/footer
  under other names. The system lays the window out and the blocks only pick a zone.
- Smell: decomposition so far adds one slot per gap. BLOCKS-SHELL-2 added `children` (after the hint),
  BLOCKS-SHELL-3 added `leading` (after the status text). Each further lift would add another slot. `StatusBuildStamp`
  also carries `Edge.END`, which means nothing inside the bar.

superPayload's lesson (`BlockComponent.acceptChildren`, VariantBlock): **no children means the block draws its built-in
default; children means the children own that part.** Its Row/Column/Section blocks are thin: one element plus
`for (b in layout) b.Render()`. We keep both ideas and make them strict.

## 1. The block contract

```kotlin
abstract class Block(
    val blockType: String,          // what it is = how it behaves (registry key, JSON discriminator)
    val id: String,                 // node id: unique in its tree, stable, the override address
    val place: Place = Place(),     // how THIS node asks its parent to place it; the parent applies it
    val children: List<Block> = emptyList(),
) {
    @Composable abstract fun Render(scope: BlockScope)
    /** The decomposition / override hook. The default refuses visibly; a block that accepts children overrides it. */
    open fun withChildren(children: List<Block>): Block = refuse("$blockType takes no children yet")
}

data class Place(
    val weight: Float? = null, val weightFill: Boolean = true,  // Row/ColumnScope.weight(w, fill)
    val size: Size = Size.Wrap,                                 // Wrap | FillMax | FillWidth | FillHeight
    val align: Alignment? = null,                               // used by stack (BoxScope.align)
    val show: Show = Show.Always,                               // Always | Wide | Compact (section 5)
)

class BlockScope(val modifier: Modifier, internal val box: BoxScope?)   // today's DockScope, renamed
val LocalBlockSize = staticCompositionLocalOf { DpSize.Unspecified }    // today's LocalDockSize, renamed
```

Rules (these are the contract; the code under them can change):
1. **Child applies `scope.modifier` exactly once, on its outermost node.** A block that takes a weight emits one root
   node. A block with `Place()` may emit several (the left panel emits panel + resize handle, as today).
2. **Parent owns placement, child owns look.** The parent turns the child's `place` into the modifier, in the order
   weight, align, size (today's body is `weight(1f).fillMaxWidth()`). A parent ignores `place` fields it does not
   understand. The child never sees its parent or its siblings.
3. **A block with children draws only its own frame (background, divider, padding) plus its children.** If static
   content still sits between two children, lift it into a child block. That replaces the slot-per-gap pattern.
4. **Context goes down, never up.** App state stays on `LocalShell`; size on `LocalBlockSize`; services later on
   BLOCKS-1's `LocalBlockScope`. A block reads context; it never looks a block up by id.
5. **Nodes are immutable.** Render state lives in `remember`. An override builds a new tree through `withChildren`.
   Leaf `object`s stay objects; only a block that takes children has to be a copyable class.
6. **Node id is not the type.** The same type may appear twice under two ids. The drawer and the docked panel both draw
   the left panel content today.

How a static composable becomes a block with children, one piece at a time (pixel-identical at every step):
(a) wrap it whole as a leaf block (BLOCKS-SHELL-1 did this); (b) lift every static part of ONE container into a leaf
block in a single step, so the container becomes a pure ordered `children` list; (c) later, any leaf repeats (a)-(b).
The system never changes. Only the block that is decomposed and the tree that lists it change.

## 2. Starting set of layout blocks

| type | fields | children placed by | wraps (exact Compose) |
|---|---|---|---|
| `group` | none | transparent: a container places a group's children as its own (flattened) | nothing (a fragment) |
| `stack` | `measure: Boolean` | `place.align` via BoxScope; scope also exposes `box` for blocks that align themselves | `Box(mod)`; with `measure`: `BoxWithConstraints(mod){ provide LocalBlockSize; Box(fillMaxSize){..} }` |
| `row` | `verticalAlignment` | `place.weight/weightFill` via RowScope | `Row(mod, verticalAlignment)` |
| `column` | `horizontalAlignment`, `surface: Boolean` (core only) | `place.weight/weightFill` via ColumnScope | `Column(mod)`; with `surface`: `Column(mod.then(LocalHostSurface))` |
| `spacer` | optional fixed `width`/`height` | n/a (leaf) | `Spacer(mod)`; with `place.weight` it is the flexible gap |
| `scroll` | `axis` | as column/row | defined now, **not** in the starting tree: the frame has no scroll today (scroll lives inside leaves). Lands with the first BLOCKS-1 tree that needs it. |

Two core wrappers are not layout but are needed to express today's root exactly: `imageViewerHost` (the existing
`ImageViewerHost{}` whose children are its content) and the `surface` flag. The surface flag carries App's modifier
(fillMaxSize, modalBlur, background, focusRequester, focusable, onPreviewKeyEvent) through `LocalHostSurface`. It has to
sit on the docked column and not on the outer box, or the Board and the other overlays would blur too.

**Rejected: a `dock` primitive with edge+size per child.** It is one node for the whole frame, so the tree is shallower
(1 node instead of 5). But it is today's `Edge` again: a five-zone page that the system understands, which the owner
ruled out, and every new arrangement would need a new edge. Composition from column/row/stack costs 4 extra nodes and
one Compose call each. `size` per child is also left out: every shell block sizes itself today (panel widths come from
ShellState). It joins `Place` when the first JSON tree needs it.

Today's shell as a tree (ids keep today's names; the new layout nodes are `infantry.shell.*`):

```
infantry.shell.window            group                       (App root, inside theme + locals)
├ infantry.shell.images          imageViewerHost
│ └ infantry.shell.dock          stack       place{FillMax}                           (DockHost's outer Box)
│   ├ infantry.shell.frame       column      surface                                  (DockHost's Column)
│   │ ├ infantry.shell.header    titleBar    (section 6b: children since L6)
│   │ ├ infantry.shell.body      stack measure place{weight 1, FillWidth}              (BoxWithConstraints + Box)
│   │ │ ├ infantry.shell.panes   row         place{FillMax}
│   │ │ │ ├ infantry.shell.left    leftPanel   place{show Wide}
│   │ │ │ ├ infantry.shell.center  center      place{weight 1}
│   │ │ │ └ infantry.shell.right   rightPanel  place{show Wide}
│   │ │ └ infantry.shell.drawer  compactDrawer place{show Compact}
│   │ └ infantry.shell.footer    statusBar   (section 3)
│   └ assistant, toast, board, diff, sound, voice, questions   (CoreShell order; they align themselves, as today; board: 6g)
└ infantry.shell.windowLayer     group       config, serverUpdate, roles, servers, switchError, connect (DockWindow order)
```

Every line maps to the Compose call DockHost/App emit today, with the same modifier chain in the same order. That
1:1 mapping is what makes the swap 0 px. **Nothing in today's shell fails to fit this model.**

## 3. Status bar: two slots become one ordered array

`statusBar` is a layout type: its frame is `HorizontalDivider` + `Row(fillMaxWidth, height 24, Panel bg, padding h12,
CenterVertically)`, and it renders `children` with RowScope weights. The static parts become leaf blocks, ids
`infantry.shell.footer.<name>`:

```
connStatus       place{weight 1, weightFill false}   "● daemon X contract Y" / "● state: detail"
lastFrame                                            owns the 1 s ticker (today the whole bar recomposes each second)
contractWarning                                      "   unexpected contract version" (emits nothing when fine)
versionMismatch                                      SERVER_MISMATCH_TAG kept
processesBadge                                       StatusProcessesBadge (BLOCKS-SHELL-3)
gap              spacer place{weight 1}
shortcutHint     place{weight 1, weightFill false}   reads state.panels.compactWindow, as today (see 5)
buildStamp                                           StatusBuildStamp, Edge dropped
configCog                                            the 24dp cog box, "Configuration" semantics
```

`CoreShell.statusBarChildren`, `statusBarLeading`, `StatusBar(..., children, leading)` and the composable itself go
away. A plugin indicator is one `before`/`after` against one of these ids.

## 4. Geometry without roles

A child declares geometry in `place`, as data. The parent applies it, and only the parent knows its own axis: a row
reads weight as width and a column reads it as height; a stack reads align. No container knows what a child is, and no
child knows what its container is. This is the Compose idiom (`Modifier.weight` is declared at the child and resolved
by the parent scope), so the blocks stay thin. The alternative was a per-child placement map on the parent. It was
rejected because an inserted plugin node would then need a second edit on its parent.

## 5. Compact / narrow behaviour, per block

- Breakpoint: `PanelLayout.COMPACT_BELOW_DP = 840f` (the "600dp" comments in `ShellLeftPanel.kt` and LAYOUT-1's
  TitleBar remarks are stale). "Compact" means the nearest `LocalBlockSize.width < 840`. Only `stack(measure)` provides
  `LocalBlockSize`, so a tree decides where it is measured; the system does not.
- `place.show = Wide|Compact` is pure show/hide, and the parent applies it (the node leaves composition). The left
  panel, the right panel and the drawer move to it; their `!compact &&` checks go. The blocks still read compactness
  from context for their state (`panels.forWindow(compact)`, `setCompactWindow` in the centre).
- Anything richer is the block's own behaviour, not a system rule: the drawer width (88%, max 420dp), the assistant
  popup becoming a sheet, the header overflow. For the header: the overflow "More" button is a node with `show
  Compact`, and its entries are its **own children** (explicit nodes), not siblings collected by magic. A plugin header
  item that should overflow therefore adds two nodes: one `show Wide` in the bar, one in the overflow. LAYOUT-1 said
  compact needs its own order. Read again, the compact order is the wide order with the overflow node in front, so
  `show` covers it. The header ticket must verify this on the 360/800 shots.
- Kept for pixel identity: `shortcutHint` and `assistant` read the `state.panels.compactWindow` flag, which the centre
  sets in a `LaunchedEffect` (one frame late), because the footer and the overlays sit outside the measured body. A
  later cleanup can move `measure` up to `dock` (only width is ever read, and the width is the same there) and drop the
  flag. That cleanup is not part of the 0 px migration.

## 6. Plugin overrides and inserts by node id (BLOCKS-1 §5, 7a062c28)

- Address: `infantry.shell#<nodeId>` for the core tree, `<module>/<file>#<id>` for module trees. The address form and
  the ops `replace | remove | wrap | before | after | move` are BLOCKS-1's. This adds `into` (parent id, first/last/index)
  and `place` (patch a node's Place only, e.g. `show Wide`; it is cheaper and safer than replacing a Kotlin leaf).
- A plugin inserts **nodes**. A node's type is a core primitive or a type the plugin registers. A plugin may not
  re-implement a primitive **type** (a global `row`): that would change every tree, other plugins' included, and its
  blast radius has no bound.
- Resolution runs when the module set changes, not per frame: core tree, then the modules in Kahn order (the last one
  wins). An unknown target, a refused `withChildren`, a duplicate id or a locked node is refused and listed in Settings >
  Plugins, never applied silently. Community modules need `allowOverride` to touch the core tree. The demo lock freezes
  everything.
- `locked` nodes refuse remove/replace/move/wrap, and nothing is ever injected into them (docs/BLOCKS-INJECTORS.md 2b, since J1: a locked node such as `windowLayer` or `config` would otherwise let a plugin draw a fake prompt over the real one). A locked node offers a place to add things only through an explicit unlocked child, an open slot.

### 6a. As built in L5 (80690a98)

L5 ships `replace` and `wrap` only; `remove | before | after | move | into | place` come later on the same resolver.

- **Node ids are API from L5 on.** The ids pinned by `CoreShellTreeTest` (section 2's tree plus the footer's
  `infantry.shell.footer.<name>` children, since L6 the header's `infantry.shell.header.<name>`, since L9a the right panel's `infantry.shell.right.<name>`)
  are never renamed. The test also checks that every id is unique.
- module.json: `"shell": [{ "node": "<id or infantry.shell#<id>>", "name": "<registered name>", "mode": "replace|wrap" }]`;
  the Kotlin entry registers `UiRegistrar.shell(name, BlockOverride { scope, inner -> })`. `inner` is the block being
  wrapped (null for a replace).
- Trust (`ModuleResolver`, plugin side): a community module needs the node id in its `allowOverride`; core needs no
  grant. The rest (`BlockResolver`, UI side, which holds the tree): an unknown id or mode is ignored, a `locked` node
  (`CoreShell.locked`, the two locked rows of section 7) is refused, and so is a replace that would remove a locked node
  below it. Each one is a line in `TabFactory.problems` (Settings > Plugins, and the `[plugins]` log).
- Resolution: once per `TabFactory` (the module set), bottom-up: a node's children first, the node rebuilt through
  `withChildren` only if a child changed, then its own overrides in load order. A wrap wraps what is there (the last
  loaded is outermost); a replace drops it (the last replace wins, logged as "overriding"). The override node keeps the
  id and `place`, so its parent places it as before.
- No flag: with no override the resolver returns `CoreShell.root` itself (the same object), so the window is the L4
  window by construction. The `blocks.enabled` switch of the L5 row in section 8 was not needed.

### 6b. As built in L6 (032b2c61)

`infantry.shell.header` is a `titleBar` layout block: its frame is the 36dp Panel row (it provides
`LocalTitleBarCompact`) plus the divider, and its items are children, left to right, `infantry.shell.header.<name>`:

```
lead            titleBarLead    left toggle, logo + name, project slot, avatar strip / compact drag + Assistant, then the overflow (children since L7, 6c; overflow L8, 6d)
attention       attentionChip   "N need attention" + its 10dp gap; wide only (compact lists it in the overflow)
toggleRight     panelToggle     side panel toggle + its gap (8dp wide, 4dp compact)
server          serverSwitcher  SWITCH-1 dropdown (nothing without a switcher)
connection      connectionChip  state + URL (wide) / state only (compact)
windowControls  windowControls  8dp + minimize/maximize/close with a window chrome, else the 12dp end
```

- Each item owns the gap next to it, so a replaced or wrapped child keeps the bar's spacing.
- `lead` is not a weighted wrapper. Its items, the drag area's `weight(1f)` included, go straight into the bar's row
  through `BlockScope.row` (core only, set by a row parent). A weighted wrapper measured the right-hand items first and
  moved the clipping of the overflowing 360dp bar from the window controls to the lead (`chrome-compact-360*` shots).
- None of these nodes is locked: the header root and every child stay overridable (section 7).
- The standalone `TitleBar(...)` composable draws the same frame and items in the same order (tests use it).

### 6c. As built in L7 (b1e819fb)

`infantry.shell.header.lead` is a container (`titleBarLead`) of child blocks, left to right,
`infantry.shell.header.lead.<name>`:

```
toggleLeft  panelToggle  6dp + the agent list toggle
brand       brand        logo + name, a drag area; owns 6dp before (after the toggle) and 10dp after
project     projectSlot  M2-7 project picker + "+" New Project; wide only (compact lists it in the overflow)
agents      agentStrip   the drag area with weight(1f): wide, the avatar strip with the Assistant last;
                         compact, the plain drag area + Assistant (the overflow button is its own node since L8, 6d)
overflow    titleBarOverflow  since L8: the compact "More" button and its menu, show Compact (6d)
```

- The lead draws no node of its own: in the bar's row it renders its children straight into that row
  (`BlockScope.row`), so `agents` keeps its `weight(1f)` and the 360dp bar still clips at its right end.
- The window drag modifier sits on the same boxes as before (`brand`'s row, `agents`' drag area).
- None of these nodes is locked. The Assistant stays inside `agents` in the wide bar: a node of its own would move it
  out of the weighted strip to the strip's right end.

### 6d. As built in L8 (099cee2f)

`infantry.shell.header.lead.overflow` (`titleBarOverflow`, `place.show = Compact`) is the compact bar's "More" button,
after `agents`. Its menu entries are its own children, top to bottom, `infantry.shell.header.lead.overflow.<name>`:

```
project     projectSlot      the M2-7 project picker
attention   attentionEntry   "N need attention", while any is open
connection  connectionEntry  the divider before it (it owns that gap) + the connection URL
```

- The `titleBar` frame now provides `LocalBlockSize` (the bar's width), so `show Wide|Compact` on a header node splits
  at the same width as `LocalTitleBarCompact`. The header sits outside the measured body, so before L8 a header node's
  `show` always read "wide".
- An entry closes the menu through `LocalTitleBarOverflowDismiss`; an entry a plugin adds can do the same.
- A replace or wrap keeps the node's `show Compact` (the override keeps the id and `place`). None of these nodes is locked.
- The Assistant stays inside `agents`; the drag modifier stays on the same boxes. 0 px: diffTabShots 32/32.
### 6e. As built in L9a (31521aed): the right panel's insides

`infantry.shell.right` is a container (`rightPanel`, `show Wide`) of child blocks. It draws nothing while the panel is
hidden (PANEL-TOGGLE-1), and no node of its own while shown: in the panes' row its children go straight into that row
(`BlockScope.row`), as the header lead's do (6c).

```
resize       resizeHandle    the 7dp drag edge (↔ cursor, grip); drag left = wider
body         rightPanelBody  the column at the dragged width (tag right-panel): the module sections (PLUGINS-3) around
                             the tab area, which takes the free height and holds body's children, top to bottom:
  body.strip   tabStrip      the right tab strip (scroll arrows, fade) and the divider under it
  body.tab     tabContent    the picked tab: TabFactory.Content (container slots, interceptors, Community badge)
```

- The module sections stay what they were: `PanelSections` entries, ordered around the tab area by `Panels.TABS_ORDER`.
  They are not nodes (a module adds them through its manifest, not by node id).
- `strip` owns its divider. Its placement modifier goes on the strip's row, not on the divider.
- `tab` has no node of its own (the module draws its tab's content as it is). A placement other than the default gets a
  Box to wear it; with the default the content is drawn exactly as before.
- The compact drawer draws the same panel: its tab area renders the window's resolved `body.strip` and `body.tab`, found
  by id in `TabFactory.shellRoot` (inside wraps too), so a module's strip or tab shows in the drawer as well. An override
  of `body` itself, or of `resize`, applies to the docked panel only. The drawer has its own frame (Close row, width).
- The left panel is not split: its insides are only module sections (the agent list is Infantry_Agents' section), so
  the core has no fixed structure there to make nodes of.
- None of these nodes is locked.

### 6f. As built in L9b (da799855): the centre pane's insides

`infantry.shell.center` is a container (`center`, `weight 1` in the panes' row): the column tagged `center-pane`, whose
children it draws straight into that column, top to bottom. It keeps the MOBILE-LAYOUT-2 compact-window effect.

```
chat         chatPanel        weight 1: the main window's chat (CHAT-PANEL-1) and its effects; children top to bottom:
  chat.header      chatHeader         the session header with its Switch agent chip
  chat.tabs        tabStrip           the centre tab strip (its placement goes on the strip's row)
  chat.transcript  chatTranscript     weight 1: the picked centre tab (Chat = transcript, the inline pending question,
                                      the Activity box) and the to-do / reasoning side slot; its Box wears the placement
  chat.notices     noticeStrip        the latest notice for this session or the window
  chat.spawn       spawnRows          New agent mode only: the spawn role row, then the provider and model picker
  chat.switch      switchAgentPicker  the inline Switch agent picker, while open for this session
  chat.composer    promptBox          the composer (PromptBox)
newAgent     newAgentDialog   the New agent modal and its catalog refresh (a dialog: no node in the column)
```

- The chat keeps one implementation. `ChatPanel(...)` keeps its signature (the Assistant popup and the tests host it as
  before) and draws the same sections, which live in ChatPanel.kt on a `ChatPanelHost` (`chatPanelHost(...)` reads the
  session and runs the panel's effects). The `chat` node builds the host and hands it to its children through a
  composition local; a chat child drawn outside `chat` draws nothing.
- Sections that had no node of their own (header, notices, spawn, switch, composer) draw exactly as before with the
  default placement; any other placement gets a Column to wear it (`spawn` has two rows, so not a Box).
- The question panel stays inside `chat.transcript` (it is the transcript's slot in OutputPane), not a node: the Chat
  tab's content is a module tab (TabFactory), as the right panel's `body.tab` is.
- A replace or wrap of a `chat.*` node applies at every width; the compact window draws the same centre.
- None of these nodes is locked. 0 px: diffTabShots 32/32 after every step.


### 6g. As built in L10 (76ac155c): the board modal's insides

`infantry.shell.board` is a container (`boardModal`, an overlay in the dock): while the board is open it draws the
modal's frame (backdrop, scrim tap = close, the rounded panel tagged `board-modal`, Esc, focus), and its children go
straight into the panel's column, top to bottom, `infantry.shell.board.<name>`:

```
header   boardHeader   the title row (title, active project, close) and the divider under it; its placement goes on the row
pane     boardPane     weight 1: BoardPane (filters, columns, tickets) in its padded box, which wears the placement
```

- The frame stays the board's own. `BoardModal(...)` keeps its signature and draws the same three pieces
  (`BoardModalFrame`, `BoardModalHeader`, `BoardModalPane` in BoardModal.kt). The node hands its children "open this
  agent" and "close" through a composition local; a board child drawn outside the board draws nothing.
- A module's child can close the board the same way the header does: the close it gets is the frame's dismiss.
- None of these nodes is locked.
- 0 px: none of the 32 shots opens the board, so the board has its own opt-in shot: `renderTabShotsForDiff
  -PtabShotsExtra=board` renders only `board-open` (the default run is unchanged, still 32). Each step diffed that shot
  against a render of the base (e016c3f) with `diffTabShots -PtabShotsCurrent=... -PtabShotsBaseline=...`: 0 px, plus
  diffTabShots 32/32.
- The L10 survey's other candidates, for later slices: the Assistant popup (resize edge, title row, the shared ChatPanel;
  its full and docked modes), the diff modal, the New agent dialog and the role manager. None of them is in the 32 shots
  either, so each one needs an opt-in shot like the board's. `config` is locked and large (its Plugins tab must stay
  locked). `connect`, `servers`, `switchError` and `serverUpdate` stay locked and are not split. The centre and right
  tab contents are module tabs (TabFactory), and the left panel has only module sections (6e), so neither has core
  structure to make nodes of.

### 6h. As built in L11 (9376cac5): the Assistant popup, the diff modal and the New agent dialog

Three more overlays become containers, as the board did in L10 (6g). In each one the frame stays the overlay's own,
the children are drawn straight into the overlay's row or column, and the node hands its children what they read
(render-time values) through a composition local. A child drawn outside its container draws nothing. Actions (close,
Add, prev / next) stay explicit callbacks.

```
infantry.shell.assistant  assistantPopup  open only: AssistantPopupFrame (the row at the dragged width, or full size
                                          expanded or compact; border; the first ask that makes the Assistant); children
                                          left to right in that row:
  resize       resizeHandle     the docked popup's drag edge; expanded or compact it draws nothing
  body         assistantBody    weight 1, fill height: the popup's column; children top to bottom:
    body.header  assistantHeader  the title row (name, expand / dock, close) and the divider; its placement goes on the row
    body.chat    assistantChat    weight 1: the shared ChatPanel, or "Starting the Assistant..." until the daemon made it

infantry.shell.diff       diffModal       while a diff is open: DiffModalFrame (scrim, scrim tap = close, the rounded panel
                                          tagged diff-modal, Esc, focus); children top to bottom in its column:
  header       diffHeader       the title row (path, status, +/- counts, prev / next / close) and the divider
  body         diffView         weight 1: the diff (gutters, bands, sideways scroll) or its notice; its Box wears the placement

infantry.shell.center.newAgent  newAgentDialog  while open: NewAgentDialogFrame (dialog, title, Add and its hint, Cancel,
                                          and the draft); children top to bottom in its scrolling column (10dp apart):
  role         newAgentRoles    step 1: the role catalog (search, the grid)
  executor     newAgentExecutor step 2: harness, provider and model (closed until a role is picked)
  error        newAgentError    Add's last refusal, nothing otherwise
  task         newAgentTask     step 3: the first task
```

- The public composables keep their signatures and draw the same pieces: `AssistantPopup` (AssistantPopupFrame / Resize /
  Header / Chat), `DiffModal` (DiffModalFrame / Header / Body) and `NewAgentDialog` (NewAgentDialogFrame and the four
  steps). `ProjectDiffModal` reads `openDiff(...)`, which runs the diff's load effects and returns what the modal shows.
  The `diff` node uses the same function.
- The Assistant follows the right panel's shape (6e), `resize` + `body` with `body.*`, because its header and chat share
  a column inside the popup's row.
- The diff modal has no file list of its own (the Changes tab lists the files and opens the modal), so its children are
  the title row and the diff view.
- The New agent draft (model, update, last refusal) lives in the frame, because Add reads it too. The steps read it
  through `LocalNewAgentDraft`.
- None of these nodes is locked.
- 0 px: none of the 32 shots opens these overlays, so they have opt-in shots, as the board does: `renderTabShotsForDiff
  -PtabShotsExtra=assistant,newAgent,diff` renders `assistant-docked`, `assistant-full`, `newAgent-open`,
  `newAgent-role` and `diff-open` (a temp git repository with one modified file). The default run is unchanged, still 32.
  mask.txt masks the chat's message clocks behind the New agent dialog for the two `newAgent-*` shots, by name. Each step
  matched its base render 5/5 at 0 px (base f63fb27, step 0 of L11), plus diffTabShots 32/32.

### 6i. As built in L12 (9dfbf408): the role manager

The role manager (Configuration > Agents > Manage roles) becomes a container, as the overlays of 6h did. The frame stays
the dialog's own, the pieces are drawn straight into its scrolling column (8dp apart), and the page shown (list or
form) reaches them through a composition local. The form is itself a container: its frame owns the fields as typed, the
load of the project's notes for the role, and save / delete; its pieces read the draft through a second local.

```
infantry.shell.roles   infantry.shell.roles  open only: RoleManagerFrame (dialog, title "Roles", Done, the page shown);
                                            children top to bottom in its column:
  hint           roleManagerHint   the line on when changes apply
  list           roleList          the list page: New role and a row per role; nothing while a role is open
  form           roleForm          the form page while a role is open (nothing on the list): RoleFormFrame (fields,
                                   notes load, save, delete, draws nothing itself); children straight into the same column:
    form.title     roleFormTitle     "New role" / "Edit <name>", and the older-daemon notice
    form.identity  roleFormIdentity  id (a new role only), name, description, role notes, colour, icon
    form.executor  roleFormExecutor  default provider and model, effort
    form.traits    roleFormTraits    the trait checkboxes
    form.notes     roleFormNotes     the project's notes for the role, read-only (an existing role only)
    form.actions   roleFormActions   the last refusal, Delete / Back / Save, and Delete's confirmation
```

- `RoleManagerDialog` keeps its signature and draws `RoleManagerFrame` and the same pieces (`RoleManagerHint`,
  `RoleManagerList`, `RoleManagerForm` with `RoleFormTitle` ... `RoleFormActions`).
- A piece that draws several items into the column puts its placement on one item that is always drawn: New role, the
  form title, the name field, the executor, traits and notes headings, the button row.
- Save, delete and back are callbacks on the draft (`RoleFormDraft.onSave / onDelete / onDone`); the frame runs them.
- None of these nodes is locked.
- 0 px: none of the 32 shots opens the role manager, so it has an opt-in shot set: `-PtabShotsExtra=roles` renders
  `roles-list`, `roles-new`, `roles-edit` and `roles-edit-end` (scrolled to Save). The default run is unchanged, still 32.
  The step matched its base render 4/4 at 0 px (base 30262e4, step 0 of L12), plus diffTabShots 32/32.

### 6j. As built in L14 (194e27d8): the Git tab

The Git tab (Infantry_Git) is the first module tab whose content becomes a tree (docs/BLOCKS-NEXT-SPLITS.md, top-8 item
2). Its tree is not in `CoreShell.root`: the tab draws it. `CoreShell.tabs` lists such core tab trees (only `ShellGit`
now). `TabFactory` resolves each one once with the shell overrides that name one of its nodes, and the rest go into the
window tree as before. The tab draws `tabTree("infantry.shell.git")`. A module addresses these nodes as it does any shell
node (`infantry.shell.git.tree` or `infantry.shell#infantry.shell.git.tree`), and the same trust rules apply.

```
infantry.shell.git   gitPane   GitPaneFrame (the scrolling column, "GIT · <project>", the tree load when the project, its
                               directory or the link changes; with no project "Select a project..." and no section);
                               children top to bottom, straight into the column:
  base        gitBase        the base branch for new tasks (BaseBranchPicker)
  directory   gitDirectory   divider, the directory line, field, Open / Save / Clear, the last refusal
  tree        gitTree        divider, WORKING TREE and Refresh, the branch line, the changed files; on a daemon older
                             than 1.36.0 the line that says so instead
  diff        gitDiff        the picked file's diff; nothing while none is picked
  branches    gitBranches    divider, the local branches, Switch, New branch from current (BranchSwitcher)
  pipeline    gitPipeline    divider, the verification pipeline (ProjectPipelineSection)
  history     gitHistory     divider, HISTORY and a row per commit
```

- `ProjectGitPane(state, controller)` keeps its signature and draws `GitPaneFrame` and the same sections
  (`GitBaseSection` ... `GitHistorySection`), so the pane's own tests and the real-daemon tests host it as before.
- The frame owns the state the sections share: the picked file, the last refusal and the time up to which refusals are
  dismissed. Sections read it through `GitPaneHost` (a composition local). They change it only through
  `GitPaneHost.picked` and `run` (send a request, keep its refusal). A section drawn outside the pane draws nothing.
- Each section owns the divider above it, so replacing one keeps the others' gaps. `diff` has none: the tree and its diff
  read as one part. Below the tree, a section draws nothing on a daemon older than 1.36.0, as the pane did.
- A section's placement goes on its heading (the directory line for `directory`); the frame's goes on the column.
  A section draws several items into the column, so a module that wraps one draws `inner` in a Column (a Box would
  stack them).
- None of these nodes is locked.
- 0 px: the default Git shot has no project, so it shows only the frame. `-PtabShotsExtra=git` renders `git-top`,
  `git-diff`, `git-history` and `git-end` over a real repository (fixed path, fixed commit dates). The default run is
  unchanged, still 32. The step matched its base render 4/4 at 0 px (base f38ebb5, step 0 of L14), plus diffTabShots 32/32.


### 6k. L13 (79c66a61): the small overlays stay leaves

The L13 survey (base 5958392) looked at the overlays left in the dock after L11 and found none worth splitting: each one
is one small composable, below the ~60-line bar, so the nodes stay leaves (`replace` / `wrap` of the whole node already
works). No node id changed or was added.

```
infantry.shell.toast      ToastHost 18 lines (a column of 0-3 toasts) + the private Toast card 39 lines; the toasts
                          are a runtime list, not fixed children, so they are no tree of nodes
infantry.shell.questions  QuestionPill 10 lines: one Text, absent with no hidden question
infantry.shell.voice      VoiceAlerts 32 lines, mostly the speech effect; what it draws is an 8-line Box
infantry.shell.sound      IncomingCommsSoundEffect 15 lines; it draws nothing
```

- The Attention list is not a shell overlay: it is the Infantry_Attention module's tab (`AttentionPane`), and module
  tabs are not split (6e, 6f). Its header chip and overflow entry are nodes already (6b, 6d).
- The settings sections for voice and sound (`VoiceSettingsSection`, `IncomingCommsSoundSection`) live inside `config`,
  which is locked.
- If a toast card is ever made overridable, it needs a per-item template node (a node drawn once per toast with the
  toast in a local), which is a new resolver shape, not an L-step split.

### 6l. As built in L15 (2352af94): the Run tab

The Run tab (Infantry_Run) is the second core tab tree (docs/BLOCKS-NEXT-SPLITS.md, top-8 item 4), built as 6j:
`CoreShell.tabs` is now `ShellGit, ShellRun`, `TabFactory` resolves each with the shell overrides that name its nodes, and
the tab draws `tabTree("infantry.shell.run")`.

```
infantry.shell.run   runPane    RunPaneFrame (the column, "RUN", the watch that closes the editor once a save is answered);
                                children top to bottom, straight into the column:
  empty      runEmpty      "No saved commands on this daemon." and Add command, with none saved and no editor open
  toolbar    runToolbar    the command picker and its menu, Start, Interactive (desktop, 1.134.0), Stop, Add / Edit /
                           Delete, the shown run's state
  pills      runPills      one pill per run, newest first, and their clock; nothing with no run
  confirm    runConfirm    the inline delete confirm, while it is asked
  editor     runEditor     the add / edit form (CommandEditor), while it is open; weight 1
  notices    runNotices    the last refusal, an output gap, the hidden-lines count; nothing while the editor is open
  output     runOutput     the shown run's lines; nothing while the editor is open; weight 1
```

- `RunPane(state, controller)` keeps its signature and draws `RunPaneFrame` and the same sections (`RunEmptySection` ...
  `RunOutputSection`), so the pane's own tests host it as before.
- The frame owns the state the sections share: the picked command, the editor's draft, the delete confirm, the save in
  flight and the last refusal. Sections read it through `RunPaneHost` (a composition local) and change it only through its
  callbacks (`pick`, `start`, `stop`, `openRun`, `openNew`, `editPicked`, `saveDraft`, `cancelEdit`, `askDelete`,
  `deletePicked`, ...). The dropdown and the Interactive tick are the toolbar's own, the pills' clock the pills' own. A
  section drawn outside the pane draws nothing.
- Each section owns the gap above it, as the pane drew it. `editor` and `output` ask for `weight = 1` in their `place` (an
  override keeps it); they never draw at once.
- A section's placement goes on its outermost item (the first notice for `notices`). `notices` can draw several items into
  the column, so a module that wraps it draws `inner` in a Column (a Box would stack them).
- None of these nodes is locked. Run enablement (the demo keeps run and terminal off) is unchanged.
- 0 px: the default Run shot has no saved command. `-PtabShotsExtra=run` gives the in-process daemon a temp repository,
  its own commands.json and owner credential, saves one command and renders `run-toolbar`, `run-delete`, `run-editor` and
  `run-output` (a finished run's pill and line). The step matched its base render 4/4 at 0 px (step 0 of L15), plus
  diffTabShots 32/32.

### 6m. As built in L16 (983f1936): the board pane and the ticket card template

The board's pane (6g) stops being a leaf (docs/BLOCKS-NEXT-SPLITS.md, top-8 item 3). `infantry.shell.board.pane` keeps
its id, its block type and its `weight = 1`, and becomes a container. The ticket card becomes the first **template node**
(BLOCKS-NEXT-SPLITS section 4). The tree is in `CoreShell.root`, under `infantry.shell.board`, not a tab tree.

```
infantry.shell.board.pane  boardPane   BoardModalPaneBox (the padded box, which wears the placement) and BoardPaneFrame (the
                                       image viewer host, the drag box and its ghost, the epics load, the Epic filter, the
                                       new-ticket flag, the drop session); children top to bottom, straight into its column:
  pane.filters     boardFilters     Epic, the ticket count, Start next, + New ticket
  pane.unassigned  boardUnassigned  "N unassigned tickets (no project) not shown", with a project selected
  pane.newTicket   boardNewTicket   the new-ticket form, while it is open
  pane.empty       boardEmpty       "The backlog is empty." (or "Connect to a daemon..."), with no ticket and no form
  pane.columns     boardColumns     weight 1: the status columns (title, count, drop border, drop line); its children are
                                    drawn once per ticket into the column's card list:
    infantry.shell.board.card  ticketCard  TEMPLATE: TicketCardFrame (the raised rounded column, the drag look, the
                                           edit form's open flag); children top to bottom, straight into its column:
      card.summary          ticketCardSummary  the drag handle and the clickable summary (it opens / closes the form);
                                               its children go straight into the summary's column:
        card.summary.title    ticketCardTitle    the title
        card.summary.badges   ticketCardBadges   type, assignee (avatar, chip), epic, won't fix, queued, report flag
        card.summary.details  ticketCardDetails  hold reason, runs on, branch, progress, last run
      card.note             ticketCardNote     why a drop snapped back, and the role pick; nothing otherwise
      card.edit             ticketCardEdit     the edit form and the links, while open
      card.actions          ticketCardActions  the column's moves, Agent, Delete while open
```

- `BoardPane(state, controller, onOpenAgent)` and `BoardModalPane(...)` keep their signatures and draw `BoardPaneFrame`, the
  same sections (`BoardFiltersSection` ... `BoardColumnsSection`) and per ticket `TicketCardFrame` and its sections
  (`TicketCardSummarySection` ... `TicketCardActionsSection`), so the board's own tests host it as before.
- The pane frame owns what the sections share and hands it to them as `BoardPaneHost` (a composition local): the filter in
  force, the new-ticket flag, the drag and the drop session. Sections change it only through its callbacks
  (`pickFilter`, `toggleCreating`, `closeCreating`, `startNext`, `drop`).
- The template: the columns hand each ticket to their children as a `BoardCardItem` (the ticket, its drag look, its handle,
  its drop note, its start) through a second local; the card frame owns the edit form's open flag and hands its sections
  a `BoardCardHost` through a third (`toggleOpen`, `close`, `move`, `start`, `delete`, `openAgent`, `dismissNote`). The
  card node is resolved once like any node; an override of `infantry.shell.board.card` or of one of its sections applies
  to every card. Overrides address the template id, never a ticket; the columns own the list, its order and its length,
  and an override cannot change them. The template's id is `infantry.shell.board.card`, not under `pane.columns`, as the
  toast card's would be `infantry.shell.toast.card`.
- The card's placement goes first, then the drag look (where the card is measured for a drop, the drop line, the faded
  copy while dragged). A module that replaces the whole card draws its own and loses the handle and the drag with it.
- A section's placement goes on its outermost item (the row, the text, the column). `newTicket`, `details`, `note` and
  `edit` draw several items or an untagged form, so any placement other than the default gets a Column to wear it (as
  6f), and a module that wraps one draws `inner` in a Column (a Box would stack them).
- A section drawn outside its pane, a card drawn outside the columns, or a card section drawn outside a card draws nothing.
- None of these nodes is locked.
- 0 px: the default run never opens the board, and `board-open` (6g) has an empty backlog. Step 0 of L16 adds to
  `-PtabShotsExtra=board`, after `board-open`: `board-new` (the form), `board-cards` (three tickets in Backlog, To do and
  Done, one with a role assignee) and `board-card-open` (a card on its edit form). The step matched the step-0 render 4/4
  at 0 px, plus diffTabShots 32/32.
### 6n. As built in L17 (b0eaa699): the Notes tab

The Notes tab (Infantry_Notes, `MemoryPane` 152 lines; docs/BLOCKS-NEXT-SPLITS.md, top-8 item 6) is the third core tab
tree, built as 6j and 6l: `CoreShell.tabs` is now `ShellGit, ShellRun, ShellNotes`, and the tab (`infantry.notes`) draws
`tabTree("infantry.shell.notes")`.

```
infantry.shell.notes   notesPane   NotesPaneFrame (the box the modal dims, the scrolled padded column, the memory load, the
                                   body fill-in of an opened note); children top to bottom, straight into the column:
  header     notesHeader    "NOTES · project" and Add note
  empty      notesEmpty     "Select a project to see its notes.", with no project
  scopes     notesScopes    the Project / Role / Session tabs, with a project
  banner     notesBanner    whose notes these are (a session's in red: temporary), with a project
  noOwner    notesNoOwner   "Select a session to see ...", for a role / session scope with no session selected
  search     notesSearch    the search box and Search; nothing with no project or no owner (as below)
  hits       notesHits      "No hits for ..." or one hit row per hit, for the last search of this scope
    hit      notesHit       the hit TEMPLATE: name, description, snippet
  error      notesError     the last refusal (a refused search), while the modal is closed
  list       notesList      the divider, then "No notes yet." or one note row per note
    note     notesNote      the note TEMPLATE: name, description; a click opens it in the modal
  modal      notesModal     the add / edit modal (NoteModal on EditorModal), while it is open: drawn in the box, over the column
```

- `MemoryPane(state, controller)` keeps its signature and draws `NotesPaneFrame` and the same sections
  (`NotesHeaderSection` ... `NotesModalSection`, with `NotesHitRow` and `NotesNoteRow` as the rows), so the pane's own
  tests (MemoryPaneUiTest, NotesScopesUiTest) host it as before.
- The frame owns the state the sections share: the search query and the modal's draft (both reset with the project), the
  scope tab and the last refusal. Sections read it through `NotesPaneHost` (a composition local) and change it only
  through its callbacks (`pickScope`, `changeQuery`, `search`, `openNew`, `openNote`, `saveDraft`, `deleteEditing`,
  `cancelDraft`). A section drawn outside the pane, and a row drawn outside its list, draws nothing.
- The pane's early returns are now each section's own condition: no project draws the header and `empty` only; a scope
  with no owner draws header, scopes, banner and `noOwner` only. The modal keeps its old condition (a project and an open
  draft).
- `modal` is the one child that does not go into the column: `NotesPaneBlock` draws the children whose id is
  `infantry.shell.notes.modal` (an override of it keeps the id) in the pane's box after the column, every other child
  (an injected one too) into the column. Its placement goes on the modal's panel.
- Templates (as the L16 card): `hits` and `list` draw their children once per hit / note, with that item in a composition
  local, so one override of `infantry.shell.notes.hit` or `.note` applies to every row. Rows go straight into the pane's
  column; each row owns its gap (a hit the 6 dp above it, a note the 6 dp under it).
- `hits` and `list` draw several items: a placement other than the default gets a Column to wear it (as L16's
  `Placed`), so a module that wraps one draws `inner` in a Column (a Box would stack them). "No hits" wears the
  placement itself.
- None of these nodes is locked. HandCursorGuardTest baseline unchanged (the raw calls moved, none added or removed).
- 0 px: the default Notes shot has no project. `-PtabShotsExtra=memory` creates a project with two notes and renders
  `memory-list`, `memory-search` (the search goes through the controller, so no caret blinks in the shot), `memory-edit`,
  `memory-add`, `memory-role` and `memory-session` (no session selected: the no-owner line). The step matched its base
  render (step 0 of L17) 6/6 at 0 px, plus diffTabShots 32/32.

### 6o. As built in L18 (2f33c612): the Changes tab

The Changes tab (Infantry_Changes, `ProjectChangesPanel` 115 lines; docs/BLOCKS-NEXT-SPLITS.md, top-8 item 8) is the
fourth core tab tree, built as 6j, 6l and 6n: `CoreShell.tabs` is now `ShellGit, ShellRun, ShellNotes, ShellChanges`, and
the tab (`infantry.changes`, right container) draws `ChangesTab(diffTarget, onOpenDiff)`, which draws
`tabTree("infantry.shell.changes")`. The panel was not in the Git tab's tree (`infantry.shell.git` is `ProjectGitPane`), so
the container is a new id.

```
infantry.shell.changes   changesPane   ChangesPaneFrame (the scrolled padded column, 6 dp between items, the Working tree |
                                       History mode, the polling); children top to bottom, straight into the column:
  empty        changesEmpty        "Select a project to see its changes.", with no project
  title        changesTitle        "CHANGES · project" and the refresh icon, with a project
  unsupported  changesUnsupported  the line for a daemon older than 1.36.0
  notGit       changesNotGit       "Not a git repository: ...", when a 1.88.0 daemon says so
  worktree     changesWorktree     the worktree picker and its menu, and "Worktrees unknown: ..." (1.88.0)
  header       changesHeader       the shown worktree's branch, ahead/behind, base, uncommitted (or why it is unknown)
  mode         changesMode         the Working tree | History toggle
  branch       changesBranch       the branch line and its menu: an older daemon, or the Working tree mode
  hint         changesHint         "N commits ahead; see Compare/History" over a clean worktree, on the working tree
  files        changesFiles        the working tree's files: unknown, clean, or the summary and the Staged / Not staged groups
    file       changesFile         the file TEMPLATE: badge, path, status; a click opens its diff
  branchFiles  changesBranchFiles  what the picked branch changed vs its base: unknown, or the header, the rows, "More files"
    branchFile changesBranchFile   the branch file TEMPLATE: badge, path, +/-, status; a click opens its diff
  history      changesHistory      the History mode's commits: unknown, "No commits yet." or the rows and "Older commits"
    commit     changesCommit       the commit TEMPLATE: short hash, subject, author and time
```

- `ProjectChangesPanel(state, controller, pollMs, openDiff, onOpenDiff)` keeps its signature and draws `ChangesPaneFrame`
  and the same sections (`ChangesEmptySection` ... `ChangesHistorySection`, with `ChangesFileRow`, `ChangesBranchFileRow`
  and `ChangesCommitRow` as the rows), so the panel's own tests (ProjectChangesPanelUiTest, IconButtonsUiTest, the real
  daemon tests) host it as before.
- The frame owns the mode and the five polling effects; the worktree and branch picks stay in `AppState` as before.
  Sections read them through `ChangesPaneHost` (a composition local) and change them only through its callbacks
  (`refresh`, `pickMode`, `selectWorktree`, `selectBranch`, `open`). The two menus' open flags are their sections' own. A
  section drawn outside the pane, and a row drawn outside its list, draws nothing.
- The diff modal's file and the click that opens one come from the tab (`TabContext.diffTarget`, `onOpenDiff`), not from
  `LocalShell`: `ChangesTab` hands them to the pane block through a private composition local.
- The panel's early returns are now each section's own condition (`showsOlder`, `notGit`, `showsWorktrees`, `showsList`
  on the host): no project draws `empty` only; an old daemon draws title, `branch` and one list; a non-git folder draws
  title and `notGit` only.
- The column keeps `spacedBy(6 dp)`, so items own no gap of their own here: every item a section draws goes straight into
  the column and gets the column's gap, as it did. A section that draws nothing adds no gap.
- Templates (as 6m, 6n): `files`, `branchFiles` and `history` draw their children once per file / commit, with the item
  in a composition local (`ChangesFileItem` = the file and whether it is listed as staged), so one override of
  `infantry.shell.changes.file`, `.branchFile` or `.commit` applies to every row. The working tree's rows still go
  through `ChangeGroup` (shared with the Git tab), which now takes an optional `row`; the Git tab passes none and draws
  `ChangedFileRow`, the same row the file template draws.
- `worktree`, `files`, `branchFiles` and `history` can draw several items: a placement other than the default gets a
  Column (6 dp apart, as the tab) to wear it, so a module that wraps one draws `inner` in a Column (a Box would stack
  them). Their single-line states (unknown, clean, empty) wear the placement themselves.
- None of these nodes is locked. HandCursorGuardTest baseline unchanged (the raw calls moved, none added or removed).
- 0 px: the default Changes shot has no project. `-PtabShotsExtra=changes` gives a project a real repository (fixed path
  and commit dates, a staged and an unstaged file, a branch with one commit) and renders `changes-top`,
  `changes-worktrees` (the worktree menu), `changes-branches` (the branch menu), `changes-branch` (the branch's changes),
  `changes-history` and `changes-not-git`. The chat's message clocks are on screen in these shots, so the before/after
  diff masks them (the default mask only masks them for the shots named after the chat tab). The step matched its base
  render (step 0 of L18) 6/6 at 0 px, plus diffTabShots 32/32.

### 6p. As built in SHELL-EMPTY-DROPIN S1 (9c69dee6): placeholder containers

The drop-in path (docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md, gap 1) needs a dropped plugin to fill an empty shell, and the
locked containers refused every override and injection. Four of them are now **placeholders**, open slots:

```
infantry.shell.frame        placeholder   default: column(surface)   header, body, footer
infantry.shell.body         placeholder   default: stack(measure)    panes, drawer
infantry.shell.panes        placeholder   default: row               left, center, right
infantry.shell.windowLayer  placeholder   default: group             the dialogs (config ... connect)
```

- `PlaceholderBlock(id, default, place)` (block/layout/Placeholder.kt, built with `Block.asPlaceholder()`) keeps the
  node's id and place, so its parent places it as before, and draws its core-provided **default** (the block the node
  was) with the same scope: with no override the window is pixel-identical. With no default it draws nothing (the
  empty shell's slot, for later steps).
- Its children are the default's, so every node below keeps its id and address (the tree walk in CoreShellTreeTest is
  unchanged) and an injection into a placeholder goes in among them, drawn by the default (`withChildren` rebuilds the
  default; an empty placeholder takes a group). A placeholder of a group flattens like a group.
- A replace draws the module's block in the placeholder's id and place (`OverrideBlock`, as any node). A replace of the
  frame, whose default wears the host surface (fill, modal blur, background, the window's focus target, the key
  handler), gets that surface in its scope's modifier (`wearsHostSurface`), so the window still starts and keys still
  work; the block must apply the modifier exactly once, as every block does. A wrap leaves the surface to `inner`.
- `windowLayer` takes injections, but its replace is still refused: it would remove the locked dialogs in it. That is
  the rule as it was, and it is what keeps the trust kernel reachable.
- Still locked: the true skeleton `window`, `images`, `dock`, and `connect`, `servers`, `switchError`, `serverUpdate`,
  `config`. `CoreShell.placeholders` lists the four; both lists are pinned in CoreShellTreeTest (plugin API, 3d of the
  feasibility doc).
- Trust is unchanged: a community module needs `allowOverride` (replace, wrap) or `allowInject` for a placeholder like
  any core node (ModuleResolverTest, BlockInjectorTest).
- Deviation from the feasibility doc's S1 row: the doc has the open children become empty placeholders that a
  compiled-in `Infantry_AppUi` core module fills back through `shell(replace)`. S1 instead keeps the current UI as the
  placeholders' in-tree defaults: replacing the open children would take every node below them (header.lead...,
  footer chips, the board card...) out of the tree, breaking their published ids, and the window would no longer be
  `CoreShell.root` itself with no module. Moving the defaults out into a module is S4's job (the module split).
- 0 px: diffTabShots 32/32; the S0 set (`-PtabShotsExtra=config,changes,compact` against tab-shots-baseline-s0) 22/22;
  `roles` 4/4 and `board` 4/4 against a render of the base ref. HandCursorGuardTest baseline unchanged (no raw calls
  added or removed).

### 6q. As built in SHELL-EMPTY-DROPIN S2 (4c8f56c3): the ShellContext

Gap 2 of the feasibility doc: the open nodes read `App`, `Shell` and `Controller` directly, so a block compiled outside
core has nothing stable to compile against. `ui.shell.ShellContext` (agenthqUiCore, commonMain) is that surface; the
shell provides it once, through `LocalShellContext`, next to `LocalShell`.

```
ShellContext { link, session, git, run, board, roles, toast, config }
  LinkContext     live, contract, contractAtLeast(major, minor)
  SessionContext  activeProject, notices, projectRefusal
  GitContext      status/patch/history/branches/checkoutResult/pipeline(projectId), failure;
                  load, loadDiff, loadBranches, checkout, setBaseBranch, saveDirectory, loadPipeline, configurePipeline
  RunContext      runs, secretNames, hasConsoleWindow, interactiveSupported; start, stop, open, saveCommand, deleteCommand
  BoardContext    open; close, openAgent(sessionId)
  RolesContext    managerOpen, roles, bindings, traits; closeManager
  ToastContext    service (ToastService), events; open(event)
  ConfigContext   values(nodeId): ConfigValues {keys, string, int, double, boolean}; implementation(type, key)
```

- **Derived, not designed.** Every member is something a migrated section called (grep of `state.`/`controller.` in
  ProjectGitPane, ProjectPipelineSection, RunPane, RoleManager list, BoardModal header, the toast block and the five
  shell blocks). Nothing is in it "because a plugin might want it"; memory, settings and a fuller roles/board domain
  come when the code that needs them migrates (S4), as additions.
- **Small sub-interfaces per domain**, so a block depends on the ones it reads (`GitPaneFrame(link, session, git)`,
  `RunPaneFrame(link, run)`), and a fake for one domain is a page, not all of Controller (ShellContextTest).
- **Values are composition snapshots, not flows.** `App` already collects `Controller.state` once; `CoreShellContext`
  wraps that snapshot and is provided anew when it changes, exactly as `LocalShell` is, so recomposition is unchanged.
  Trade-off: non-composable code (a `LaunchedEffect` that must see the newest state, like the board drag's
  `controller.state.collect`) has no flow yet; one would be an addition (`val changes: Flow<...>` with a default).
- **Actions answer `CommandResult`** and delegate one-to-one to the Controller call the section made before (same
  overload, same defaults: `startRun(id)` vs `startRun(id, interactive = true)`, `configurePipeline(.., exclusiveCheck =
  false)`). Window effects (board open/close, the toast tap, roles close) are the core's to perform, so a block asks for
  them by intent and never writes `ShellState`.
- **Config slot (owner, 2026-10-27).** Read-only: `values(nodeId)` resolves child, parent, root, plugin default and
  returns typed values (null for unset or another type, never coerced); `implementation(type, key)` returns what is
  registered under a registry key (the JSON stores keys, never class names). The core answers `EmptyConfig` until P1
  (f60f4d97) builds schema, tree and storage behind these signatures.
- **Migrated as the proof:** the Git tab (frame, host, all seven sections, branch switcher, base picker, pipeline), the
  Run tab (frame, host, all sections), the toast block, the board block and its header, the roles block and its list.
  Their `(state, controller)` entry points (`ProjectGitPane`, `RunPane`, `BoardModalHeader`, `RoleManagerList`,
  `ToastHost`) stay and adapt through `CoreLink`/`CoreSession`/`CoreGit`/`CoreRun`, so unmigrated callers and tests are
  untouched. **Not migrated** (S4): `BoardPaneFrame` and the ticket forms, `RoleManagerForm`/`RoleFormFrame`, which still
  read `LocalShell`; and `GitTab`/`RunTab` still resolve their tree through `LocalShell.tabs` (tab wiring, not state).
- Outside a shell (no `LocalShellContext`) every migrated block draws nothing.
- **Stability rule: additions only.** The interfaces, their member names and types, and the model types they return
  (`ProjectView`, `Notice`, `RunsState`, `ToastEvent`, the `ProjectGit*` and `Role*` wire types) are plugin API under
  `apiVersion`. A member is never renamed, retyped or removed; a new member on an interface a plugin might implement
  (a fake, a wrapper) gets a default. Too wide is a leak forever; S4's compile errors decide each widening.

### 6r. As built in SHELL-EMPTY-DROPIN S3 (b06cc897): Configuration split into a trust kernel and open settings

Gap 3 of the feasibility doc: `ConfigDialog` (239 lines) was one locked leaf, so the "empty" shell still held every
setting. `infantry.shell.config` is now a container, built as 6j-6o; it and its trust sections stay locked, the settings
are open.

```
infantry.shell.config   config (LOCKED)   the frame: AhqAlertDialog, title, lifetime note, search box and results, tab strip,
                                          Done; children straight into the selected tab's scrolled column, top to bottom:
  settings        group (open)      app UI; transparent, so its children are drawn as the container's own
    theme           configSection   Appearance: accent and dark mode
    blur            configSection   Appearance: blur behind dialogs
    markdownEngine  configSection   Appearance: chat markdown engine
    reasoning       configSection   Appearance: where reasoning shows
    userPortrait    configSection   Appearance: the user's portrait
    agentScope      configSection   General
    progressStale   configSection   General
    lifecycle       configSection   General: session keep-alive (daemon state)
    cliLocations    configSection   Agents: CLI paths and harness install
    toolApproval    configSection   Agents
    roles           configSection   Agents: opens the role manager
    limits          configSection   Limits
    voice           configSection   Notifications
    incomingSound   configSection   Notifications
    questions       configSection   Notifications: send a test question
  plugins         configSection (LOCKED)   Plugins: voice plugins
  modules         configSection (LOCKED)   Plugins: modules, where a plugin is disabled
  secrets         configSection (LOCKED)   Secrets: the daemon keystore
  pair            configSection (LOCKED)   Devices: pair a device (a server address and its token)
  about           configSection (LOCKED)   About: app and daemon build
  updates         configSection (LOCKED)   About: check for app updates
  serverUpdate    configSection (LOCKED)   About: the daemon's version and updater
  logs            configSection (LOCKED)   About: the log folder
```

- **What is locked, and why.** `config` itself (frame, tab strip, search) and the eight trust sections are in
  `CoreShell.locked` (`ShellConfigTrust`): they are where a plugin is disabled (plugins, modules), a secret or a token is
  typed (secrets, pair), an update is checked or approved (updates, serverUpdate) and a problem is reported from (about,
  logs). No module replaces or wraps one, and a replace of `windowLayer` is still refused, so a bad drop can always be
  undone from Configuration > Plugins (feasibility doc 3d). `serverUpdate` and `modules` are not named in the doc's list;
  they are locked for the same reasons as `updates` and Plugins. The fifteen settings and their group are open.
- **Adding a section.** The container is locked, so it takes no injection; `settings` is open, so a module adds a section
  by injecting into `infantry.shell.config.settings` (a community module needs `allowInject` for it). An injected child
  has no `ConfigEntry`, so it shows on every tab and the search does not find it; a registry for plugin sections (tab,
  title, keywords) is a later addition, not part of S3. Replacing `settings` drops every setting and keeps the trust half.
- `ConfigDialog(...)` keeps its signature plus one trailing parameter, `sections` (default `ConfigSections`, every section
  in order), so its own tests (ConfigTabsUiTest, ConfigDialogLayoutUiTest, SecretsKeyValueUiTest, ...) host it as before.
  `ConfigBlock` passes its children instead.
- The dialog owns the tab, the query and the highlight; sections read them, with the dialog's inputs, through
  `ConfigDialogHost` (a composition local) and change settings only through its callbacks (`onFlags`, `onTheme`,
  `onLimit`, ...). A section drawn outside the dialog draws nothing.
- Each section's old `if (shows(entry) && input != null)` is its own condition (`ConfigThemeSection` ...
  `ConfigLogsSection`); it draws nothing off its tab, so it adds no 14 dp gap, as before. The container also draws a child
  whose id is a section's only on that section's tab, so a module's replace or wrap of `theme` shows on Appearance only.
- Each section draws one `FlagSection` column that wears its placement (`scope.modifier` first, then the section's tag,
  the bring-into-view requester and the highlight outline), so a wrap needs no Column of its own beyond the module's.
- HandCursorGuardTest baseline unchanged (the raw calls moved, none added or removed).
- 0 px: S0's opt-in set (`-PtabShotsExtra=config,compact`: the nine Configuration tabs and the search, and the compact
  board, new-agent and config shots) rendered on the base before the split matched after it, plus diffTabShots 32/32.

### 6s. P1 (f60f4d97): plugin configuration, step 1 (read-only registry view) and the config layer design

Owner request 2026-10-27: open a container node and see the plugins registered in it, tabs first; then (step 2, after a
PM check) change what is registered there; and the Payload-CMS-style configuration layer (A-G below), design only.

**For the owner, in ten lines.**

```
1. Every node may declare config fields (a schema): text, number, checkbox, select, multiSelect, implementation; plugins add types.
2. Defaults come from JSON: core's file plus each installed plugin's, merged into ONE tree keyed by node id and hierarchy.
3. That JSON seeds the database on first load; afterwards the database is the live copy, and the view edits it.
4. A value resolves child -> parent -> root -> the declaring plugin's default; the first one set wins; unset is never an error.
5. The block gets the resolved values as parameters (ShellContext.config.values(nodeId)); it never knows where they came from.
6. An implementation field stores a registry KEY ("sorter": "byDueDate"), never a class name; no code is loaded from config.
7. The keys come from a registry installed plugins add to; a user may swap one only where the node marks the field exposed.
8. A newly installed helper becomes the selection only if the node allows it and the user never picked one.
9. Locked core nodes and trust fields never show as editable; a community plugin edits only what allowOverride/allowInject grant.
10. The configuration view renders that same tree: pick a node, see its registered children and its fields; field groups are tabs.
```

**What counts as a container node that hosts children.** A node whose children are *registered* by modules (through
module.json and the loader), or resolved over the core tree by them, so that "who put this here" has an answer. Two
kinds exist today, and the read model (`NodeRegistry`, below) answers both the same way:

- **Tab strips** (kind `tabs`): the children are the `TabProvider`s modules declare under `tabs` in module.json, resolved
  by `TabFactory` (a preference replaces a tab in its place). Hosted by `infantry.shell.center.chat.tabs` (container
  `center`) and `infantry.shell.right.body.strip` (container `right`). A tab that draws a core tab tree
  (`CoreShell.tabs`) says which (`CoreShell.tabTreeTabs`). Wired in step 1.
- **Shell tree containers** (kind `tree`): every node of `CoreShell.root` or of a core tab tree that has children, as
  `BlockResolver` resolved it: a core child as shipped, an `InjectedBlock` (module, injection) or an `OverrideBlock`
  (module, replace or wrap; a wrapped container still lists the children inside it). Listed in step 1, read-only.
- **Not in step 1:** the side panels' `PanelProvider` sections (`infantry.shell.left`, `infantry.shell.right.body`) have
  the same shape as tabs and are the next to wire; the toast area (`infantry.shell.toast`) holds runtime events, not
  registered plugins; the slots (`center.top` ...) and interceptors decorate a tab, they are not children of a node.

Node ids found (the view lists them in this order: the two strips, then every tree container in tree order; (L) =
locked, listed but never editable; `NodeRegistryTest` pins the list):

```
tabs   infantry.shell.center.chat.tabs        infantry.shell.right.body.strip
tree   infantry.shell.window (L)  .images (L)  .dock (L)  .frame  .header  .header.lead  .header.lead.overflow  .body
       .panes  .center  .center.chat  .center.newAgent  .right  .right.body  .footer  .assistant  .assistant.body  .board
       .board.pane  .board.pane.columns  .board.card  .board.card.summary  .diff  .windowLayer  .config (L)
       .config.settings  .roles  .roles.form
tree   infantry.shell.git  .run  .notes  .notes.hits  .notes.list  .changes  .changes.files  .changes.branchFiles
       .changes.history (the core tab trees)
```

**Step 1 as built (read model + view).**

```
NodeRegistry.of(factory): List<HostNode>      core, commonMain (ui.plugin); built from TabFactory, nothing duplicated
  HostNode        id, title, kind (TABS | TREE), locked, children
  RegisteredChild id, name, module, version, tier (core/community), order, source, by, locked, enabled
  ChildSource     TAB | CORE_TAB_TREE (a tab drawing CoreShell.tabs) | REPLACE | WRAP | INJECTION | SHELL (core, as shipped)
infantry.shell.config.registry   configSection (LOCKED)   Plugins: where plugins are registered
```

- **Reused, not duplicated.** The tabs come from `TabFactory.tabs(container)`; a preference that replaced a tab is what
  `TabFactory` recorded when it applied it (`replacedTabs`); replace, wrap and injection are read off the resolved trees
  (`OverrideBlock.module/mode`, `InjectedBlock.module`), so the view shows exactly what is drawn.
- **Enabled.** A tab drawn now is enabled. A tab a module declares in its module.json while the module is not loaded (off
  in modules.json, refused, locked) is listed as disabled, with no order (its order is in code that did not run). Step 2
  adds a per-node disable on top.
- **The view** is one trust section, `infantry.shell.config.registry`, after Modules on the Plugins tab: a node picker
  (chips: the strips, then the tree containers), then the picked node's children, one row each: name, id, module and
  version, Core/Community, source, order, Locked, Enabled/Disabled. Read-only; a locked node says so above its list. It
  reads the dialog's host (`ConfigDialogHost`) through the section's composition local, as every config section does;
  drawn outside the dialog it draws nothing. It shows where Modules shows (a host that loads modules at run time: the
  desktop); web and Android have no Plugins > Modules and so no registry view yet.
- **Why locked (the one new locked node).** Step 2 makes this where a child plugin is disabled, so it belongs to the
  trust kernel (6r): a module must not replace the view that turns it off. No other node is locked.
- **Pixels.** The section sits below Modules, under the fold of every existing Plugins shot, so those stay 0 px. New
  opt-in shot `-PtabShotsExtra=registry`: `config-registry` (the Plugins tab scrolled to the view, the centre strip
  picked).

**Step 2 (NOT built; after a PM check of step 1).** Per-node settings from this view: enable or disable a registered
child, reorder a strip's tabs. Stored as per-node settings (B below; modules.json `ModuleSetting` unchanged) and read
when the factory is built: a disabled child is not drawn, a reordered strip uses the stored order (ties by id). Never
editable: locked nodes and the trust sections; a community module's child only where its `allowOverride` (tabs,
replace, wrap) or `allowInject` (injections) grants the node. Restart rule as Modules today, until the factory can
rebuild live.

**A. Schema per node.** A module declares fields per node id it owns or is granted, in module.json:

```json
"config": [
  { "node": "infantry.shell.board.pane", "group": "Board",
    "fields": [
      { "key": "showEmpty", "type": "checkbox", "label": "Show empty columns", "default": true },
      { "key": "columns", "type": "multiSelect", "options": ["todo", "in_progress", "done"], "default": ["todo", "in_progress", "done"] },
      { "key": "sorter", "type": "implementation", "registry": "board.sorter", "default": "byOrder", "exposed": true }
    ] }
]
```

Built-in types: `text`, `number` (min, max, step), `checkbox`, `select`, `multiSelect`, `implementation`. A type is a
key in a field-type registry (validate a JSON value, draw an editor); a module registers more (`color`, `duration`). An
unknown type is shown read-only with one line in Problems, never dropped.

**B. One unified tree.** At start the loader merges core's `config.json` and every loaded module's `config` into one tree
keyed by node id; the hierarchy is the block tree's (a node's parent is its parent in `CoreShell.root` or its tab tree),
so `infantry.shell.board.pane` sits under `infantry.shell.board`. Merge: core first, then modules in load order; a module
declares fields only on its own nodes or nodes its modules.json grants name; a second declaration of a key is refused
with a line. Storage: the daemon's database, `node_config(node_id, key, value_json, set_by, updated_at)`; the first load
inserts only keys not there yet (the JSON seeds, never overwrites); after that the database is the live copy. The view
reads and writes it through the daemon (one route), so desktop, web and Android see the same values.

**C. Resolution.** `values(nodeId).string(key)` (ShellContext's `ConfigContext`, 6q) looks at the node, then its parent,
up to the root, then the declaring module's default; the first value set wins. Typed: a value of another type is null,
never coerced. The core passes the resolved values to the block as parameters (`LocalShellContext.config`); the block
never asks who set them. Resolution runs once per change of the tree, not per frame.

**D. Implementation fields.** The stored value is a registry key; `implementation(type, key)` returns what a module
registered under that key (a future `UiRegistrar.implementation(registry, key, value)`), or null. Firm rules: no class
names or paths in JSON, ever; the options are the keys registered now (a stored key whose module is gone falls back to the
default, with a line); a user may swap one only if the field is declared `exposed`; the fields of locked nodes and trust
sections are never offered; a newly installed module's key becomes the selection only if the field is `exposed`, allows
it (`autoSelectNew`), and `set_by` is not `user`.

**E. Children control.** For a container (tabs, tree) the view offers add, remove and reorder. Add offers only children a
loaded module declares as belonging to that node (its module.json `tabs[].container`, `inject[].into`, or a later
`children[].into`), so a container never takes a stranger. Remove is disable (the module stays loaded). A locked
container offers nothing; community children obey allowOverride and allowInject.

**F. Config groups as tabs.** Each field set names a `group`; the configuration dialog's tab strip is the list of groups
in order (today's nine `ConfigTab`s are core's groups). A module's group with a core group's name joins that tab; a new
name adds a tab after core's, before About. Inside the registry view, a node's own groups render as a sub-strip above its
fields.

**G. Other platforms.** Same JSON format and resolution on web (wasm) and Android; the registry is filled at compile time
from the compiled-in modules (`CoreModules.all`). Only Windows has the drop-in (run-time loading).

**P1 step 2 (44aee6e5), as built.** Decisions taken while building (the owner approved 6s as written; none of these
changes a line of the ten-line summary):

- **1. Schema, tree, storage, resolver.** The schema types are plugin API in `agenthqPlugins` (`ConfigDeclaration`,
  `ConfigFieldDeclaration`, `ConfigFieldTypes`, the wire row `NodeConfigEntry`), shared by the daemon and the window;
  module.json gains `config` (A). `TabFactory.configTree` merges core's `CoreConfig.JSON` (text in commonMain, so web and
  Android read the same file, G) and each loaded module's `config` in load order (B); the hierarchy is the drawn trees'
  plus two links the block tree has no node for: a tab sits under its strip node (or its parent tab), a tab tree's root
  under the tab that draws it. A core module declares on any node; a community one on its own tabs, ids under its name,
  and the nodes modules.json `allowOverride`/`allowInject` grants it. A second key, a blank key and a stranger node are
  refused with a line; an unknown type is kept, with a line, for a read-only editor. Storage is the daemon's
  `node_config` (migration 50) behind three HTTP routes (`GET /node-config`, `POST /node-config/seed` inserting only
  missing keys, `PUT /node-config` with a null value removing the row), reached through `PluginDaemon`'s bearer; not a
  WebSocket frame, so no contract bump and no protocol type in the window. `NodeConfigState` (one per factory) is the
  one owner of the stored copy: the window seeds and loads it once the link is live; until then, or when the daemon is
  older (404) or down, every key resolves to its declared default and the state says why. `ResolvedConfig` is what
  `ShellContext.config` answers now (C): the node, its parent up to the root, then the nearest declaration's default;
  typed, never coerced; `ConfigValues.strings` (a multiSelect) is an addition with a default. Keys under `children.`
  belong to their node and are never inherited (E).

- **2. Enable/disable and reorder (E).** Stored on the container node itself as two own-only keys: `children.disabled`
  (child ids not drawn) and `children.order` (a strip's child ids as the user put them; one not named keeps its
  declared place after them). Applied live, not at the next start: `TabFactory.tabs`/`children` read them (so every
  strip, including the ones `plugins/core` draws, follows), and `shellRoot`/`tabTree` drop the disabled injections
  (memoised per stored copy, the very same tree object when nothing is off); `resolvedShellRoot`, `declaredTabs` and
  `declaredChildren` keep everything for the registry view. Editable: a strip's tabs (on/off and Up/Down) and a tree
  container's injected children (on/off). Never: a locked node or child, a tree container's core children as shipped
  (hiding one is a replace, a module's job), a strip's home tab (its first declared tab; `home()` now reads the
  declared order, so the window always opens on it), a tab whose module did not load (Modules decides), and a
  community module's child unless modules.json `allowOverride`/`allowInject` grants it the node. The view shows why a
  child is not editable; while the stored copy is not loaded the controls are off and a line says why, and a refused
  write says "Not saved: <the daemon's answer>". A tab switched off while open hands its strip back to the home tab.
  Pixels: the controls change only the opt-in `config-registry` shot (below the fold of every default shot).

- **3. Implementation fields (D).** A module registers with `UiRegistrar.implementation(registry, key, value)` and
  declares each under module.json `implementations` (`{registry, key}`); an undeclared one or a key a registry already
  has is refused with a line (`TabFactory.implementations`, core's first, then load order). The stored value is the key
  only. A stored key nobody registers now resolves to the field's default and the view shows "<key> is not registered".
  `autoSelectNew`: the keys seen at the previous load are kept beside the field as `<field>@known` (own-only, never
  shown); a key new since then becomes the selection only if the field is `exposed`, allows it and `set_by` is not
  `user`; the first load only records. **The real example** is each tab strip's `sorter` (core's config.json, group
  "Tabs", registry `tabs.sorter`, keys `byOrder`, the default, and `byTitle`; exposed; autoSelectNew off, so installing
  a sorter never reorders a user's strip by itself). `TabFactory.tabs` sorts through it before the stored Up/Down
  order; a sub-tab host inherits its strip's sorter by resolution. Why the strips: every list container with items is in
  `plugins/core`, which this ticket does not touch; the strips are core and drawn through `TabFactory`. The registry
  view gains a node's **Fields**: one editor per built-in type (checkbox, text and number with Save and a min/max check,
  select and multiSelect chips, implementation chips over the registered keys only when exposed), where the value
  comes from (Default, Default (stored), Set here, From <parent>), and Reset. A locked node's fields are read-only; a
  node with fields but no children is pickable under "Configured nodes".
- **4. Config groups as tabs (F).** The groups are tabs **inside the picked node's Fields** in Plugins > Registered
  (`NodeFieldGroups`): one tab per group the node's fields name, in the merged tree's group order (core's first, then
  each new name in load order), and the picked tab shows that group's fields. A node with one group still shows its one
  tab, so the view reads the same however many a node has. Why not new top-level tabs in the Configuration dialog: its
  strip is the fixed `ConfigTab` set of the locked `infantry.shell.config`, every group would then need a home for
  nodes from many modules at once, and core's own "Tabs" group would add a tab to every window and move the default
  shots. Payload's tabs are per collection, the same as per node here. A group named like a dialog tab does not move
  into it; that can come later if a module needs it. **Shots:** 29/32 default shots are 0 px; the three
  `settings-plugins-refused-*` differ by the Plugins tab's scrollbar thumb only (172-344 px), because the Registered
  view below the modules is longer now (status line, Enabled/Up/Down, fields, group strip). The baseline is left as it
  is (another worker is moving blocks; refresh it on merge).

## 7. Core vs overridable (input to LICENSE-0, 08877cc7)

| part | bucket |
|---|---|
| `Block`/`Place`/`BlockScope`/`LocalBlockSize` contract, primitive type names + fields (the JSON schema) | **plugin API** (what a plugin composes against; candidate for the permissive API module) |
| primitive implementations, resolver, `surface`, `imageViewerHost` | **core**, not overridable as types |
| skeleton nodes `window, images, dock` | core, `locked` (children insertable) |
| placeholders `frame, body, panes, windowLayer` (S1, 6p) | core default, **overridable per node** (windowLayer: injections only, it holds locked dialogs) |
| `connect`, `servers`, `switchError`, `serverUpdate`, `config` and config's trust sections (S3, 6r) | core, `locked`: a plugin must not replace where a token is typed, an update is approved or a plugin is disabled |
| `config.settings` and its fifteen sections (S3, 6r) | core-shipped, **overridable per node** |
| titleBar, panels, center, statusBar and its children, toast/assistant/board/diff/voice/sound/questions | core-shipped, **overridable per node** |

LICENSE-0 §4 asks how the percentages move. The overridable bucket grows with each decomposition step (section 8),
while the core bucket stays the primitives plus the skeleton, which is small and fixed.

## 8. Migration, one ticket per step, each 0 px (diffTabShots 32/32) plus the existing UI tests

| step | change | risk | weight |
|---|---|---|---|
| L0 | done: LAYOUT-1 P0 shots (27 + 5 chrome/compact/overflow) are the baseline; merge BLOCKS-SHELL-3 first | none | - |
| L1 | contract + primitives as unused code: `Place`, `BlockScope` (DockScope typealias), `LocalBlockSize` (alias), group/stack/row/column/spacer, `withChildren`; unit tests for place→modifier order and group flattening | none (nothing calls it) | 2 |
| L2 | status bar = one array (section 3); delete both slots and `StatusBar` | low: copy `weight(1f, fill=false)`, the 3-space prefixes, the 10dp starts | 3 |
| L3 | root = tree (section 2); App renders `CoreShell.root`; `Edge` → `place`; delete `DockHost`/`DockWindow`/`Edge` | medium: modifier order, overlay order, focus target on the surface column | 3 |
| L4 | `place.show` for left/right/drawer; drop their `!compact` checks | low | 1 |
| L5 | resolver + id-uniqueness test + `locked`; core-tree overrides from module.json behind `blocks.enabled` (BLOCKS-1 P3 applied to the core tree) | none with the flag off | 4 |
| L6 | header: titleBar's right group as children (LAYOUT-1 P3, re-expressed as nodes) | low-medium: conditional 10/8dp gaps move into items | 3 |
| L7 | header: left/middle (LAYOUT-1 P4); the drag modifier stays on the same boxes | medium | 3 |
| L8 | header compact: overflow node + its own children (LAYOUT-1 P6); 360/800 shots | medium | 3 |
| L9+ | panels' insides (PanelSections), then any leaf when it is touched anyway | per piece | - |

## Expensive to reverse

1. **Node ids, once L5 publishes them to plugins.** They become API. Rename any id now, before L5, or never. Today's
   ids (`infantry.shell.left`, `.footer`) are only names, which the system does not interpret, so keeping them is fine.
2. **`Place` field names and meanings in the JSON schema.** Every plugin tree depends on them. Add fields freely; never
   repurpose one.
3. **Primitive types being core-locked.** Opening them later is easy, but closing them after plugins rely on it is not.
4. **The `ShellContext` surface (6q).** Every member and every model type it returns is a compatibility promise; it
   only grows. Changing a value to a flow, or a domain split, later means a v2 next to v1.
5. Cheap to reverse: the choice of composition over `dock`, the starting set itself, and where `measure` sits.

## Impact on LAYOUT-1

LAYOUT-1's region model (`RegionItem`, `RegionHost`, region ids `header.left`…`footer.right`, a `regions` field in
module.json) is the system knowing the regions, which the owner direction rules out. Its P1-P6 should be retired. The
map, the risks and the P0 shots carry over into L2/L6-L8 above.
