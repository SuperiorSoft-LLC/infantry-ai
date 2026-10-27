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
- `locked` nodes refuse remove/replace/move/wrap. Inserting before/after or into them is still allowed.

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


### 6j. L13 (79c66a61): the small overlays stay leaves

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

## 7. Core vs overridable (input to LICENSE-0, 08877cc7)

| part | bucket |
|---|---|
| `Block`/`Place`/`BlockScope`/`LocalBlockSize` contract, primitive type names + fields (the JSON schema) | **plugin API** (what a plugin composes against; candidate for the permissive API module) |
| primitive implementations, resolver, `surface`, `imageViewerHost` | **core**, not overridable as types |
| skeleton nodes `window, images, dock, frame, body, panes, windowLayer` | core, `locked` (children insertable) |
| `connect`, `servers`, `switchError`, `serverUpdate`, `config` | core, `locked`: a plugin must not replace where a token is typed, an update is approved or a plugin is disabled |
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
4. Cheap to reverse: the choice of composition over `dock`, the starting set itself, and where `measure` sits.

## Impact on LAYOUT-1

LAYOUT-1's region model (`RegionItem`, `RegionHost`, region ids `header.left`…`footer.right`, a `regions` field in
module.json) is the system knowing the regions, which the owner direction rules out. Its P1-P6 should be retired. The
map, the risks and the P0 shots carry over into L2/L6-L8 above.
