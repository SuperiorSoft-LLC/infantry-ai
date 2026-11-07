// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.superiorsoftllc.infantry.ui.DragPayload
import com.superiorsoftllc.infantry.ui.DragSeam
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.superiorsoftllc.infantry.ui.model.BoardDrop
import com.superiorsoftllc.infantry.ui.model.DropPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.EpicView
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.model.branchLine
import com.superiorsoftllc.infantry.ui.model.ReportFlag
import com.superiorsoftllc.infantry.ui.model.reportFlagFor
import com.superiorsoftllc.infantry.ui.model.WatchFlag
import com.superiorsoftllc.infantry.ui.model.TicketPins
import com.superiorsoftllc.infantry.ui.model.ContextLimitInput
import com.superiorsoftllc.infantry.ui.model.contextLimitLine
import com.superiorsoftllc.infantry.ui.model.contextLimitNoteFor
import com.superiorsoftllc.infantry.ui.model.effortLine
import com.superiorsoftllc.infantry.ui.model.effortNoteFor
import com.superiorsoftllc.infantry.ui.model.ticketEffortEdit
import com.superiorsoftllc.infantry.ui.model.executorLine
import com.superiorsoftllc.infantry.ui.model.formatContextLimit
import com.superiorsoftllc.infantry.ui.model.ticketContextLimitEdit
import com.superiorsoftllc.infantry.ui.model.ticketPinEdit
import com.superiorsoftllc.infantry.ui.model.ticketTypeLabel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.ImageUploadOutcome
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.shell.PickedImage
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlinx.coroutines.launch

/** M2-1: the role behind a ticket's assignee: a profile's role, or the `role:<role>` lane's role, else none. */
internal fun assigneeRole(deps: BoardDeps, agentId: String): String? =
    deps.roles.profiles.firstOrNull { it.id == agentId }?.role
        ?: agentId.removePrefix("role:").takeIf { agentId.startsWith("role:") }
        ?: deps.agent(agentId)?.role

/** M2-14: the board's Epic filter anchor, its options (`all`, `none`, then each epic id) and a card's epic chip. */
const val BOARD_EPIC_FILTER_TAG = "board-epic-filter"
const val BOARD_EPIC_FILTER_OPTION_TAG_PREFIX = "board-epic-filter-option-"
/** M2-14: the new-ticket form's Epic picker anchor and its options (`none`, then each epic id). */
const val TICKET_EPIC_PICKER_TAG = "ticket-epic-picker"
const val TICKET_EPIC_OPTION_TAG_PREFIX = "ticket-epic-option-"
/** M2-17: a card's clickable summary (+ ticket id), and its edit form's fields, Epic picker, Save and refusal line. */
const val TICKET_CARD_TAG_PREFIX = "ticket-card-"
/** A card's six-dot drag handle, the line a card that snapped back shows, and a board column (by status). */
const val TICKET_HANDLE_TAG_PREFIX = "ticket-handle-"
const val TICKET_DROP_NOTE_TAG_PREFIX = "ticket-drop-note-"
/** FIX (web, QA F11): the inline role pick a no-role Start offers, + ticket id. */
const val TICKET_ROLE_NUDGE_TAG_PREFIX = "ticket-role-nudge-"
const val BOARD_COLUMN_TAG_PREFIX = "board-column-"

/**
 * MOBILE-LAYOUT-2: a column's width in a compact (below 600dp) window, where the columns scroll sideways instead of
 * sharing the width: four columns in ~350dp left ~52dp of card, too narrow for a card's Done/Back/Agent buttons.
 */
val BoardCompactColumnWidth = 280.dp
const val TICKET_EDIT_TITLE_TAG = "ticket-edit-title"
const val TICKET_EDIT_DESCRIPTION_TAG = "ticket-edit-description"
const val TICKET_EDIT_EPIC_PICKER_TAG = "ticket-edit-epic-picker"
const val TICKET_EDIT_EPIC_OPTION_TAG_PREFIX = "ticket-edit-epic-option-"
const val TICKET_EDIT_SAVE_TAG = "ticket-edit-save"
const val TICKET_EDIT_ERROR_TAG = "ticket-edit-error"

/** M2-14: the board's Epic filter. [All] shows every ticket, [NoEpic] those filed under none, [Only] one epic's. */
internal sealed interface EpicFilter {
    fun matches(t: Ticket): Boolean
    data object All : EpicFilter { override fun matches(t: Ticket) = true }
    data object NoEpic : EpicFilter { override fun matches(t: Ticket) = t.epicId.isNullOrBlank() }
    data class Only(val epicId: String) : EpicFilter { override fun matches(t: Ticket) = t.epicId == epicId }
}

/**
 * M2-14: a small labelled drop-down: an anchor reading "[label]: [selected]" that opens a menu of [options]
 * (tag suffix to label). Tags: [anchorTag] on the anchor, [optionTagPrefix] + suffix on each option.
 */
@Composable
private fun LabelledDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    anchorTag: String,
    optionTagPrefix: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                .clickable(role = Role.Button, onClickLabel = "Choose the $label") { open = true }
                .semantics { contentDescription = "$label: $selected" }
                .testTag(anchorTag)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label: ", color = Palette.Muted, fontSize = 11.sp)
            Text(selected, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(" ▾", color = Palette.Muted, fontSize = 11.sp)
        }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Raised)) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, color = Palette.Text, fontSize = 12.sp) },
                    onClick = { open = false; onSelect(key) },
                    modifier = Modifier.testTag(optionTagPrefix + key),
                )
            }
        }
    }
}

/** The column's title and the colour of its chip. */
private fun columnTitle(status: String): Pair<String, Color> = when (status) {
    TicketColumns.BACKLOG -> "Backlog" to Palette.Muted
    TicketColumns.TODO -> "To do" to Palette.Info
    TicketColumns.IN_PROGRESS -> "In progress" to Palette.Accent
    TicketColumns.PENDING -> "Pending" to Palette.Muted
    else -> "Done" to Palette.Ok
}

/**
 * The scrum board (contracts 1.5.0 / 1.6.0): the daemon's backlog in its four columns. The daemon owns the
 * columns, `pending` and the hand-out; this screen only renders them and sends the requests a click means
 * (create, start, finish, reopen, delete, start next).
 * BLOCKS-LAYOUT L16: [BoardPaneFrame] and its sections, as the `infantry.shell.board.pane` node draws them; every card is
 * [TicketCardFrame] and its sections, as the `infantry.shell.board.card` template draws them.
 */
/** S4d: the board over [deps] (the shell's context; RUNTIME-15: never the window's Controller). */
@Composable
fun BoardPane(deps: BoardDeps, onOpenAgent: (String) -> Unit) {
    BoardPaneFrame(deps, onOpenAgent) { board ->
        BoardFiltersSection(board)
        BoardUnassignedSection(board)
        BoardNewTicketSection(board)
        BoardEmptySection(board)
        BoardColumnsSection(board, Modifier.weight(1f)) { item ->
            TicketCardFrame(board, item) { card ->
                TicketCardSummarySection(card) {
                    TicketCardTitleSection(card)
                    TicketCardBadgesSection(card)
                    TicketCardDetailsSection(card)
                }
                TicketCardNoteSection(card)
                TicketCardEditSection(card)
                TicketCardActionsSection(card)
            }
        }
    }
}

/**
 * BLOCKS-LAYOUT L16: what the board pane's sections share, owned by [BoardPaneFrame]: the Epic filter, whether the
 * new-ticket form is open, the drag in flight and the drop session. Sections read it and change it only through its
 * callbacks.
 */
class BoardPaneHost internal constructor(
    val deps: BoardDeps,
    val onOpenAgent: (String) -> Unit,
    private val scope: CoroutineScope,
    private val filterState: MutableState<EpicFilter>,
    private val creatingState: MutableState<Boolean>,
    internal val epics: List<EpicView>,
    /** The filter in force: an epic no longer listed falls back to every ticket. */
    internal val active: EpicFilter,
    /** The board's view of one column, top first: what a drop's slot is counted in. */
    internal val columnOf: (String) -> List<Ticket>,
    internal val drag: BoardDrag,
    internal val session: BoardDropSession,
    private val hoverState: State<DragHover?>,
    /** EPIC-MOVE-1: the tickets ticked for a bulk Move to epic. */
    val selection: BoardSelection = BoardSelection(),
) {
    val live: Boolean get() = deps.live
    /** POLICY-1: whether the named action [actionId] may be performed now (live, and the daemon's policy allows it). */
    @Composable fun can(actionId: String, kind: String = com.superiorsoftllc.infantry.plugin.api.ActionKind.WRITE): Boolean = deps.can(actionId, kind)
    val creating: Boolean get() = creatingState.value
    internal val hover: DragHover? get() = hoverState.value

    internal fun pickFilter(f: EpicFilter) { filterState.value = f }
    fun toggleCreating() { creatingState.value = !creatingState.value }
    fun closeCreating() { creatingState.value = false }
    fun startNext() { scope.launch { deps.tickets.startNext() } }
    internal fun drop(t: Ticket, plan: DropPlan) { scope.launch { session.drop(plan, deps.tickets.ticket(t.id) ?: t) } }
}

/**
 * BLOCKS-LAYOUT L16: the board pane's frame: the image viewer host (IMG-4), the box the drag ghost follows the pointer
 * in, the epics load, the Epic filter, the new-ticket flag and the drop session; [content] goes straight into its
 * column, top to bottom.
 */
@Composable
fun BoardPaneFrame(deps: BoardDeps, onOpenAgent: (String) -> Unit, content: @Composable ColumnScope.(BoardPaneHost) -> Unit) {
    val actionScope = rememberCoroutineScope()
    val creating = remember { mutableStateOf(false) }
    val live = deps.live
    val projectId = deps.session.activeProjectId
    // The shell hands over a new context on every state change; the effects and the drop session read the current one.
    val current by rememberUpdatedState(deps)
    // M2-14: the Epic filter lives in memory only, and resets when the shell switches project.
    val filterState = remember(projectId) { mutableStateOf<EpicFilter>(EpicFilter.All) }
    val filter by filterState
    val epics = deps.boardEpics
    // M2-14: the board loads the project's epics itself, so the filter and the form work without the Epics pane.
    LaunchedEffect(projectId, live) {
        projectId?.let { if (live) current.tickets.loadEpics(it) }
    }
    // An epic no longer listed falls back to every ticket rather than an empty board.
    val active: EpicFilter = (filter as? EpicFilter.Only)?.takeIf { f -> epics.none { it.id == f.epicId } }?.let { EpicFilter.All } ?: filter
    // The board's view of one column, top first: what a drop's slot is counted in.
    val columnOf: (String) -> List<Ticket> = { status -> deps.tickets.column(status).filter { active.matches(it) } }
    val drag = remember { BoardDrag() }
    val session = remember { BoardDropSession(ticketMoves { current.tickets }) }
    val currentColumnOf by rememberUpdatedState(columnOf)
    // Every board change is read for a start's answer, in order, so a follow-up placement is never cancelled midway.
    LaunchedEffect(session) { current.tickets.updates.collect { visible -> session.observe(visible, currentColumnOf) } }
    val hover = remember(deps.tickets.visible, active) { derivedStateOf { drag.hover(columnOf) } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // EPIC-MOVE-1: the multi-select lives in memory only, and resets when the shell switches project.
    val selection = remember(projectId) { BoardSelection() }
    val board = BoardPaneHost(deps, onOpenAgent, actionScope, filterState, creating, epics, active, columnOf, drag, session, hover, selection)
    // IMG-4: a ticket's thumbnail opens full size over the whole board.
    ImageViewerHost {
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
            // The grabbing cursor follows the pointer off the handle for as long as the drag is held.
            .then(if (drag.ticket != null) Modifier.dragHandleCursor(dragging = true) else Modifier),
    ) {
    Column(Modifier.fillMaxSize()) {
        content(board)
    }
    drag.ticket?.let { DragGhost(it, drag, origin) }
    }
    }
}

/** A section that draws several items, or one it cannot tag: a placement other than the default gets a Column to wear it. */
@Composable
private fun Placed(modifier: Modifier, content: @Composable () -> Unit) {
    if (modifier == Modifier) content() else Column(modifier) { content() }
}

/** BLOCKS-LAYOUT L16: the filter row (Epic, the ticket count, Start next, + New ticket); [modifier] goes on the row. */
@Composable
fun BoardFiltersSection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    val tickets = board.deps.tickets
    val active = board.active
    val epics = board.epics
    val shown = tickets.visible.count { active.matches(it) }
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelledDropdown(
            label = "Epic",
            selected = when (active) {
                EpicFilter.All -> "All epics"
                EpicFilter.NoEpic -> "No epic"
                is EpicFilter.Only -> epics.firstOrNull { it.id == active.epicId }?.name ?: "All epics"
            },
            options = listOf("all" to "All epics") + epics.map { it.id to it.name } + ("none" to "No epic"),
            anchorTag = BOARD_EPIC_FILTER_TAG,
            optionTagPrefix = BOARD_EPIC_FILTER_OPTION_TAG_PREFIX,
        ) { key -> board.pickFilter(when (key) { "all" -> EpicFilter.All; "none" -> EpicFilter.NoEpic; else -> EpicFilter.Only(key) }) }
        Spacer(Modifier.width(8.dp))
        Text(
            ticketCountLabel(shown, tickets.visible.size, active == EpicFilter.All),
            color = Palette.Muted, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
        )
        // ARCHIVE-1: the read-only Archive view of the project, with search and Restore.
        var showArchive by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showArchive = true }, modifier = Modifier.testTag(ARCHIVE_BUTTON_TAG)) { Text("Archive", fontSize = 12.sp) }
        if (showArchive) BoardArchiveDialog(board.deps) { showArchive = false }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { board.startNext() }, enabled = board.can("board.ticket.start", com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN) && tickets.column(TicketColumns.TODO).isNotEmpty()) {
            Text("Start next", fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { board.toggleCreating() }, enabled = board.can("board.ticket.save")) { Text(if (board.creating) "Close" else "+ New ticket", fontSize = 12.sp) }
    }
    // EPIC-MOVE-1: N selected, Move to epic..., Clear (only while tickets are ticked).
    BoardSelectionBar(board)
}

/** BLOCKS-LAYOUT L16: the "N unassigned tickets not shown" line, with a project selected; [modifier] goes on the line. */
@Composable
fun BoardUnassignedSection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    val deps = board.deps
    // Tickets with no project are not in a selected project's view; say so rather than look like an empty board.
    val unassigned = if (deps.session.activeProjectId == null) 0 else deps.tickets.all.count { it.projectId == null }
    if (unassigned > 0) {
        Text(
            "$unassigned unassigned ticket${if (unassigned == 1) "" else "s"} (no project) not shown; deselect the project to see all.",
            color = Palette.Muted, fontSize = 11.sp, modifier = modifier.padding(horizontal = 10.dp),
        )
    }
}

/** BLOCKS-LAYOUT L16: the new-ticket form, while it is open (+ New ticket). */
@Composable
fun BoardNewTicketSection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    if (!board.creating) return
    Placed(modifier) {
        TicketForm(board.deps, initialEpicId = (board.active as? EpicFilter.Only)?.epicId, onDone = { board.closeCreating() })
    }
}

/** BLOCKS-LAYOUT L16: what an empty backlog says (or that there is no daemon yet); [modifier] goes on its column. */
@Composable
fun BoardEmptySection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    if (board.deps.tickets.visible.isEmpty() && !board.creating) {
        Column(modifier.padding(16.dp)) {
            Text(if (board.live) "The backlog is empty." else "Connect to a daemon to load its backlog.", color = Palette.Text)
            Text("Create a ticket, give it an assignee (a profile or a role), then start it to hand it to that agent.", color = Palette.Muted, fontSize = 11.sp)
        }
    }
}

/**
 * BLOCKS-LAYOUT L16: one ticket's place on the board, as the columns hand it to its card: the ticket, the card's drag
 * look (where it is measured, the drop line, the faded copy while dragged), its drag handle, and the line a card that
 * snapped back shows.
 */
class BoardCardItem internal constructor(
    val ticket: Ticket,
    internal val look: Modifier,
    internal val handle: @Composable () -> Unit,
    internal val dropNote: String?,
    internal val onDismissNote: () -> Unit,
    internal val onStart: () -> Unit,
)

/**
 * BLOCKS-LAYOUT L16: the status columns (title, count, the drop target's border and the cards); [modifier] goes on the
 * row, and the board gives it the free height. [card] draws one ticket's card into its column's card list.
 */
@Composable
fun BoardColumnsSection(board: BoardPaneHost, modifier: Modifier = Modifier, card: @Composable ColumnScope.(BoardCardItem) -> Unit) {
    val backlog = board.deps.tickets
    // GATE-PANES-1: dragging (or its keyboard actions) moves a ticket: `board.ticket.move`, off while the policy refuses it.
    val canMove = board.can("board.ticket.move")
    val drag = board.drag
    val session = board.session
    val columnOf = board.columnOf
    val hover = board.hover
    // MOBILE-LAYOUT-2: a compact window gives each column a fixed width in a row that scrolls sideways; at 600dp and
    // above the columns share the width as before.
    val compact = board.deps.layout.compact
    val columnsScroll = rememberScrollState()
    val host = LocalPlatformHost.current
    val projectId = board.deps.session.activeProjectId
    val lanes = remember(projectId, host) { LaneCollapse(projectId, host::uiPref, host::saveUiPref) }
    drag.collapsed = if (compact) emptySet() else lanes.snapshot()
    Row(
        modifier.fillMaxWidth().then(if (compact) Modifier.horizontalScroll(columnsScroll) else Modifier).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TicketColumns.ALL.forEach { status ->
            val tickets = columnOf(status)
            val (title, color) = columnTitle(status)
            val target = hover?.takeIf { it.column == status }
            val refused = target?.plan is DropPlan.NotAllowed
            val shape = RoundedCornerShape(6.dp)
            // LANE-COLLAPSE-1: only where lanes sit side by side (not the compact, scrolling row).
            if (!compact && lanes.isCollapsed(status)) {
                CollapsedLane(status, title, color, tickets.size, target != null, refused, shape, drag) { lanes.toggle(status) }
                return@forEach
            }
            Column(
                (if (compact) Modifier.width(BoardCompactColumnWidth) else Modifier.weight(1f)).fillMaxHeight()
                    .onGloballyPositioned { drag.columns[status] = it.boundsInRoot() }
                    .clip(shape).background(Palette.Panel)
                    // The column the pointer is over: the accent when it takes the card, the danger colour when not.
                    .then(if (target != null) Modifier.border(2.dp, if (refused) Palette.Danger else Palette.Accent, shape) else Modifier)
                    .testTag(BOARD_COLUMN_TAG_PREFIX + status),
            ) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (refused) Text("can't drop here", color = Palette.Danger, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                    Chip("${tickets.size}", color)
                    if (!compact) {
                        IconAction(
                            AhqIcons.ChevronLeft, "Collapse lane", { lanes.toggle(status) },
                            Modifier.padding(start = 4.dp).testTag(BOARD_LANE_COLLAPSE_TAG_PREFIX + status),
                            style = IconActionStyle.TEXT, compact = true,
                        )
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The drop indicator: a line in the gap at the slot the card would land in.
                    val slot = target?.slot?.takeIf { target.plan !is DropPlan.NotAllowed && target.plan !is DropPlan.Stay }
                    val others = tickets.filter { it.id != drag.ticket?.id }
                    val lineAbove = slot?.let { others.getOrNull(it)?.id }
                    val lineBelow = slot?.takeIf { it >= others.size }?.let { tickets.lastOrNull()?.id }
                    if (slot != null && tickets.isEmpty()) DropLine(Modifier)
                    tickets.forEachIndexed { index, t ->
                        val handle = @Composable {
                            DragHandle(t, canMove, drag, cardMoves(t, index, columnOf, board::drop)) {
                                val plan = drag.hover(columnOf)?.plan ?: DropPlan.Stay(t.id)
                                if (!drag.end()) board.drop(t, plan)
                            }
                        }
                        val look = Modifier
                            // Unclipped: a card half scrolled out of its column still has its true centre.
                            .onGloballyPositioned { drag.cards[t.id] = Rect(it.positionInRoot(), it.size.toSize()) }
                            .dropLine(above = lineAbove == t.id, below = lineBelow == t.id)
                            .then(if (drag.ticket?.id == t.id) Modifier.graphicsLayer { alpha = 0.4f } else Modifier)
                        card(
                            BoardCardItem(
                                t, look, handle, session.notes[t.id], { session.dismiss(t.id) },
                                onStart = { board.drop(t, DropPlan.Start(t.id, backlog.column(TicketColumns.IN_PROGRESS).size)) },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one drag in flight on the board: the card, where the pointer is and what each column and card covers (all in
 * root coordinates). It only follows the pointer; a drop's meaning is [BoardDrop.plan].
 */
internal class BoardDrag {
    var ticket by mutableStateOf<Ticket?>(null)
        private set
    private var pointerState by mutableStateOf(Offset.Zero)
    var pointer: Offset
        get() = pointerState
        set(v) { pointerState = v; if (ticket != null) DragSeam.move(v) }
    /** The pointer's offset from the card's top-left when the drag began, so the ghost keeps the grab point. */
    var grab = Offset.Zero
        private set
    val columns = mutableStateMapOf<String, Rect>()
    val cards = mutableStateMapOf<String, Rect>()
    /** LANE-COLLAPSE-1: the lanes drawn as strips now. */
    var collapsed: Set<String> = emptySet()

    fun begin(t: Ticket, at: Offset) {
        grab = at - (cards[t.id]?.topLeft ?: at)
        pointerState = at
        ticket = t
        // AGENT-TASKS-7: a todo ticket is also offered to any plugin's drop target (an agent card) as kind "ticket".
        if (t.status == TicketColumns.TODO) DragSeam.begin(DragPayload("ticket", t.id, t.title), at)
    }

    /** Ends the drag; true when another plugin's drop target took the card (the board then applies no drop of its own). */
    fun end(): Boolean { val had = ticket != null; ticket = null; return had && DragSeam.end() }

    /** The column under the pointer, the slot in it and what a drop there means; null = not over a column. */
    fun hover(columnOf: (String) -> List<Ticket>): DragHover? {
        val t = ticket ?: return null
        val column = columns.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return null
        val others = columnOf(column).filter { it.id != t.id }
        // LANE-COLLAPSE-1: a collapsed lane shows no cards; a drop on its strip goes to the lane's end.
        val slot = if (column in collapsed) others.size else BoardDrop.slotAt(others.map { cards[it.id]?.center?.y ?: 0f }, pointer.y)
        return DragHover(column, slot, BoardDrop.plan(columnOf, t, column, slot))
    }
}

/**
 * LANE-COLLAPSE-1: a collapsed lane, a narrow vertical strip: the expand chevron, the lane's name rotated, its count.
 * A click anywhere on it expands it; it stays a drop target (its bounds are the lane's) and keeps its place in the row.
 */
@Composable
private fun CollapsedLane(
    status: String, title: String, color: androidx.compose.ui.graphics.Color, count: Int,
    targeted: Boolean, refused: Boolean, shape: RoundedCornerShape, drag: BoardDrag, onExpand: () -> Unit,
) {
    Column(
        Modifier.width(BoardCollapsedLaneWidth).fillMaxHeight()
            .onGloballyPositioned { drag.columns[status] = it.boundsInRoot() }
            .clip(shape).background(Palette.Panel)
            .then(if (targeted) Modifier.border(2.dp, if (refused) Palette.Danger else Palette.Accent, shape) else Modifier)
            .clickable(onClickLabel = "Expand lane", role = Role.Button) { onExpand() }
            .semantics { contentDescription = "$title lane, collapsed, $count tickets" }
            .testTag(BOARD_LANE_STRIP_TAG_PREFIX + status)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconAction(AhqIcons.ChevronRight, "Expand lane", onExpand, style = IconActionStyle.TEXT, compact = true)
        Spacer(Modifier.height(6.dp))
        Chip("$count", color)
        Spacer(Modifier.height(10.dp))
        // The name reads bottom-to-top; the layout box is swapped so the strip stays narrow.
        Text(
            title.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.verticalText(),
        )
    }
}

/** The collapsed strip's width. */
internal val BoardCollapsedLaneWidth = 36.dp

/** Lays the content out rotated -90 degrees, measuring it with width and height swapped. */
private fun Modifier.verticalText(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxHeight.takeIf { it != androidx.compose.ui.unit.Constraints.Infinity } ?: androidx.compose.ui.unit.Constraints.Infinity))
    layout(p.height, p.width) { p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f } }
}

internal data class DragHover(val column: String, val slot: Int, val plan: DropPlan)

/**
 * The keyboard / screen-reader route to what a drag does, as the handle's custom actions: up, down, and to the end of
 * each other column the card may go to (In Progress is a start).
 */
private fun cardMoves(t: Ticket, index: Int, columnOf: (String) -> List<Ticket>, drop: (Ticket, DropPlan) -> Unit): List<Pair<String, () -> Unit>> {
    val size = columnOf(t.status).size
    val moves = mutableListOf<Pair<String, () -> Unit>>()
    if (index > 0) moves += "Move up" to { drop(t, BoardDrop.plan(columnOf, t, t.status, index - 1)) }
    if (index < size - 1) moves += "Move down" to { drop(t, BoardDrop.plan(columnOf, t, t.status, index + 1)) }
    TicketColumns.ALL.filter { it != t.status && BoardDrop.refusal(t.status, it) == null }.forEach { to ->
        val label = if (to == TicketColumns.IN_PROGRESS) "Start (move to In progress)" else "Move to ${columnTitle(to).first}"
        moves += label to { drop(t, BoardDrop.plan(columnOf, t, to, columnOf(to).size)) }
    }
    return moves
}

/** The drop indicator drawn in the gap above and/or below a card, outside its bounds so nothing reflows. */
private fun Modifier.dropLine(above: Boolean, below: Boolean): Modifier = if (!above && !below) this else drawWithContent {
    drawContent()
    val thick = 3.dp.toPx()
    val gap = 6.dp.toPx()
    if (above) drawRect(Palette.Accent, Offset(0f, -(gap + thick) / 2), Size(size.width, thick))
    if (below) drawRect(Palette.Accent, Offset(0f, size.height + (gap - thick) / 2), Size(size.width, thick))
}

/** The drop indicator alone, in a column with no card. */
@Composable
private fun DropLine(modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Palette.Accent))
}

/** The six-dot grip: the only place a card is picked up. Hover shows grab, a held drag grabbing. */
@Composable
private fun DragHandle(t: Ticket, enabled: Boolean, drag: BoardDrag, actions: List<Pair<String, () -> Unit>>, onDrop: () -> Unit) {
    val current by rememberUpdatedState(t)
    val dropped by rememberUpdatedState(onDrop)
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dragging = drag.ticket?.id == t.id
    Box(
        Modifier.size(width = 16.dp, height = 22.dp)
            .dragHandleCursor(enabled, dragging)
            .semantics {
                contentDescription = "Drag ${t.title}"
                if (enabled) customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
            }
            .testTag(TICKET_HANDLE_TAG_PREFIX + t.id)
            .onGloballyPositioned { place[0] = it }
            .pointerInput(t.id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { at -> place[0]?.takeIf { it.isAttached }?.let { drag.begin(current, it.localToRoot(at)) } },
                    onDrag = { change, _ ->
                        change.consume()
                        place[0]?.takeIf { it.isAttached }?.let { drag.pointer = it.localToRoot(change.position) }
                    },
                    onDragEnd = { dropped() },
                    onDragCancel = { drag.end(); DragSeam.cancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val dot = if (enabled) Palette.Muted else Palette.Muted.copy(alpha = 0.4f)
        Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
            val r = 1.5.dp.toPx()
            for (col in 0..1) for (row in 0..2) {
                drawCircle(dot, r, Offset(r + col * (size.width - 2 * r), r + row * (size.height - 2 * r) / 2))
            }
        }
    }
}

/** What follows the pointer while a card is dragged: the card's title at the card's width, where it was grabbed. */
@Composable
private fun DragGhost(t: Ticket, drag: BoardDrag, origin: Offset) {
    val width = with(LocalDensity.current) { (drag.cards[t.id]?.width ?: 200f).toDp() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.offset { (drag.pointer - drag.grab - origin).round() }
            .zIndex(10f).width(width).graphicsLayer { alpha = 0.92f }
            .clip(shape).background(Palette.Raised).border(1.dp, Palette.Accent, shape).padding(8.dp),
    ) {
        Text(t.title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

/**
 * BLOCKS-LAYOUT L16: what one card's sections share, owned by [TicketCardFrame]: the ticket, whether its edit form is
 * open, the drop note. Sections read it and change state only through its callbacks.
 */
class BoardCardHost internal constructor(
    private val board: BoardPaneHost,
    private val item: BoardCardItem,
    private val scope: CoroutineScope,
    private val openState: MutableState<Boolean>,
) {
    val ticket: Ticket get() = item.ticket
    val deps: BoardDeps get() = board.deps
    val live: Boolean get() = board.live
    /** POLICY-1: whether the named action [actionId] may be performed on this card now. */
    @Composable fun can(actionId: String, kind: String = com.superiorsoftllc.infantry.plugin.api.ActionKind.WRITE): Boolean = board.can(actionId, kind)
    /** The edit form is open (the summary toggles it). */
    val open: Boolean get() = openState.value
    val dropNote: String? get() = item.dropNote
    internal val handle: @Composable () -> Unit get() = item.handle

    fun toggleOpen() { openState.value = !openState.value }
    fun close() { openState.value = false }
    fun dismissNote() = item.onDismissNote()
    fun start() = item.onStart()
    suspend fun move(status: String): CommandResult = deps.tickets.move(ticket.id, status)
    fun delete() { scope.launch { deps.tickets.delete(ticket.id) } }
    fun openAgent(sessionId: String) = board.onOpenAgent(sessionId)
    /** EPIC-MOVE-1: the card is ticked for a bulk move. */
    val selected: Boolean get() = board.selection.has(ticket.id)
    fun toggleSelected() = board.selection.toggle(ticket.id)
    /** The project's epics (for the card's Move to epic...). */
    val epics: List<EpicView> get() = board.epics
}

/**
 * BLOCKS-LAYOUT L16: one ticket's card frame (the rounded raised column, the drag look, the edit form's open flag);
 * [content] goes straight into its column, top to bottom. [modifier] (the card's placement) comes before the drag look.
 */
@Composable
fun TicketCardFrame(board: BoardPaneHost, item: BoardCardItem, modifier: Modifier = Modifier, content: @Composable ColumnScope.(BoardCardHost) -> Unit) {
    val actionScope = rememberCoroutineScope()
    val open = remember(item.ticket.id) { mutableStateOf(false) }
    val card = BoardCardHost(board, item, actionScope, open)
    Column(
        modifier.then(item.look).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content(card)
    }
}

/**
 * BLOCKS-LAYOUT L16: the drag handle and the clickable summary (M2-17: it opens and closes the edit form; the form itself
 * is not a click target); [modifier] goes on the row, and [content] goes straight into the summary's column.
 */
@Composable
fun TicketCardSummarySection(card: BoardCardHost, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        card.handle()
        Spacer(Modifier.width(4.dp))
        TicketSelectBox(card.selected, card.ticket.title, card.ticket.id) { card.toggleSelected() }
        Spacer(Modifier.width(6.dp))
        Column(
            Modifier.weight(1f)
                .clickable(role = Role.Button, onClickLabel = if (card.open) "Close the ticket" else "Edit the ticket") { card.toggleOpen() }
                .testTag(TICKET_CARD_TAG_PREFIX + card.ticket.id),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

/** BLOCKS-LAYOUT L16: the card's title (six lines while open, else two); [modifier] goes on the text. */
@Composable
fun TicketCardTitleSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    Text(card.ticket.title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = if (card.open) 6 else 2, modifier = modifier)
}

/** BLOCKS-LAYOUT L16: the type, the assignee (avatar and chip), the epic, won't fix, queued and the report flag; [modifier] goes on the row. */
@Composable
fun TicketCardBadgesSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    val deps = card.deps
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        t.ticketType?.let { Chip(ticketTypeLabel(it)) }
        // M2-1: the assignee's avatar, the same portrait its agent shows in the list and the chat.
        t.agentId?.let { id ->
            val role = assigneeRole(deps, id)
            AgentAvatar(role, id, 16, deps.roles.role(role)?.color, Modifier.testTag("ticket-avatar-${t.id}"))
        }
        Chip(assigneeLabel(deps.roles.profiles, t.agentId), if (t.agentId == null) Palette.Muted else Palette.Info)
        // M2-14: the epic the ticket is filed under, by name (its short id until the epics have loaded).
        t.epicId?.takeIf { it.isNotBlank() }?.let { eid ->
            Chip(deps.epicName(eid) ?: eid.takeLast(8), Palette.Ok, Modifier.testTag("ticket-epic-${t.id}"))
        }
        if (t.wontFix) Chip("won't fix", Palette.Danger)
        if (t.queuedFor != null) Chip("queued", Palette.Accent)
        AgentTaskBadges(t)
        ReportFlagChip(deps.reportFlag(t), Modifier.testTag("ticket-report-${t.id}"))
        // KNOWLEDGE-PTR-2: how many tickets this one relates to (relates_to links + series members).
        RelatedView.count(t).takeIf { it > 0 }?.let { Chip("$it related", Palette.Muted, Modifier.testTag("ticket-related-count-${t.id}")) }
        // TICKET-COMMENTS-3: open comments, read once per ticket revision and cached in TicketCommentCounts.
        val shell = com.superiorsoftllc.infantry.ui.shell.LocalShellContext.current
        androidx.compose.runtime.LaunchedEffect(t.id, t.updatedAt) {
            shell?.let { DaemonTicketCommentsApi(it.tickets).meta(t.id) }
        }
        TicketCommentCounts.open[t.id]?.takeIf { it > 0 }?.let { Chip("$it open", Palette.Accent, Modifier.testTag("ticket-comments-count-${t.id}")) }
    }
}

/** BLOCKS-LAYOUT L16: the hold reason, what the ticket runs on, its branch, the worker's progress and the last run. */
@Composable
fun TicketCardDetailsSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    Placed(modifier) {
        t.holdReason?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, maxLines = if (card.open) 6 else 2) }
        // TICKET-EXEC-UI: what the ticket will run on, and where each value comes from (ticket / profile / role / default).
        executorLine(t.effectiveExecutor)?.let {
            Text("runs on $it", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, modifier = Modifier.testTag(TICKET_EXECUTOR_LINE_TAG_PREFIX + t.id))
        }
        // CONTEXT-LIMIT-2: the effective context limit and its source (ticket / default), like the executor line.
        contextLimitLine(t.effectiveContextLimit)?.let {
            Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, modifier = Modifier.testTag(TICKET_CONTEXT_LIMIT_LINE_TAG_PREFIX + t.id))
        }
        // EFFORT-1: the effective reasoning effort and its source (ticket / rule / agent / role / default), flagged when the harness does not apply it.
        effortLine(t.effectiveEffort)?.let {
            Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, modifier = Modifier.testTag(TICKET_EFFORT_LINE_TAG_PREFIX + t.id))
        }
        // BRANCH-PER-TASK 2/3: read-only; a ticket that never started has no branch line at all.
        t.branchLine()?.let { Text(it, color = Palette.Info, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag("ticket-branch-${t.id}")) }
        // Contract 1.117.0: the running ticket's worker reports how far along its job is.
        // RUNNER-POOL-4: the runner badge, only when sessions span more than one runner (nothing composed otherwise).
        if (t.status == TicketColumns.IN_PROGRESS) card.deps.agent(t.sessionId)?.session?.let { s ->
            runnerBadgeFor(s, card.deps.roles.agents.map { it.session })?.let { RunnerBadge(it, s.id) }
        }
        if (t.status == TicketColumns.IN_PROGRESS) card.deps.agent(t.sessionId)?.session?.progress?.let { ProgressMeter(it, "ticket-${t.id}") }
        t.lastRun?.let { run ->
            val outcome = run.outcome ?: "running"
            Text("last run: ${outcome.lowercase().replace('_', ' ')}", color = if (run.outcome == null || run.outcome == "SUCCEEDED") Palette.Muted else Palette.Danger, fontSize = 10.sp)
        }
    }
}

/**
 * BLOCKS-LAYOUT L16: why a drop snapped back (a refused column, or the daemon's reason a start did not start), and the
 * role pick a refusal for want of a role offers.
 */
@Composable
fun TicketCardNoteSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    val note = card.dropNote ?: return
    Placed(modifier) {
        LaunchedEffect(note) { delay(8_000); card.dismissNote() }
        Text(
            note, color = Palette.Danger, fontSize = 10.sp, maxLines = 3,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(TICKET_DROP_NOTE_TAG_PREFIX + t.id),
        )
        // A refusal for want of a role offers the role pick inline (focused), not the whole edit form.
        if (BoardDrop.isNoRoleNote(note) && t.agentId == null && !card.open) RoleNudge(t, card.deps)
    }
}

/** BLOCKS-LAYOUT L16: the edit form and the ticket's links, while the card is open. */
@Composable
fun TicketCardEditSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    if (!card.open) return
    Placed(modifier) {
        TicketEditForm(t, card.deps, onDone = { card.close() })
        RelatedList(t)
        if (t.links.isNotEmpty()) Text(t.links.joinToString(", ") { "${it.type.replace('_', ' ')} ${it.ticketId.takeLast(8)}" }, color = Palette.Muted, fontSize = 10.sp)
    }
}

/** BLOCKS-LAYOUT L16: the card's buttons (the column's moves, Agent, and Delete while open); [modifier] goes on the row. */
@Composable
fun TicketCardActionsSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    // POLICY-1: each card button is a named action (move, start, delete), gated by the daemon's policy.
    val canMove = card.can("board.ticket.move")
    // Start moves the ticket to In progress (ticket_move); the daemon then starts its agent.
    val canStart = card.can("board.ticket.move")
    val canDelete = card.can("board.ticket.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        when (t.status) {
            // 1.131.0: a backlog card is never started; it is promoted to the sprint first.
            TicketColumns.BACKLOG -> CardButton("To do", canMove, "Move to To do (the sprint)") { card.move(TicketColumns.TODO) }
            TicketColumns.TODO -> {
                CardButton("Start", canStart) { card.start(); CommandResult.Sent }
                CardButton("Backlog", canMove, "Park in Backlog") { card.move(TicketColumns.BACKLOG) }
            }
            TicketColumns.IN_PROGRESS -> {
                CardButton("Done", canMove) { card.move(TicketColumns.DONE) }
                CardButton("Back", canMove) { card.move(TicketColumns.TODO) }
            }
            TicketColumns.DONE -> CardButton("Reopen", canMove) { card.move(TicketColumns.TODO) }
        }
        t.sessionId?.takeIf { card.deps.agent(it) != null }?.let { sid ->
            TextButton(onClick = { card.openAgent(sid) }) { Text("Agent", fontSize = 11.sp) }
        }
        Spacer(Modifier.weight(1f))
        AgentTaskMenu(card)
        // TICKET-COMMENTS-1: the full-open modal for this ticket (the inline editor stays).
        val shellBoard = com.superiorsoftllc.infantry.ui.shell.LocalShellContext.current?.board
        if (shellBoard != null) TextButton(onClick = { shellBoard.openTicket(t.id) }, modifier = Modifier.testTag(TICKET_OPEN_TAG_PREFIX + t.id)) { Text("Open", fontSize = 11.sp) }
        if (card.open) TextButton(onClick = { card.delete() }, enabled = canDelete) { Text("Delete", color = Palette.Danger, fontSize = 11.sp) }
    }
}

/** FIX (web, QA F11): "pick a role first" under a card whose Start was refused for want of a role; a pick saves the assignee. */
@Composable
private fun RoleNudge(t: Ticket, deps: BoardDeps) {
    val actionScope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // GATE-PANES-1: picking the role edits the ticket (`board.ticket.save`).
    val canSave = deps.can("board.ticket.save")
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().testTag(TICKET_ROLE_NUDGE_TAG_PREFIX + t.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Pick a role first, then Start.", color = Palette.Danger, fontSize = 11.sp)
        TicketAssigneePicker(deps.roles, null, focus) { id ->
            if (!id.isNullOrEmpty() && canSave) actionScope.launch { deps.tickets.edit(t.id, agentId = id) }
        }
    }
}

@Composable
private fun CardButton(label: String, enabled: Boolean, description: String? = null, action: suspend () -> CommandResult) {
    val actionScope = rememberCoroutineScope()
    TextButton(
        onClick = { actionScope.launch { action() } },
        enabled = enabled,
        modifier = if (description == null) Modifier else Modifier.semantics { contentDescription = description },
    ) { Text(label, fontSize = 11.sp) }
}

/** M2-17: what an edit sent, trimmed as the daemon stores it; it has landed once the ticket reads back the same. */
private data class TicketEdit(
    val title: String, val description: String, val epicId: String?, val pins: TicketPins, val images: List<String>,
    val contextLimit: Int?, val effort: String?,
) {
    fun landedIn(t: Ticket): Boolean = t.title == title && RelatedView.human(t.description) == description &&
        t.epicId?.takeIf { it.isNotBlank() } == epicId && TicketPins.of(t) == pins && t.images == images &&
        t.contextLimit?.takeIf { it > 0 } == contextLimit && t.effort?.takeIf { it.isNotBlank() } == effort
}

/**
 * M2-17: edit a ticket's title, description and Epic (None or one of the active project's epics). Save sends only the
 * changed fields; the form stays open until the daemon's `ticket` answer reads back what was sent, and shows a
 * `ticket_save refused: ...` answer in red instead of closing (the BUG-PROF-1 ProfileForm pattern).
 */
@Composable
internal fun TicketEditForm(t: Ticket, deps: BoardDeps, onDone: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    val currentEpic = t.epicId?.takeIf { it.isNotBlank() }
    var title by remember(t.id) { mutableStateOf(t.title) }
    var description by remember(t.id) { mutableStateOf(RelatedView.human(t.description)) }
    var epicId by remember(t.id) { mutableStateOf(currentEpic) }
    // TICKET-EXEC-UI: the assignee ("" = unassigned) and the provider / model pins (null = inherit).
    val currentPins = TicketPins.of(t)
    var assignee by remember(t.id) { mutableStateOf(currentPins.agentId.orEmpty()) }
    var provider by remember(t.id) { mutableStateOf(currentPins.provider) }
    var model by remember(t.id) { mutableStateOf(currentPins.model) }
    // CONTEXT-LIMIT-2: off = no limit (the harness's own window).
    val currentLimit = t.contextLimit?.takeIf { it > 0 }
    var limitOn by remember(t.id) { mutableStateOf(currentLimit != null) }
    var limitText by remember(t.id) { mutableStateOf(currentLimit?.let { formatContextLimit(it) }.orEmpty()) }
    val limitParsed = ContextLimitInput.parse(limitText)
    val limit = if (limitOn) limitParsed.value else null
    // EFFORT-1: off = the harness default.
    val currentEffort = t.effort?.takeIf { it.isNotBlank() }
    var effortOn by remember(t.id) { mutableStateOf(currentEffort != null) }
    var effortLevel by remember(t.id) { mutableStateOf(currentEffort ?: "medium") }
    val effort = if (effortOn) effortLevel else null
    // IMG-4: the ticket's images as the form holds them; Save sends the whole list when it changed.
    var images by remember(t.id) { mutableStateOf(t.images) }
    var uploaded by remember(t.id) { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var uploading by remember(t.id) { mutableStateOf(false) }
    var imageError by remember(t.id) { mutableStateOf<String?>(null) }
    val host = LocalPlatformHost.current
    var pending by remember(t.id) { mutableStateOf<TicketEdit?>(null) }
    var refusal by remember(t.id) { mutableStateOf<String?>(null) }
    // IMG-4: each picked image is uploaded (purpose ticket) in turn; a stored one joins the form's list.
    fun attach(picked: List<PickedImage>) {
        actionScope.launch {
            uploading = true
            imageError = null
            try {
                for (image in picked) {
                    when (val r = deps.tickets.uploadImage(t.projectId, image.fileName, image.mimeType, image.bytes)) {
                        is ImageUploadOutcome.Stored -> {
                            uploaded = uploaded + (r.image.relPath to image.bytes)
                            if (r.image.relPath !in images) images = images + r.image.relPath
                        }
                        is ImageUploadOutcome.Refused -> imageError = "${image.fileName}: ${r.reason}"
                    }
                }
            } finally {
                uploading = false
            }
        }
    }
    LaunchedEffect(pending, deps.tickets.error, t) {
        val sent = pending ?: return@LaunchedEffect
        val error = deps.tickets.error
        if (error != null) {
            refusal = error
            pending = null
        } else if (sent.landedIn(t)) {
            pending = null
            onDone()
        }
    }
    val epics = deps.boardEpics
    val canUpload = deps.can("images.upload")
    // An epic outside the active project (or not loaded yet) still reads by name, else by its short id.
    val epicName = epicId?.let { id -> deps.epicName(id) ?: id.takeLast(8) } ?: "None"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
            // IMG-4: Ctrl/Cmd+V anywhere in the form attaches a clipboard image; text on the clipboard pastes as usual.
            .onPreviewKeyEvent { e ->
                val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                val image = if (paste && canUpload && pending == null && !uploading) host.clipboardImage() else null
                image?.let { attach(listOf(it)) } != null
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(TICKET_EDIT_TITLE_TAG))
        OutlinedTextField(
            description, { description = it }, label = { Text("Description (the agent's task)") }, minLines = 2, maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag(TICKET_EDIT_DESCRIPTION_TAG),
        )
        LabelledDropdown(
            label = "Epic",
            selected = epicName,
            options = listOf("none" to "None") + epics.map { it.id to it.name },
            anchorTag = TICKET_EDIT_EPIC_PICKER_TAG,
            optionTagPrefix = TICKET_EDIT_EPIC_OPTION_TAG_PREFIX,
        ) { key -> epicId = key.takeUnless { it == "none" } }
        TicketAssigneePicker(deps.roles, assignee) { assignee = it.orEmpty() }
        TicketExecutorField(deps.executor, t.id, provider, model) { p, m -> provider = p; model = m }
        ContextLimitField(
            limitOn, limitText, contextLimitNoteFor(deps.executor.providers, provider ?: t.effectiveExecutor?.provider?.value),
            limitOn && limitParsed.error != null, TICKET_CONTEXT_LIMIT_TAGS,
            onOn = { limitOn = it }, onText = { limitText = it }, textColor = Palette.Text,
        )
        EffortField(
            effortOn, effortLevel, effortNoteFor(deps.executor.providers, provider ?: t.effectiveExecutor?.provider?.value),
            TICKET_EFFORT_TAGS, onOn = { effortOn = it }, onLevel = { effortLevel = it }, textColor = Palette.Text,
        )
        if (limitOn) limitParsed.error?.let { Text("Context limit: $it", color = Palette.Danger, fontSize = 11.sp) }
        TicketImagesSection(
            images = images,
            projectDirectory = deps.session.projects.firstOrNull { it.id == t.projectId }?.directory,
            uploaded = uploaded,
            enabled = deps.can("images.upload") && pending == null,
            busy = uploading,
            error = imageError,
            onAttach = ::attach,
            onRemove = { relPath -> images = images - relPath },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = deps.can("board.ticket.save") && title.isNotBlank() && pending == null && !uploading && !(limitOn && limitParsed.error != null),
                modifier = Modifier.testTag(TICKET_EDIT_SAVE_TAG),
                onClick = {
                    actionScope.launch {
                        val pins = TicketPins(assignee.ifEmpty { null }, provider, model)
                        val edit = TicketEdit(title.trim(), description.trim(), epicId, pins, images, limit, effort)
                        if (edit.landedIn(t)) return@launch onDone() // nothing changed: nothing to send
                        refusal = null
                        // TICKET-EXEC-UI: only the pins that changed; "" clears one (unassigned / inherit).
                        val pinEdit = ticketPinEdit(currentPins, pins)
                        val r = deps.tickets.edit(
                            t.id,
                            title = edit.title.takeIf { it != t.title },
                            description = edit.description.takeIf { it != RelatedView.human(t.description) },
                            // "" files the ticket under no epic (contract 1.60.0).
                            epicId = if (edit.epicId != currentEpic) edit.epicId ?: "" else null,
                            agentId = pinEdit.agentId,
                            provider = pinEdit.provider,
                            model = pinEdit.model,
                            images = images.takeIf { it != t.images },
                            contextLimit = ticketContextLimitEdit(currentLimit, limit),
                            effort = ticketEffortEdit(currentEffort, effort),
                        )
                        when (r) {
                            is CommandResult.Sent -> pending = edit
                            else -> refusal = (r as? CommandResult.Refused)?.reason ?: "the save could not be sent"
                        }
                    }
                },
            ) { Text(if (pending != null) "Saving…" else "Save", fontSize = 12.sp) }
            TextButton(onClick = onDone) { Text("Cancel", fontSize = 12.sp) }
        }
        refusal?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(TICKET_EDIT_ERROR_TAG)) }
    }
}

/**
 * New ticket: a title, an optional description, who gets it and, TICKET-EXEC-UI, the provider / model it runs on
 * (inherit unless pinned). BUG-ROLE-3: the daemon no longer assigns a role on its own, so the form preselects the first
 * role lane it lists, visibly (unassigned while no profile exists); the create always sends that explicit choice.
 */
@Composable
private fun TicketForm(deps: BoardDeps, initialEpicId: String?, onDone: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // null = not picked yet (the first role lane, see below), "" = unassigned, else a profile id or role:<role>
    var picked by remember { mutableStateOf<String?>(null) }
    val assignee = picked ?: deps.roles.profiles.firstOrNull()?.let { "role:${it.role}" } ?: ""
    // M2-14: null = no epic; starts on the board's filtered epic, if any.
    var epicId by remember { mutableStateOf(initialEpicId) }
    // TICKET-EXEC-UI: null = inherit.
    var provider by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var limitOn by remember { mutableStateOf(false) }
    var limitText by remember { mutableStateOf("") }
    val limitParsed = ContextLimitInput.parse(limitText)
    var effortOn by remember { mutableStateOf(false) }
    var effortLevel by remember { mutableStateOf("medium") }
    val epics = deps.boardEpics
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Description (the agent's task)") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
        TicketAssigneePicker(deps.roles, assignee) { picked = it.orEmpty() }
        TicketExecutorField(deps.executor, "new-ticket", provider, model) { p, m -> provider = p; model = m }
        ContextLimitField(
            limitOn, limitText, contextLimitNoteFor(deps.executor.providers, provider),
            limitOn && limitParsed.error != null, TICKET_CONTEXT_LIMIT_TAGS,
            onOn = { limitOn = it }, onText = { limitText = it }, textColor = Palette.Text,
        )
        EffortField(
            effortOn, effortLevel, effortNoteFor(deps.executor.providers, provider),
            TICKET_EFFORT_TAGS, onOn = { effortOn = it }, onLevel = { effortLevel = it }, textColor = Palette.Text,
        )
        LabelledDropdown(
            label = "Epic",
            selected = epics.firstOrNull { it.id == epicId }?.name ?: "None",
            options = listOf("none" to "None") + epics.map { it.id to it.name },
            anchorTag = TICKET_EPIC_PICKER_TAG,
            optionTagPrefix = TICKET_EPIC_OPTION_TAG_PREFIX,
        ) { key -> epicId = key.takeUnless { it == "none" } }
        if (deps.roles.profiles.isEmpty()) Text("No agent profile exists yet: create one in the Profiles tab so a started ticket has someone to run it.", color = Palette.Muted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = deps.can("board.ticket.save") && title.isNotBlank() && !(limitOn && limitParsed.error != null),
                onClick = {
                    actionScope.launch {
                        if (deps.tickets.create(title, description, assignee, epicId?.takeIf { id -> epics.any { it.id == id } }, provider, model, if (limitOn) limitParsed.value else null, if (effortOn) effortLevel else null) is CommandResult.Sent) onDone()
                    }
                },
            ) { Text("Create ticket") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

/** "1 ticket" / "2 tickets", or "1 of 2 tickets" under an epic filter. */
internal fun ticketCountLabel(shown: Int, total: Int, all: Boolean): String {
    val noun = if (total == 1) "ticket" else "tickets"
    return if (all) "$total $noun" else "$shown of $total $noun"
}

/** KNOWLEDGE-PTR-2: the related tickets of an open card (series, linked, same files) with status and files. */
@Composable
internal fun RelatedList(t: Ticket) {
    val items = RelatedView.items(t.description)
    val linked = t.links.filter { it.type == "relates_to" }.map { it.ticketId }.filter { id -> items.none { it.ticketId == id } }
    if (items.isEmpty() && linked.isEmpty()) return
    Column(Modifier.testTag("ticket-related-${t.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Related", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { Text("${it.reason}: ${it.ticketId.takeLast(8)} ${it.title} [${it.status}]" + (it.files?.let { f -> " -> $f" } ?: ""), color = Palette.Muted, fontSize = 10.sp) }
        linked.forEach { Text("linked: ${it.takeLast(8)}", color = Palette.Muted, fontSize = 10.sp) }
    }
}
