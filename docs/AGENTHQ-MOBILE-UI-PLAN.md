# AgentHQ mobile-friendly UI: architecture plan (ARCH-MOBILE-1)

| | |
|---|---|
| Status | Proposal. Plan only: no production code was changed. |
| Ticket | ARCH-MOBILE-1 (`9138aaec`) |
| Read at | branch `arch-mobile-1`, commit `d209716e` (declared base `dfe3abd6`) |
| Author | architect agent, 2026-10-21 |
| Feeds | PANEL-TOGGLE-1 (`a7746b18`), MOBILE-ANDROID-1 (`3d61a3c8`), MOBILE-BIND-1 (`973949fb`), and the new tickets in section 5 |

**Owner goal (2026-10-21):** keep the current UI and make it mobile-friendly, not a rewrite. On a phone the left
and right panels become slide-out drawers. On Windows the user can hide either panel.

**Line references.** Every `File.kt:NNN` below was read at `d209716e`. Unless a longer path is given, files are in
`superiorSoftLibrary/agenthqUiCore/src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui/`. Sizes are in dp.
Statements marked *(computed)* are worked out from the code. Statements marked *(verify on device)* are what I
expect from Compose and Android, but nobody has confirmed them yet: there is no APK yet.

---

## Summary

1. **Why it breaks today.** The main row has fixed widths: sidebar 260, two 7dp drag handles, right panel 320. That
   is 594dp before the chat column gets anything (App.kt:364-472). On a 360dp phone the chat column is 0dp wide, so
   neither the messages nor the composer are on screen *(computed)*.
2. **The fix is one layout with a width switch, not a second UI.** A small pure `WindowWidthClass` uses Material's
   published 600/840 breakpoints and is read once at the App root.
   - **Wide windows:** today's three columns, and each side panel can be hidden.
   - **Narrow windows:** the chat fills the screen. The two panels slide in as drawers over a dimmed background.
3. **One state model for both.** The panels' open/closed state lives in `AppState` as a small pure `PanelLayout`.
   It changes only through Controller methods. PANEL-TOGGLE-1's Windows toggles and the phone drawers are the same
   two buttons calling the same methods. Only the meaning depends on the width: hide a column, or open a drawer.
4. **The switch cannot change Windows.**
   - The desktop window cannot be narrower than 900 (agentHqUi `AgentHqWindow.kt:39`). That is above the 840
     breakpoint, so the desktop always takes today's path.
   - The 42 test files that call `App(` render it at 1400-1800 wide, and so does the smoke window (`SmokeMain.kt`).
5. **The biggest finding is not about layout: the UI reads the daemon's files from its own disk.**
   - Sent-image thumbnails and ticket images are loaded by file path through the local filesystem
     (ChatAttachments.kt:210, TicketImages.kt:162 and :205). That only works when the UI and the daemon run on the
     same machine.
   - On a phone, every sent image would read "image expired" and ticket images would be blank. A Windows desktop
     connected to a remote daemon (a droplet) has the same problem.
   - The fix needs a way to read those images through the daemon, which is a contract change (section 3.3).
6. **The plan has 17 tickets (section 5): one existing, 16 new, 2 of them optional. Eleven are size S.** The order:
   - Panel state, then the Windows toggles, then the breakpoint, then the drawers.
   - Then the compact fixes, one surface per ticket. These can all be built and tested in the existing desktop JVM
     suite.
   - Then the Android-only work, which needs a real APK.

---

## 1. Layout inventory

### 1.1 The frame today

```
App()                                                   App.kt:182
└─ AgentHqTheme > Box(fillMaxSize)                      App.kt:354-356   overlays stack in this Box
   ├─ Column(onPreviewKeyEvent(::key))                  App.kt:357-358   window shortcuts, App.kt:317-352
   │  ├─ TitleBar, 36dp high                            App.kt:360-363, TitleBar.kt:85, :141
   │  ├─ Row(weight 1, fillMaxWidth)                    App.kt:364       THE THREE COLUMNS
   │  │  ├─ Sidebar, width = sidebarWidth (260)         App.kt:365-373, :539       tag "left-sidebar"
   │  │  ├─ ResizeHandle, 7dp                           App.kt:375                 tag "sidebar-resize-handle"
   │  │  ├─ centre Column(weight 1)                     App.kt:376
   │  │  │  ├─ CenterHeader                             App.kt:377, :831
   │  │  │  ├─ CenterTabBar (10 tabs, scrolls)          App.kt:378, :917, :939
   │  │  │  ├─ when (centerTab): CLI / Pods / Roles / Profiles / Triggers / Epics / Memory / Run / Git / Stats
   │  │  │  │                                           App.kt:379-396 (enum at App.kt:162)
   │  │  │  ├─ NoticeStrip                              App.kt:397, :1270
   │  │  │  ├─ NewAgentDialog, SpawnRoleRow, pickers    App.kt:403, :416, :423, :432
   │  │  │  └─ PromptBox (composer)                     App.kt:438, :1312
   │  │  ├─ ResizeHandle, 7dp                           App.kt:449                 tag "right-panel-resize-handle"
   │  │  └─ right Column, width = rightPanelWidth (320) App.kt:450                 tag "right-panel"
   │  │     ├─ TabBar: Changes / Review / Attention / Usage / Prompts / Skills   App.kt:451-462 (enum App.kt:167)
   │  │     └─ when (rightTab): the six panes           App.kt:463-470
   │  └─ StatusBar, 24dp high                           App.kt:473, :1663
   ├─ BoardModal (in-window overlay)                    App.kt:477, BoardModal.kt:81
   ├─ ProjectDiffModal                                  App.kt:484
   ├─ ConfigDialog                                      App.kt:488
   └─ ConnectDialog                                     App.kt:502
```

What matters for this plan:

- **Both panel widths are local `remember` state, kept in memory only.** The right panel is at App.kt:199 (whose
  comment says "no UI-prefs store exists yet") and the sidebar at App.kt:200. Drag limits (`model/SidebarWidth.kt`
  and `model/RightPanelWidth.kt`, lines 4-13):

  | Panel | Default | Min | Max |
  |---|---|---|---|
  | Sidebar | 260 | 200 | 480 |
  | Right panel | 320 | 280 | 640 |

- **The centre column already takes all remaining width** (App.kt:376, `weight(1f)`). Taking a side column out of
  the Row gives the chat that space with no other change.
- **`UiFlags` (App.kt:221) is the existing in-memory presentation state.** "Nothing here is written to disk"
  (`model/UiFlags.kt:29-30`; also `FLAGS_LIFETIME_NOTE` at :36).
- **Overlays are drawn inside the window, never as platform dialogs.** commonMain may not import
  `androidx.compose.ui.window` (BoardModal.kt:74-79; enforced by jvmTest `PlatformBoundaryTest`). The drawers must
  follow the same rule.
- **The module is already multiplatform.** `agenthqUiCore` has:
  - 84 `.kt` files in commonMain, 3 in jvmMain, and 4 in androidMain plus `AndroidManifest.xml`;
  - 59 commonTest files.

  The desktop entry point (`agentHqUi`) is a thin Window around the shared `AgentHqShell`.

### 1.2 Hard-coded sizes that matter on a narrow screen

| Where | Value | Effect below ~600dp |
|---|---|---|
| App.kt:200, App.kt:539 | sidebar 260 | takes 72% of a 360dp phone |
| App.kt:199, App.kt:450 | right panel 320 | gets whatever is left, see 1.3 |
| ResizeHandle.kt:43 | handle 7dp wide | 14dp of the row; far too thin for touch |
| TitleBar.kt:141 | title bar 36 high | fine |
| TitleBar.kt:161 | window buttons 46 wide | desktop only (no `WindowChrome` on Android, TitleBar.kt:66) |
| ProjectPicker.kt:86 | project picker 120-240 wide | 120dp minimum in the title bar |
| App.kt:763 | agent-row "⋯" menu 22×18 | touch target far below 48 |
| App.kt:1680 | status-bar cog 24 | touch target below 48 |
| BoardModal.kt:89, :111 | board inset 24, close box 28 | 48dp of a phone lost to the inset; small close target |
| Board.kt:202-206 | four equal board columns | about 70dp per column on a phone, see 1.3 |
| RolesAttention.kt:71 | roles list 230 wide | the detail gets about 130dp on a phone |
| StatsPane.kt:267-272 | chart 110 high, axis 44 wide | fine |
| StatsPane.kt:305-319 | sources table columns 56 / 150 (bar 120) | tight on a phone |
| App.kt:1062 | activity box max 160 high | fine |
| App.kt:1610 | pod name 110 wide | fine |
| App.kt:828, :841-842, :865 | header title keeps 160, chips get the rest | already adapts to the width |
| ConfigDialog.kt:68, NewAgentDialog.kt:88 | max height 560 / 520, scrolls | fine |
| NewAgentDialog.kt:139 | adaptive grid of 150 cells, max height 190 | adapts (2 cells on a phone) |
| EditorModal.kt:69 | max width 520, else fills the width | adapts |
| ProjectChangesPanel.kt:96 | branch menu max 360 | a maximum, not a fixed width; fine |
| ProjectGitPane.kt:411, ExecutorPicker.kt:123, SearchableSelect.kt:73 | list max heights 220 / 110 / 150 | fine |
| Triggers.kt:173 | a field 150 wide | fine |
| DiffModal.kt:188 | line-number gutter 44 | fine; the diff scrolls both ways (DiffModal.kt:134-138) |

### 1.3 What breaks below ~600dp

How the numbers are worked out: a Compose `Row` measures its fixed-width children first, in order, each against the
space still left. Weighted children share whatever remains. *(computed)*

| Window width | Sidebar | Right panel | Chat column |
|---|---|---|---|
| 360 (phone) | 260 | 86 (squeezed from 320) | **0** |
| 412 (large phone) | 260 | 138 | **0** |
| 600 | 260 | 320 | 6 |
| 840 | 260 | 320 | 246 |
| 900 (desktop minimum) | 260 | 320 | 306 |

Surface by surface:

1. **Chat and composer.** Not on screen below 594dp wide. This is the blocker.
2. **Title bar** (TitleBar.kt:97-137). The fixed parts already exceed a phone width, so the agent-avatar strip
   (`weight(1f)`, TitleBar.kt:108-119) gets no room and the connection chip is cut off *(computed; text widths
   estimated)*. The fixed parts are:
   - the logo plus the word "AgentHQ" (TitleBar.kt:101-105);
   - the project picker, at least 120 wide (ProjectPicker.kt:86);
   - the attention chip (TitleBar.kt:121-124);
   - the connection chip, which prints the full daemon URL with no line limit (TitleBar.kt:229).
3. **Status bar** (App.kt:1668-1685).
   - The shortcut legend (App.kt:1677) has no weight and no line limit, so it is measured first and gets clipped.
   - The daemon-status text (`weight(1f, fill = false)`, App.kt:1670-1673) only gets the space left over, which on
     a phone is none.

   On a phone the bar therefore shows a cut-off shortcut list and no connection status.
4. **Composer action row** (App.kt:1468-1515). Up to six Material buttons sit in one row: Send, Switch or New,
   Resume, Kill, Force kill, Interrupt. That needs at least 6 × 58 + 5 × 6 = 378dp, more than a 360dp phone, before
   the trailing hint text *(computed)*. Why 58: neither the button wrappers (Clickables.kt:62-99) nor `IconAction`
   (IconAction.kt:64-97) override Material 3's minimum button width, which is 58dp in the M3 versions I know.
5. **Board.** The modal is inset 24dp on each side (BoardModal.kt:89) and the board has four equal columns
   (Board.kt:202-206). At 360 that is about 70dp per column and 58dp cards *(computed)*, which is unusable.
6. **Roles.** The fixed 230dp list (RolesAttention.kt:71) leaves about 130dp for the role detail (RolesAttention.kt:103).
7. **Stats.** The sources table has fixed 56 and 150dp columns (StatsPane.kt:305-319). Tight, but it fits.
8. **Role chip rows do not scroll.** This affects the sidebar filter (App.kt:563-569) and the spawn role picker
   (App.kt:1720-1726). Roles that do not fit are cut off. This already happens on desktop when a project has many
   roles.
9. **Touch targets below 48dp.** See section 4.
10. **Desktop, for completeness.** At the maximum widths (480 + 640 + 14 = 1134) the chat is also squeezed to 0 on any
    window narrower than 1134, including the 900 minimum *(computed)*. That is a desktop issue today, though it
    needs both panels dragged to their maximum. PANEL-TOGGLE-1 gives a way out, and the optional
    DESKTOP-CENTER-MIN-1 (section 5) would fix it.

**Already fine at narrow widths:**

- The centre tab bar scrolls (App.kt:939).
- CenterHeader sizes its chips to the width (App.kt:841-865).
- The diff viewer scrolls both ways (DiffModal.kt:134-138).
- FlowRow panes wrap: EpicsPane.kt:149, PromptsPane.kt:90, SkillsPane.kt:92.
- The Profiles and Triggers chip rows scroll (Profiles.kt:131, Triggers.kt:160).
- The NewAgentDialog grid adapts (NewAgentDialog.kt:139).
- EditorModal caps its width instead of fixing it (EditorModal.kt:69).

---

## 2. Responsive strategy

### 2.1 Choosing the breakpoint

| Option | For | Against |
|---|---|---|
| A. CMP `material3-window-size-class` (`calculateWindowSizeClass()`) | standard names | a new dependency for one `when`; experimental API; each platform needs window access |
| **B. A pure `WindowWidthClass.of(widthDp)`, read with `BoxWithConstraints` at the App root (recommended)** | no new dependency; unit-testable in commonTest; same pattern as `SidebarWidth` / `RightPanelWidth`; `BoxWithConstraints` is already used (App.kt:841, DiffModal.kt:134) | we own three lines of thresholds |
| C. Branch on platform (Android means mobile) | trivial | wrong for tablets, foldables, split screen and Chromebooks; the compact layout could not be tested in the desktop suite |

The thresholds are Material's published window-size-class values, not numbers invented for this app:

```kotlin
// model/WindowWidthClass.kt: pure, commonMain, no Compose imports
enum class WindowWidthClass {
    COMPACT, MEDIUM, EXPANDED;
    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f
        fun of(widthDp: Float): WindowWidthClass = when {
            widthDp < MEDIUM_MIN_DP -> COMPACT
            widthDp < EXPANDED_MIN_DP -> MEDIUM
            else -> EXPANDED
        }
    }
}

// App root: computed once, from the WINDOW width (never from the space left after the panels,
// so dragging a panel can never flip the class).
BoxWithConstraints(Modifier.fillMaxSize()) {
    val widthClass = WindowWidthClass.of(maxWidth.value)
    CompositionLocalProvider(LocalWindowWidthClass provides widthClass) { /* today's Box, App.kt:356 */ }
}
```

`LocalWindowWidthClass` defaults to `EXPANDED`. Each compact fix (board, roles, composer) reads it without passing a
parameter through every pane. A component test that never provides it keeps today's layout.

Put the `BoxWithConstraints` at the root and nowhere that measures intrinsics. It is a subcompose layout, which
cannot answer intrinsic measurements.

### 2.2 What each width class shows

| Class | Window width | Side panels | Resize handles |
|---|---|---|---|
| EXPANDED | 840 and wider | docked columns, as today; each can be hidden (PANEL-TOGGLE-1) | shown with their panel |
| MEDIUM | 600 to 839 | v1: drawers, the same as COMPACT (question Q1) | hidden |
| COMPACT | under 600 | drawers; the chat fills the window | hidden |

`PanelMode` is `DOCKED` for EXPANDED and `DRAWER` for the other two classes.

**The phone screen, in words.**

- **Top bar:** the amber "A" mark, a ☰ button for the agent list, the project name, the number of items needing
  attention, a coloured connection dot, and a panel button on the right.
- **Below it:** the selected agent's chat, under the centre tab bar, which scrolls sideways.
- **Composer, at the bottom:** a Send button and a ⋮ menu that holds the other agent actions with their names.
- **☰** slides the agent list in from the left over a dimmed screen. Tapping an agent opens its chat and closes the
  list.
- **The panel button** slides Changes / Review / Attention / Usage / Prompts / Skills in from the right.
- **Closing:** Back, or a tap on the dimmed area, closes whichever drawer is open.

### 2.3 Where the open/closed state lives

| Option | For | Against |
|---|---|---|
| A. Local `remember` in App, like the widths and `UiFlags` | smallest change | the Controller and tests cannot see it, so only Compose UI tests can check it; the toggles and the drawers would each grow their own flags |
| **B. A `PanelLayout` value in `AppState`, changed through Controller methods (recommended)** | one source of truth for PANEL-TOGGLE-1 and the drawers; pure transitions, unit-tested in commonTest; matches the other client-only AppState fields (`selectedId` Model.kt:482, `spawnRole` :502, `pickedModel` :534, `dismissedPrompts` :560) and the `_state.update { it.copy(...) }` pattern (Controller.kt:243) | AppState grows by one field |
| C. A separate state holder or ViewModel | | a new pattern for two booleans and an enum |

`AppState` is created once (Controller.kt:120) and only ever changed through `_state.update` (46 lines in
Controller.kt). A client-only field therefore survives reconnects and project switches.

```kotlin
// model/PanelLayout.kt: pure, commonMain
enum class PanelSide { LEFT, RIGHT }

enum class PanelMode {
    DOCKED, DRAWER;
    companion object { fun of(c: WindowWidthClass) = if (c == WindowWidthClass.EXPANDED) DOCKED else DRAWER }
}

data class PanelLayout(
    val leftShown: Boolean = true,   // docked preference (wide windows); both true = today's look
    val rightShown: Boolean = true,
    val drawer: PanelSide? = null,   // narrow windows only; at most one drawer is open
) {
    fun isOpen(side: PanelSide, mode: PanelMode): Boolean = when (mode) {
        PanelMode.DOCKED -> if (side == PanelSide.LEFT) leftShown else rightShown
        PanelMode.DRAWER -> drawer == side
    }
    fun toggle(side: PanelSide, mode: PanelMode): PanelLayout = when (mode) {
        PanelMode.DOCKED -> if (side == PanelSide.LEFT) copy(leftShown = !leftShown) else copy(rightShown = !rightShown)
        PanelMode.DRAWER -> copy(drawer = if (drawer == side) null else side)
    }
    fun reveal(side: PanelSide, mode: PanelMode): PanelLayout = if (isOpen(side, mode)) this else toggle(side, mode)
    fun closeDrawer(): PanelLayout = copy(drawer = null)
}

// AppState:   val panels: PanelLayout = PanelLayout()
// Controller: fun togglePanel(side: PanelSide, mode: PanelMode)
//             fun revealPanel(side: PanelSide, mode: PanelMode)
//             fun closeDrawer()
```

The mode is passed in rather than stored. App works it out in the same frame from the window width, so the model
stays independent of the width and a phone never draws one frame of docked columns first.

**Rules.** Rules 1 to 4 are commonTest cases in PANEL-STATE-1. Rules 5 and 6 are UI tests in PANEL-TOGGLE-1.

1. **The same toggle means different things in each mode.** In docked mode it hides or shows the column; in drawer
   mode it opens or closes the drawer. Drawer mode never changes the docked preference. A tablet turned back to
   landscape therefore gets the columns the user chose, and turning it to portrait never pops a drawer open.
2. **At most one drawer is open.** Opening the other side replaces it.
3. **Tapping an agent in the left drawer closes the drawer.** That happens in App's `onSelect` (App.kt:367), not in
   `Controller.select`, so a selection the app makes by itself never closes a drawer under the user's finger.
4. **When the window becomes EXPANDED, App calls `closeDrawer()`** (an effect keyed on the mode). A stale drawer
   then never reappears on the next narrow layout.
5. **Anything that targets a hidden panel shows it first.** That covers the attention chip (App.kt:361),
   Ctrl+3/4/6/7 (App.kt:339, :340, :345, :346) and Ctrl+K (App.kt:322). Without this rule, they do nothing visible
   while their panel is hidden, in either mode.
6. **Ctrl+K must not focus the search box while the sidebar is not on screen.** It currently calls
   `searchFocus.requestFocus()` (App.kt:322). In the Compose versions I know, focusing a requester that is not
   attached throws `IllegalStateException`. BoardModal already wraps its own call in `runCatching`
   (BoardModal.kt:125). Show the sidebar first, then focus once it is composed.

**The widths stay where they are** (App.kt:199-200). They change on every drag event. In AppState, every drag frame
would be a new AppState and would recompose everything that takes `state` (reasoning, not measured). In drawer mode
they only act as each drawer's preferred width.

**Persistence: none for now, the same as today's widths and `UiFlags`.** There is no UI-prefs store (App.kt:199).
PANEL-TOGGLE-1 says so in its DONE report. The optional UI-PREFS-1 would add one (question Q3).

### 2.4 The drawers

| Option | For | Against |
|---|---|---|
| M3 `ModalNavigationDrawer` | ready-made | opens from the start (left) edge only, so a right drawer needs a flipped LayoutDirection; built for navigation menus, not a 260-640dp work panel |
| M3 `ModalBottomSheet` | ready-made | the wrong shape for an agent list or a change list |
| **An own `SlideOutPanel` overlay in commonMain (recommended)** | works on both sides; reuses BoardModal's scrim, Esc and focus pattern (BoardModal.kt:81-127); no platform window; keeps the existing test tags | about 100 lines that we own |

`SlideOutPanel` spec:

- **Placement.** Drawn in the root Box (App.kt:356) after the main Column and before BoardModal (App.kt:477), so
  modals and dialogs, including the FORM-MODAL work, stack above it.
- **Scrim.** The same colour as BoardModal's (BoardModal.kt:86). A tap on it closes the drawer. Use `pointerInput`,
  not `clickable`, for the semantics reason given at BoardModal.kt:85.
- **Width.** The smaller of the preferred width (`sidebarWidth` / `rightPanelWidth`) and the window width minus 56dp,
  so 56dp of scrim stays tappable (Material's modal-drawer guidance). On a 360 phone that is 260 on the left and 304
  on the right.
- **Animation.** Slide plus fade. `androidx.compose.animation` is already imported by App.kt, so no new dependency.
- **It closes on:**
  - a scrim tap;
  - Esc, for a desktop or hardware keyboard;
  - Android back (ANDROID-BACK-1);
  - selecting an agent (left drawer only);
  - the window becoming EXPANDED.
- **Content.**
  - The left drawer uses the same `Sidebar(...)` call.
  - The right-panel Column (App.kt:450-471) is extracted into a `RightPanel(...)` composable that both the docked
    column and the drawer use, so no pane code is duplicated.
  - Inside a drawer they keep the `left-sidebar` / `right-panel` tags. The drawer adds `left-drawer`, `right-drawer`
    and `drawer-scrim`.
- **Android back.** Add `expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)`.
  - The Android actual wraps `androidx.activity.compose.BackHandler`.
  - The JVM actual does nothing, because Esc already covers it.
  - This is the same expect/actual shape as the resize cursor (ResizeHandle.kt:27). It does not depend on whether
    CMP 1.10 ships a common back handler, which I have not verified.
  - Order: a modal or dialog closes first, then a drawer, then the system default (leave the app).

### 2.5 Where the toggle buttons go

| Option | For | Against |
|---|---|---|
| **The title bar: the sidebar toggle at the left edge, the right-panel toggle at the right edge before the connection chip (recommended)** | always on screen in both modes; on a phone the same two buttons are the drawer buttons (☰ and panel); a hidden panel can always come back | slightly further from the panel it hides |
| Inside each panel's own header (the screenshot on PANEL-TOGGLE-1 shows the reference app's toggle at the top right of the right panel's tab header) | right next to what it hides | once the panel is hidden, its button is gone with it, so a second "show" button is needed somewhere else anyway |
| The status bar | out of the way | a 24dp bar, far from the panels; on a phone it is the strip we are emptying |

This is question Q2. The toggles are `IconAction`s, so they get the hand cursor and a tooltip for free. Plain
material3 buttons are forbidden by `HandCursorGuardTest`.

### 2.6 Why Windows does not change

- **The desktop is always EXPANDED.** The desktop window is at least 900×560 (agentHqUi `AgentHqWindow.kt:39` and
  :52) and opens at 1400×860 (:49). Compose Desktop's dp are AWT logical pixels, so 900 is about 900dp, which is
  above 840 (reasoning; LAYOUT-BREAKPOINT-1 adds a test for it).
- **No App-level test can hit the new path.** In the 42 test files that call `App(` in `agentHqUi/src/test`, every
  call sits in a test window 1400-1800 wide (1700 in 31 places), judged by the nearest `runDesktopComposeUiTest`
  before it. The smoke window is 1400×860 (`SmokeMain.kt`). The only narrow (400×800) case
  in an App-level file is IconButtonsUiTest.kt:162. That file calls `App(` once, inside its 1700×1000 case
  (IconButtonsUiTest.kt:77-78).
- **The test tags keep their meaning.** `left-sidebar`, `right-panel`, `sidebar-resize-handle` and
  `right-panel-resize-handle` are present when their panel is docked and shown. Both panels start shown, so the
  first launch looks exactly like today.
- **HEADLESS-1 is not affected.** Nothing in sections 2 to 4 touches the daemon. The one daemon change in this plan,
  REMOTE-MEDIA-1, adds a read route and keeps the daemon free of UI classes.

---

## 3. Feature audit

### 3.1 Per surface

The verdict is one of three:

- **As-is:** works on a phone as it is.
- **Tweak:** needs a layout change for narrow widths.
- **Platform:** needs Android platform work.

**Centre tabs**

| Surface | Where | Verdict | Notes and ticket |
|---|---|---|---|
| CLI / chat | App.kt:1003 (`AgentChat`), App.kt:379-396 | As-is | Messages sit in a `SelectionContainer` with a `LazyColumn`. Code-block Copy uses `LocalClipboardManager` (App.kt:1112), which is text and works on both platforms. Selection by long-press *(verify on device)*. |
| Composer | App.kt:1312 (`PromptBox`) | Tweak + Platform | The action row is too wide (1.3 item 4): COMPACT-COMPOSER-1. Attach (App.kt:1349-1357, :1419) is disabled on Android because `canPickFiles` is false. Drop (App.kt:1359-1368, :1397) and Ctrl+V file paste (App.kt:1460) do nothing on Android: ANDROID-ATTACH-1. Enter-to-send (App.kt:1434-1463) is unreliable on soft keyboards (Q5). |
| Centre header | App.kt:831, :841-865, :898 | As-is | Already sizes itself to the width. The full model id shows on hover (App.kt:898); see section 4. |
| Centre tab bar | App.kt:917-939 | As-is | Scrolls. The "^1"-style hints are hidden on compact (COMPACT-CHROME-1). |
| Pods | App.kt:1585, :1610 | As-is | The 110dp name column fits. |
| Roles | RolesAttention.kt:65-103 | Tweak | A fixed 230dp list next to the detail. On compact: the list, then a tap opens the detail with a back arrow (ROLES-COMPACT-1). The icon picker scrolls (RolesAttention.kt:406). |
| Profiles | Profiles.kt:49, :131 | As-is | Its chip row scrolls. |
| Triggers | Triggers.kt:56, :160, :173 | As-is | Its chip row scrolls; one 150dp field. |
| Epics | EpicsPane.kt:57, :149 | As-is | FlowRow. |
| Memory | MemoryPane.kt:69, :186 | As-is | Check NoteModal's width on the first APK. |
| Run | RunPane.kt:46 | As-is | |
| Git | ProjectGitPane.kt:71, :113-119, :411 | As-is | There is no directory picker on Android (`LocalDirectoryPicker` is null, DirectoryPicker.kt:14), so only the text box shows. That is the right behaviour: the repository path is on the daemon's machine, not the phone's. |
| Stats | StatsPane.kt:73, :267-272, :305-319, :172 | Tweak | On compact the sources table stacks instead of using fixed columns (STATS-COMPACT-1). Copying the session id (StatsPane.kt:172) is plain text and works. |

**Board**

| Surface | Where | Verdict | Notes and ticket |
|---|---|---|---|
| Board | BoardModal.kt:81-127, Board.kt:155-219 | Tweak + Platform | About 70dp columns on a phone (1.3 item 5). On compact: a full-screen modal, one column at a time with a column switcher that shows counts, and a header row that wraps (BOARD-COMPACT-1). Ticket image paste (Board.kt:376) and attach (TicketImages.kt:140-155) depend on the platform (ANDROID-ATTACH-1). Ticket thumbnails need the daemon (section 3.3). |

**Right panel**

| Surface | Where | Verdict | Notes and ticket |
|---|---|---|---|
| Changes | ProjectChangesPanel.kt:181, :96 | As-is | Fits a 304dp drawer; the branch menu's 360 is only a maximum. |
| Diff | DiffModal.kt:85-198 | As-is | Scrolls both ways. Cramped on a phone but usable. |
| Review | App.kt:1555 | As-is | |
| Attention | RolesAttention.kt:432 | As-is | |
| Usage | UsagePane.kt:52 | As-is | |
| Prompts | PromptsPane.kt:44, :90 | As-is | FlowRow. |
| Skills | SkillsPane.kt:44, :92 | As-is | FlowRow. |

**Settings and dialogs**

| Surface | Where | Verdict | Notes and ticket |
|---|---|---|---|
| Settings (Config) | ConfigDialog.kt:43, :68, :185, :260 | Platform (hide, do not port) | Android has none of these entries: no Browse for the CLI locations (`pickFile` is null, AgentHqShell.kt:32), no Logs (`logsDir` is null, AgentHqShell.kt:33), no Updates (`updates` is null, AgentHqShell.kt:34). The CLI paths and the logs live on the daemon's machine, so a phone-side Browse or Logs would be wrong. The rest scrolls. |
| New agent | NewAgentDialog.kt:62, :88, :139 | As-is | The adaptive grid gives two cells. Check it on the first APK. |
| Editor modal | EditorModal.kt:50, :69 | As-is | Fills the width up to 520. |
| Connect | App.kt:1689 | As-is | URL and token are typed in. MOBILE-BIND-1 covers the daemon side. |

**Window chrome**

| Surface | Where | Verdict | Notes and ticket |
|---|---|---|---|
| Title bar | TitleBar.kt:85-139 | Tweak | 1.3 item 2 (COMPACT-CHROME-1). |
| Status bar | App.kt:1663-1686 | Tweak | 1.3 item 3 (COMPACT-CHROME-1). |
| Sidebar | App.kt:523-591 | Tweak | Role chips do not scroll (CHIP-ROWS-1). The search hint shows "^K" (App.kt:558; COMPACT-CHROME-1). The "⋯" menu needs touch (TOUCH-TARGETS-1). |

### 3.2 Android `PlatformHost` gaps

`AndroidPlatformHost` (androidMain `shell/AndroidPlatformHost.kt:10-17`) overrides four members: `setting`,
`readTextFile`, `readBytes` and `fileExists`, all through `java.io.File`. Everything else falls back to the interface
defaults (`shell/PlatformHost.kt:13-55`).

| Member | Desktop | Android today | Consequence | Ticket |
|---|---|---|---|---|
| `canPickFiles` / `pickFile` (:22, :25) | AWT FileDialog | `false` / `null` | Attach buttons are disabled in chat and on tickets. Also, `pickFile` returns a path *synchronously*. An Android picker is an asynchronous activity result that returns a `content://` URI, and `File(path)` cannot read one. | ANDROID-ATTACH-1: an async picker seam that returns bytes (`PickedImage`), not paths. The desktop adapter wraps today's FileDialog. |
| `clipboardImage` / `clipboardFile` (:43, :52) | AWT clipboard | `null` | No image paste (App.kt:1460, Board.kt:376, TicketImages.kt:155). | later (keyboard rich-content paste) |
| `droppedImages` / `droppedFiles` (:46, :49) | AWT drop | empty | Dropping a file does nothing. | later; the picker covers it |
| `fileSize` (:55) | file size | `null` | The size check falls back to reading the bytes (App.kt:1353-1354). Fine. | none |
| `logsDir` / `openFolder` (:28, :31) | logs folder | `null` / `false` | The Logs section is hidden. Correct. | none |
| `updates` (:34) | updater | `null` | No updates entry. | owner decision (Q8) |
| `LocalDirectoryPicker` (DirectoryPicker.kt:14) | Swing chooser (agentHqUi `Main.kt:15-16`) | `null` | Text box only. Correct for a remote daemon. | none |
| `LocalWindowChrome` (TitleBar.kt:66) | undecorated-window buttons | `null` | No window buttons. Correct. | none |
| `defaultDaemonUrl` (AndroidPlatformHost.kt:11) | port file / env | `ws://10.0.2.2:8787/ws`, the emulator's alias for the host PC | A physical device must be given a URL. | MOBILE-BIND-1 |

Gaps that sit outside `PlatformHost`:

- **Cleartext.** The manifest (androidMain `AndroidManifest.xml`) declares INTERNET (line 4). It sets neither
  `usesCleartextTraffic` nor a network security config. Android blocks cleartext by default for apps that target
  API 28 or later, so `ws://<LAN IP>` may be refused *(verify with the HTTP engine `PlatformHttpClient` uses)*.
  - Production should use `wss://` through the reverse proxy (the owner's plan).
  - For LAN development, use a debug-only network security config in the app module that MOBILE-ANDROID-1 creates.
- **Edge-to-edge and the keyboard.** An app that targets SDK 35 or later is drawn edge-to-edge. The title bar would
  then sit under the status bar, and `adjustResize` (manifest lines 8-13) may no longer lift the composer above the
  keyboard *(verify on device)*. ANDROID-INSETS-1 applies `WindowInsets.safeDrawing` and `imePadding`, which are
  zero on desktop.
- **Back.** See 2.4 (ANDROID-BACK-1).
- **Rotation and process death.** Rotation does not recreate the Activity (`configChanges`, manifest line 13; the comment
  on line 7 gives the reason: "the controller owns a live WebSocket"), so state survives it. Process death is different: AppState is rebuilt and the
  panels return to their defaults, which is acceptable because they are in-memory on desktop too.

### 3.3 The data-locality gap: the most important finding outside layout

Uploads already go over the wire: the image and file bytes are sent to the daemon (Controller.kt:737-770). **Reading
them back does not.** The UI loads them from the local disk by the daemon's own path:

- **Sent chat images.** `ChatSentImages` (ChatAttachments.kt:204-223) takes the `[image: <path>]` lines of a sent
  turn.
  - It asks `host.fileExists(path)` (ChatAttachments.kt:210) and shows "image expired" when the answer is `false`.
  - It draws the thumbnail with `rememberImage(path)`, which calls `host.readBytes(path)` (TicketImages.kt:201-205).
- **Ticket images.** The thumbnail path is built from the project's directory on the daemon's machine
  (`ticketImagePath(projectDirectory, relPath)`, TicketImages.kt:162) and read the same way.

This works on desktop today only because the UI and the daemon share one disk. On Android:

- `AndroidPlatformHost.fileExists` is `File(path).isFile`, which is `false` for a path that exists only on the daemon's
  machine. **So every sent image reads "image expired".**
- `readBytes` returns `null`, so ticket thumbnails are blank, except images uploaded in the same session, which fall
  back to the bytes still in memory.

A desktop UI connected to a daemon on another machine behaves the same way. The owner wants that case to work too
("connect to a local PC, a droplet, or anywhere else"). I found this by reading the code. I have not reproduced it,
because there is no APK yet and I did not run a remote daemon.

**The fix is REMOTE-MEDIA-1, size L, starting with a short contract design:**

1. The daemon serves an uploaded image's bytes over the authenticated channel, keyed by project and relative path.
2. The UI loads thumbnails through it. It keeps the local-disk read as a fast path only when the daemon is on
   loopback.
3. Interim first step: the Android host answers `null` ("cannot look") from `fileExists` for daemon paths, so the
   chat stops claiming "image expired".

This follows the standing rule that features land as daemon frames or routes with a thin UI on top, so both the
mobile and the desktop clients reuse them.

---

## 4. Input differences

| Desktop interaction | Where | What happens on touch | Touch equivalent | Ticket |
|---|---|---|---|---|
| The agent row's "⋯" menu appears on hover | App.kt:731, :755-799; invisible unless the row is selected or hovered (:764); 22×18 (:763) | No hover. Worse: tapping a row in the drawer selects it *and closes the drawer*, so an unselected agent's menu cannot be reached | In drawer mode the "⋯" is always visible, with a 48dp touch area around the same glyph | TOUCH-TARGETS-1 |
| Row highlight on hover | App.kt:672-675 | cosmetic only | none needed (the pressed ripple stays) | none |
| Tooltips on icon buttons (name and shortcut) | IconAction.kt:107-150 (pointer enter/exit :137-138, keyboard focus after it) | no hover | The Material `TooltipBox` also opens on long-press *(verify on device)*. Screen readers already get the name. On compact, the less obvious composer actions move into a ⋮ menu that shows their names. | COMPACT-COMPOSER-1 |
| Full model id shown on hover | App.kt:898 | no hover | long-press, the same `TooltipBox` *(verify on device)* | none |
| Right-click menus | none in commonMain (grep: no `onSecondaryClick`, `PointerButton.Secondary` or `ContextMenuArea`) | n/a | n/a | none |
| Long-press | none today | n/a | Any long-press added later needs a `combinedClickable` wrapper in Clickables.kt, because `HandCursorGuardTest` forbids importing it directly. | none |
| Keyboard shortcuts | App.kt:317-352. Hints: the status-bar legend (App.kt:1677), "^n" in tab labels (App.kt:451-462, :917-934), the search hint (App.kt:558), the Board's "^8" (BoardModal.kt:70) | no keyboard on most phones | Every shortcut already has an on-screen control except Ctrl+J (next agent needing input). For it: make the title-bar avatars tappable, since the amber ring already marks those agents (Q6). Hide the hints on compact. Tablet hardware keyboards keep the shortcuts. | COMPACT-CHROME-1 |
| Enter sends, Shift+Enter adds a new line | App.kt:1434-1463 | Soft keyboards do not reliably send key events | The Send button (already there). On compact, Enter adds a new line (Q5). | COMPACT-COMPOSER-1 |
| Ctrl+V pastes an image or file | App.kt:1460, Board.kt:376 | no Ctrl+V, and the host returns `null` | the Attach button with the system picker | ANDROID-ATTACH-1 |
| Drag and drop files | App.kt:1359-1368 and :1397; TicketImages.kt:120 and :130 | nothing to drag from | the Attach button with the system picker; a "Share to AgentHQ" target later | ANDROID-ATTACH-1 |
| Resize handles | ResizeHandle.kt:38-55 (7dp wide, horizontal drag) | 7dp is far below a 48dp target | Hidden in drawer mode, where there is nothing to resize. On a touch tablet in EXPANDED, the touch area could be widened while the 7dp look stays (optional). | MOBILE-DRAWERS-1 |
| Window drag, double-click to maximize | TitleBar.kt:94-96 | n/a | There is no window chrome on Android (TitleBar.kt:66, :126-136) | none |
| Small custom click targets | "⋯" 22×18 (App.kt:763), cog 24 (App.kt:1680), Board close 28 (BoardModal.kt:111), the connection chip's 4dp padding (TitleBar.kt:226), clickable chips (App.kt:564-567, :1722-1724, TitleBar.kt:122) | Material buttons claim Material's 48dp minimum touch area by themselves; these custom `clickable` boxes and chips do not *(verify on device)* | A 48dp minimum touch area in drawer mode only, so desktop spacing does not move | TOUCH-TARGETS-1 |
| Text selection | `SelectionContainer` in the chat (App.kt:1003) | Copy on right-click is a desktop thing | the platform's long-press selection handles | none |

---

## 5. Ticket breakdown

**Sizes.**

- **S:** one surface, one model or one composable. About a day for one worker, tests included.
- **M:** several files, or a new seam.
- **L:** daemon, contract and UI together.

**Rules for every ticket:**

- The existing desktop UI suite passes unchanged.
- The DONE report describes the resulting screen in words at 360 and at 1700 wide.
- One agent, one issue: anything new found mid-ticket becomes a new ticket.

**Waves 1 and 2 need no emulator.** The layout is chosen by width, not by platform. So
`runDesktopComposeUiTest(400, 800)`, which the suite already uses for component tests, exercises the compact layout
in the existing JVM tests.

```
Wave 1, desktop, first      PANEL-STATE-1 ──► PANEL-TOGGLE-1        CHIP-ROWS-1 (any time)
                            LAYOUT-BREAKPOINT-1 (in parallel)
Wave 2, desktop test rig    MOBILE-DRAWERS-1 (after all of wave 1 except CHIP-ROWS-1)
                            COMPACT-CHROME-1, COMPACT-COMPOSER-1, BOARD-COMPACT-1,
                            ROLES-COMPACT-1, STATS-COMPACT-1 (each needs only LAYOUT-BREAKPOINT-1)
                            TOUCH-TARGETS-1 (after MOBILE-DRAWERS-1)
Wave 3, needs an APK        MOBILE-ANDROID-1 ──► ANDROID-BACK-1, ANDROID-INSETS-1, ANDROID-ATTACH-1
                            MOBILE-BIND-1 ──► a phone reaching a real daemon
Daemon track, separate      REMOTE-MEDIA-1 (contract design first)
Optional                    UI-PREFS-1, DESKTOP-CENTER-MIN-1
```

### Wave 1: desktop, first

**1. PANEL-STATE-1. Size S. Depends on: nothing.**
- **Work:** add `PanelLayout`, `PanelSide` and `PanelMode` in `model/`, plus `AppState.panels`. Add the Controller
  methods `togglePanel`, `revealPanel` and `closeDrawer`. No UI change.
- **Done when:** commonTest covers rules 1-4 of section 2.3, and no UI file is touched.

**2. PANEL-TOGGLE-1 (existing, `a7746b18`). Size S to M. Depends on: PANEL-STATE-1.**
- **Work:**
  - Two `IconAction` toggles, placed per Q2.
  - A hidden panel leaves the composition together with its resize handle.
  - The attention chip and Ctrl+3/4/6/7 show the right panel.
  - Ctrl+K shows the sidebar, then focuses its search box.
  - State is kept in memory, and the DONE report says so.
- **Done when:**
  - The ticket's RED test passes: a toggle removes the `left-sidebar` / `right-panel` tags and a second click
    restores them.
  - There are tests for each path that shows a panel (rule 5).
  - There is a test that Ctrl+K with the sidebar hidden throws nothing (rule 6).
  - The default layout is unchanged.

**3. LAYOUT-BREAKPOINT-1. Size S. Depends on: nothing.**
- **Work:** add `WindowWidthClass`, `LocalWindowWidthClass` and the root `BoxWithConstraints`. Nothing visible
  changes.
- **Done when:**
  - commonTest checks `of()` at 599, 600, 839 and 840.
  - A UI test shows the class is EXPANDED at 900 and at 1700 wide.

**4. CHIP-ROWS-1. Size S. Depends on: nothing.**
- **Work:** the sidebar role filter (App.kt:563) and the spawn role picker (App.kt:1720) scroll sideways, like
  Profiles and Triggers already do.
- **Done when:** with more roles than fit, the last chip can be reached by scrolling. Desktop gets better: no more
  cut-off roles.

### Wave 2: desktop test rig

**5. MOBILE-DRAWERS-1. Size M. Depends on: PANEL-STATE-1, PANEL-TOGGLE-1 and LAYOUT-BREAKPOINT-1.**
- **Work:**
  - Extract `RightPanel(...)`. Do this as the first commit, so the later tickets land on a smaller App.kt.
  - Add `SlideOutPanel` (section 2.4).
  - In drawer mode, show the chat alone plus the drawers, with the handles hidden.
  - Apply the closing rules from section 2.4.
- **Done when**, in UI tests at 400×800:
  - there is no `left-sidebar` until the left toggle is tapped, and the toggle shows `left-drawer`;
  - a tap on the scrim closes it, and so do Esc and selecting an agent;
  - the right drawer shows the six tabs.

  At 1700×1000 nothing differs from today.

**6. COMPACT-CHROME-1. Size S. Depends on: LAYOUT-BREAKPOINT-1.**
- **Work, on compact:**
  - Title bar: the mark without the word, the project name shortened, attention shown as a count, and the
    connection as a coloured dot (the URL stays in the Connect dialog). The avatar strip is hidden or scrolls.
  - Status bar: the shortcut legend is hidden.
  - The "^n" hints are hidden in the tab labels and the search box.
- **Done when:** at 360 wide, the two toggles, attention and connection are all on screen and tappable. At 1700 the
  desktop text is unchanged.

**7. COMPACT-COMPOSER-1. Size S. Depends on: LAYOUT-BREAKPOINT-1.**
- **Work, on compact:**
  - Send and Switch/New stay in the row.
  - Resume, Kill, Force kill and Interrupt move into a ⋮ menu that shows their names.
  - The hint text gets its own line.
  - Enter follows the answer to Q5.
- **Done when:** at 360, every action can be reached. The desktop row is unchanged.

**8. TOUCH-TARGETS-1. Size S. Depends on: LAYOUT-BREAKPOINT-1 and MOBILE-DRAWERS-1.**
- **Work:** in drawer mode, give the custom clickables listed in section 4 a 48dp touch area, and make the "⋯" always
  visible.
- **Done when:**
  - at 400, those targets measure at least 48dp and the "⋯" shows on an unselected row;
  - at 1700, the bounds are unchanged.

**9. BOARD-COMPACT-1. Size M. Depends on: LAYOUT-BREAKPOINT-1.**
- **Work, on compact:**
  - The board fills the screen, with no 24dp inset.
  - One column shows at a time, with a switcher that shows each column's count.
  - The header row wraps.
- **Done when:** at 360, every column can be reached and Start, Done and Reopen work. The desktop board keeps its
  four columns.

**10. ROLES-COMPACT-1. Size S. Depends on: LAYOUT-BREAKPOINT-1.**
- **Work:** on compact, the roles list comes first, and a tap opens the detail with a back arrow.
- **Done when:** a UI test at 360 passes. The desktop keeps the list and the detail side by side.

**11. STATS-COMPACT-1. Size S. Depends on: LAYOUT-BREAKPOINT-1.**
- **Work:** on compact the sources table stacks. The chart is unchanged.
- **Done when:** a UI test at 360 passes. The desktop is unchanged.

### Wave 3: needs an APK

**12. ANDROID-BACK-1. Size S. Depends on: MOBILE-DRAWERS-1 and MOBILE-ANDROID-1.**
- **Work:** add the `PlatformBackHandler` expect/actual. Back closes the board or a dialog first, then a drawer.
- **Done when:**
  - the JVM actual does nothing and `PlatformBoundaryTest` passes;
  - back has been checked by hand on the APK.

**13. ANDROID-INSETS-1. Size S. Depends on: MOBILE-ANDROID-1.**
- **Work:** apply `WindowInsets.safeDrawing` at the root and `imePadding` on the composer.
- **Done when:** on the APK, the title bar sits below the status bar and the composer sits above the keyboard. The
  desktop insets are zero, so nothing changes there.

**14. ANDROID-ATTACH-1. Size M. Depends on: MOBILE-ANDROID-1.**
- **Work:**
  - Add an async picker seam that returns bytes, not paths, for example
    `suspend fun pickFiles(title: String, imagesOnly: Boolean): List<PickedImage>`.
  - Android implements it with the system document or photo picker and the `ContentResolver`. The desktop adapter
    wraps today's FileDialog.
  - Move the callers to it: App.kt:1349-1357 and TicketImages.kt:140-146.
- **Done when:** desktop attach behaves as today (the existing tests pass), and on the APK an image can be attached
  to a chat and to a ticket.

### Daemon track and optional tickets

**15. REMOTE-MEDIA-1. Size L. Depends on: a short contract design (architect).**
- **Work:** described in section 3.3, interim first step included.
- **Done when:**
  - In an E2E run, a desktop UI shows sent images and ticket images from a daemon that does not share its disk.
  - HEADLESS-1 still passes.
  - The loopback desktop is unchanged.

**16. UI-PREFS-1. Size M. Optional (Q3).**
- **Work:** add a `PlatformHost` preferences seam: a file on desktop, `SharedPreferences` on Android. Persist the
  docked panel choices and the widths.
- **Done when:** a restart keeps the panel state, and a first launch is unchanged.

**17. DESKTOP-CENTER-MIN-1. Size S. Optional (Q10).**
- **Work:** at layout time, limit the docked panel widths so the chat keeps a minimum width. The owner picks the
  number, or drops the ticket.
- **Done when:** at 900 wide with both panels dragged to their maximum, the chat is still usable. The default
  layout is unchanged.

**Existing tickets this plan relies on:**

- MOBILE-ANDROID-1 (`3d61a3c8`): the app module, the APK and the launch.
- MOBILE-BIND-1 (`973949fb`): the daemon's bind address and token, so a phone can reach it.

FORM-MODAL-1 and FORM-MODAL-2 are already moving forms into the shared modal. Their modal must draw above the
drawers, which the z-order rule in section 2.4 already gives.

---

## 6. Risks and open questions

### 6.1 Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Several tickets edit App.kt (1,737 lines) at once: PANEL-TOGGLE-1, MOBILE-DRAWERS-1, and FORM-MODAL-1/2 already in progress | merge conflicts | run them in the order of section 5; extracting `RightPanel` first shrinks App.kt for the tickets after it |
| Focusing a hidden panel's search box | an exception on Ctrl+K | rule 6, with a test in PANEL-TOGGLE-1 |
| A shortcut or chip that targets a hidden panel | silently does nothing | rule 5 |
| The panel flags in AppState recompose the tree on every toggle | negligible: these are clicks, not drags | the widths stay local (2.3) |
| Test density or scaling differs from what I expect | a desktop test could land in the wrong class | LAYOUT-BREAKPOINT-1 asserts EXPANDED at the real desktop sizes |
| Data locality (3.3) | broken images on the phone, and on a desktop with a remote daemon | REMOTE-MEDIA-1 |
| Cleartext `ws://` on Android | the connection is refused on a LAN | `wss://` through the proxy; a debug-only network security config (3.2) |
| Edge-to-edge on target SDK 35+ | the title bar sits under the status bar; the keyboard covers the composer | ANDROID-INSETS-1 |
| The app is backgrounded, or its process dies | the WebSocket drops; panel state resets | the Controller already reconnects; phone notifications are out of scope (Q9) |
| Platform behaviour I have not verified | the touch plan is wrong in places: TooltipBox long-press, Material's 48dp area, back handling, cleartext with the chosen engine | every "verify on device" item goes on the first-APK smoke checklist |
| The mobile work drifts into a second UI | maintenance cost doubles | one layout, chosen by width; a compact tweak only where section 3 asks for one |

### 6.2 Open questions for the owner

My recommended default comes first in each answer.

| # | Question | Recommendation |
|---|---|---|
| Q1 | Between 600 and 839dp (a tablet in portrait, a foldable), should the sidebar stay docked with only the right panel as a drawer? | **Drawers on both sides for v1.** It is one code path, and nothing on desktop can be that narrow. Revisit once there is a tablet to look at. |
| Q2 | Where do the hide/show buttons go? | **The title bar** (2.5). The right-panel-header placement in the reference screenshot leaves no button once the panel is hidden. |
| Q3 | Should the panel state survive a restart? | **Not yet**: in memory, like the widths and the flags today. UI-PREFS-1 can come later. |
| Q4 | Should the desktop minimum window width (900) come down, so a narrow or snapped desktop window gets the phone layout? | **No for now.** It is the guarantee that Windows does not change. |
| Q5 | On a phone keyboard, does Enter send or start a new line? | **A new line, with the Send button to send.** Hardware keyboards keep Enter-to-send. |
| Q6 | Should the title-bar avatars be tappable to switch agents (on desktop too)? | **Yes.** It is small, and it is the touch equivalent of Ctrl+J. |
| Q7 | Should the daemon serve image bytes (REMOTE-MEDIA-1, a contract change)? | **Yes.** A desktop with a remote daemon needs it too. |
| Q8 | How does the Android app get updates: Play Store, sideloading, or nothing? | Decide when MOBILE-ANDROID-1 is done. |
| Q9 | Should the phone notify when an agent needs input while the app is in the background? That needs a foreground service or a push channel, which is daemon work. | **Out of scope for v1**: reconnect when the app comes back to the foreground. |
| Q10 | Should the desktop keep the chat at a minimum width when both panels are dragged wide (DESKTOP-CENTER-MIN-1)? | **Optional.** Hiding a panel already solves it by hand. |

---

## Appendix: method and evidence

- **Source.** Every reference was read at `d209716e` on branch `arch-mobile-1`, the worktree under review, not the
  root checkout.
- **Counts were measured, not estimated:**
  - Source files: `find` (84 commonMain, 3 jvmMain, 4 androidMain, 59 commonTest, 3 jvmTest).
  - The 46 `_state.update` lines: `grep -c` on Controller.kt.
  - The App-level test widths: `grep` and `awk` over `agentHqUi/src/test`.
  - The absence of right-click and long-press code: `grep` over commonMain.
- **Computed, not measured:** the Row arithmetic in 1.3, the composer-row width, and the board-column widths.
- **Not done:** no production code was written, no test was run, no APK was built, nothing ran on a device or an
  emulator, and nothing was pushed.
