package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
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
import com.superiorsoftllc.agenthq.ui.model.BoardDrop
import com.superiorsoftllc.agenthq.ui.model.DropPlan
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
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.EpicView
import com.superiorsoftllc.agenthq.ui.model.branchLine
import com.superiorsoftllc.agenthq.ui.model.ReportFlag
import com.superiorsoftllc.agenthq.ui.model.reportFlagFor
import com.superiorsoftllc.agenthq.ui.model.WatchFlag
import com.superiorsoftllc.agenthq.ui.model.TicketPins
import com.superiorsoftllc.agenthq.ui.model.executorLine
import com.superiorsoftllc.agenthq.ui.model.ticketPinEdit
import com.superiorsoftllc.agenthq.ui.model.ticketTypeLabel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.ImageUploadOutcome
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlinx.coroutines.launch

/** M2-1: the role behind a ticket's assignee: a profile's role, or the `role:<role>` lane's role, else none. */
internal fun assigneeRole(state: AppState, agentId: String): String? =
    state.visibleProfiles.firstOrNull { it.id == agentId }?.role
        ?: agentId.removePrefix("role:").takeIf { agentId.startsWith("role:") }
        ?: state.agents[agentId]?.role

/** What a ticket's assignee reads as: a profile's name when the id is one, else the raw `role:<role>` / id. */
fun assigneeLabel(state: AppState, agentId: String?): String = when {
    agentId == null -> "unassigned"
    else -> state.visibleProfiles.firstOrNull { it.id == agentId }?.name ?: agentId
}

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

/** M2-14: the epics a ticket can be filed under: the active project's, or every project's when none is selected. */
internal fun boardEpics(state: AppState): List<EpicView> =
    state.activeProjectId?.let { state.epics[it].orEmpty() } ?: state.epics.values.flatten()

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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Raised)) {
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
@Composable
fun BoardPane(state: AppState, controller: Controller, onOpenAgent: (String) -> Unit) {
    BoardPaneFrame(state, controller, onOpenAgent) { board ->
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
    val state: AppState,
    val controller: Controller,
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
) {
    val live: Boolean get() = state.connection.isLive
    val creating: Boolean get() = creatingState.value
    internal val hover: DragHover? get() = hoverState.value

    internal fun pickFilter(f: EpicFilter) { filterState.value = f }
    fun toggleCreating() { creatingState.value = !creatingState.value }
    fun closeCreating() { creatingState.value = false }
    fun startNext() { scope.launch { controller.startNextTicket() } }
    internal fun drop(t: Ticket, plan: DropPlan) { scope.launch { session.drop(plan, state.ticket(t.id) ?: t) } }
}

/**
 * BLOCKS-LAYOUT L16: the board pane's frame: the image viewer host (IMG-4), the box the drag ghost follows the pointer
 * in, the epics load, the Epic filter, the new-ticket flag and the drop session; [content] goes straight into its
 * column, top to bottom.
 */
@Composable
fun BoardPaneFrame(state: AppState, controller: Controller, onOpenAgent: (String) -> Unit, content: @Composable ColumnScope.(BoardPaneHost) -> Unit) {
    val actionScope = rememberCoroutineScope()
    val creating = remember { mutableStateOf(false) }
    val live = state.connection.isLive
    // M2-14: the Epic filter lives in memory only, and resets when the shell switches project.
    val filterState = remember(state.activeProjectId) { mutableStateOf<EpicFilter>(EpicFilter.All) }
    val filter by filterState
    val epics = boardEpics(state)
    // M2-14: the board loads the project's epics itself, so the filter and the form work without the Epics pane.
    LaunchedEffect(state.activeProjectId, live) {
        state.activeProjectId?.let { if (live) controller.loadEpics(it) }
    }
    // An epic no longer listed falls back to every ticket rather than an empty board.
    val active: EpicFilter = (filter as? EpicFilter.Only)?.takeIf { f -> epics.none { it.id == f.epicId } }?.let { EpicFilter.All } ?: filter
    // The board's view of one column, top first: what a drop's slot is counted in.
    val columnOf: (String) -> List<Ticket> = { status -> state.column(status).filter { active.matches(it) } }
    val drag = remember { BoardDrag() }
    val session = remember(controller) { BoardDropSession(controller.ticketMoves()) }
    val currentColumnOf by rememberUpdatedState(columnOf)
    // Every board change is read for a start's answer, in order, so a follow-up placement is never cancelled midway.
    LaunchedEffect(session) { controller.state.collect { s -> session.observe(s.visibleTickets, currentColumnOf) } }
    val hover = remember(state, active) { derivedStateOf { drag.hover(columnOf) } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val board = BoardPaneHost(state, controller, onOpenAgent, actionScope, filterState, creating, epics, active, columnOf, drag, session, hover)
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
    val state = board.state
    val active = board.active
    val epics = board.epics
    val shown = state.visibleTickets.count { active.matches(it) }
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
            ticketCountLabel(shown, state.visibleTickets.size, active == EpicFilter.All),
            color = Palette.Muted, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { board.startNext() }, enabled = state.connection.isLive && state.column(TicketColumns.TODO).isNotEmpty()) {
            Text("Start next", fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { board.toggleCreating() }, enabled = state.connection.isLive) { Text(if (board.creating) "Close" else "+ New ticket", fontSize = 12.sp) }
    }
}

/** BLOCKS-LAYOUT L16: the "N unassigned tickets not shown" line, with a project selected; [modifier] goes on the line. */
@Composable
fun BoardUnassignedSection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    val state = board.state
    // Tickets with no project are not in a selected project's view; say so rather than look like an empty board.
    val unassigned = if (state.activeProjectId == null) 0 else state.tickets.count { it.projectId == null }
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
        TicketForm(board.state, board.controller, initialEpicId = (board.active as? EpicFilter.Only)?.epicId, onDone = { board.closeCreating() })
    }
}

/** BLOCKS-LAYOUT L16: what an empty backlog says (or that there is no daemon yet); [modifier] goes on its column. */
@Composable
fun BoardEmptySection(board: BoardPaneHost, modifier: Modifier = Modifier) {
    val state = board.state
    if (state.visibleTickets.isEmpty() && !board.creating) {
        Column(modifier.padding(16.dp)) {
            Text(if (state.connection.isLive) "The backlog is empty." else "Connect to a daemon to load its backlog.", color = Palette.Text)
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
    val state = board.state
    val live = board.live
    val drag = board.drag
    val session = board.session
    val columnOf = board.columnOf
    val hover = board.hover
    // MOBILE-LAYOUT-2: a compact window gives each column a fixed width in a row that scrolls sideways; at 600dp and
    // above the columns share the width as before.
    val compact = state.panels.compactWindow
    val columnsScroll = rememberScrollState()
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
                            DragHandle(t, live, drag, cardMoves(t, index, columnOf, board::drop)) {
                                val plan = drag.hover(columnOf)?.plan ?: DropPlan.Stay(t.id)
                                drag.end()
                                board.drop(t, plan)
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
                                onStart = { board.drop(t, DropPlan.Start(t.id, state.column(TicketColumns.IN_PROGRESS).size)) },
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
    var pointer by mutableStateOf(Offset.Zero)
    /** The pointer's offset from the card's top-left when the drag began, so the ghost keeps the grab point. */
    var grab = Offset.Zero
        private set
    val columns = mutableStateMapOf<String, Rect>()
    val cards = mutableStateMapOf<String, Rect>()

    fun begin(t: Ticket, at: Offset) {
        grab = at - (cards[t.id]?.topLeft ?: at)
        pointer = at
        ticket = t
    }

    fun end() { ticket = null }

    /** The column under the pointer, the slot in it and what a drop there means; null = not over a column. */
    fun hover(columnOf: (String) -> List<Ticket>): DragHover? {
        val t = ticket ?: return null
        val column = columns.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return null
        val centers = columnOf(column).filter { it.id != t.id }.map { cards[it.id]?.center?.y ?: 0f }
        val slot = BoardDrop.slotAt(centers, pointer.y)
        return DragHover(column, slot, BoardDrop.plan(columnOf, t, column, slot))
    }
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
                    onDragCancel = { drag.end() },
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
    val state: AppState get() = board.state
    val controller: Controller get() = board.controller
    val live: Boolean get() = board.live
    /** The edit form is open (the summary toggles it). */
    val open: Boolean get() = openState.value
    val dropNote: String? get() = item.dropNote
    internal val handle: @Composable () -> Unit get() = item.handle

    fun toggleOpen() { openState.value = !openState.value }
    fun close() { openState.value = false }
    fun dismissNote() = item.onDismissNote()
    fun start() = item.onStart()
    suspend fun move(status: String): CommandResult = controller.moveTicket(ticket.id, status)
    fun delete() { scope.launch { controller.deleteTicket(ticket.id) } }
    fun openAgent(sessionId: String) = board.onOpenAgent(sessionId)
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
    val state = card.state
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        t.ticketType?.let { Chip(ticketTypeLabel(it)) }
        // M2-1: the assignee's avatar, the same portrait its agent shows in the list and the chat.
        t.agentId?.let { id ->
            val role = assigneeRole(state, id)
            AgentAvatar(role, id, 16, state.role(role)?.color, Modifier.testTag("ticket-avatar-${t.id}"))
        }
        Chip(assigneeLabel(state, t.agentId), if (t.agentId == null) Palette.Muted else Palette.Info)
        // M2-14: the epic the ticket is filed under, by name (its short id until the epics have loaded).
        t.epicId?.takeIf { it.isNotBlank() }?.let { eid ->
            Chip(state.epics.values.flatten().firstOrNull { it.id == eid }?.name ?: eid.takeLast(8), Palette.Ok, Modifier.testTag("ticket-epic-${t.id}"))
        }
        if (t.wontFix) Chip("won't fix", Palette.Danger)
        if (t.queuedFor != null) Chip("queued", Palette.Accent)
        ReportFlagChip(state.reportFlagFor(t), Modifier.testTag("ticket-report-${t.id}"))
    }
}

/** BLOCKS-LAYOUT L16: the hold reason, what the ticket runs on, its branch, the worker's progress and the last run. */
@Composable
fun TicketCardDetailsSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    val state = card.state
    Placed(modifier) {
        t.holdReason?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, maxLines = if (card.open) 6 else 2) }
        // TICKET-EXEC-UI: what the ticket will run on, and where each value comes from (ticket / profile / role / default).
        executorLine(t.effectiveExecutor)?.let {
            Text("runs on $it", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, modifier = Modifier.testTag(TICKET_EXECUTOR_LINE_TAG_PREFIX + t.id))
        }
        // BRANCH-PER-TASK 2/3: read-only; a ticket that never started has no branch line at all.
        t.branchLine()?.let { Text(it, color = Palette.Info, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag("ticket-branch-${t.id}")) }
        // Contract 1.117.0: the running ticket's worker reports how far along its job is.
        if (t.status == TicketColumns.IN_PROGRESS) t.sessionId?.let { state.agents[it] }?.session?.progress?.let { ProgressMeter(it, "ticket-${t.id}") }
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
        if (BoardDrop.isNoRoleNote(note) && t.agentId == null && !card.open) RoleNudge(t, card.state, card.controller)
    }
}

/** BLOCKS-LAYOUT L16: the edit form and the ticket's links, while the card is open. */
@Composable
fun TicketCardEditSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    if (!card.open) return
    Placed(modifier) {
        TicketEditForm(t, card.state, card.controller, onDone = { card.close() })
        if (t.links.isNotEmpty()) Text(t.links.joinToString(", ") { "${it.type.replace('_', ' ')} ${it.ticketId.takeLast(8)}" }, color = Palette.Muted, fontSize = 10.sp)
    }
}

/** BLOCKS-LAYOUT L16: the card's buttons (the column's moves, Agent, and Delete while open); [modifier] goes on the row. */
@Composable
fun TicketCardActionsSection(card: BoardCardHost, modifier: Modifier = Modifier) {
    val t = card.ticket
    val live = card.live
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        when (t.status) {
            // 1.131.0: a backlog card is never started; it is promoted to the sprint first.
            TicketColumns.BACKLOG -> CardButton("To do", live, "Move to To do (the sprint)") { card.move(TicketColumns.TODO) }
            TicketColumns.TODO -> {
                CardButton("Start", live) { card.start(); CommandResult.Sent }
                CardButton("Backlog", live, "Park in Backlog") { card.move(TicketColumns.BACKLOG) }
            }
            TicketColumns.IN_PROGRESS -> {
                CardButton("Done", live) { card.move(TicketColumns.DONE) }
                CardButton("Back", live) { card.move(TicketColumns.TODO) }
            }
            TicketColumns.DONE -> CardButton("Reopen", live) { card.move(TicketColumns.TODO) }
        }
        t.sessionId?.takeIf { it in card.state.agents }?.let { sid ->
            TextButton(onClick = { card.openAgent(sid) }) { Text("Agent", fontSize = 11.sp) }
        }
        Spacer(Modifier.weight(1f))
        if (card.open) TextButton(onClick = { card.delete() }, enabled = live) { Text("Delete", color = Palette.Danger, fontSize = 11.sp) }
    }
}

/** FIX (web, QA F11): "pick a role first" under a card whose Start was refused for want of a role; a pick saves the assignee. */
@Composable
private fun RoleNudge(t: Ticket, state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().testTag(TICKET_ROLE_NUDGE_TAG_PREFIX + t.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Pick a role first, then Start.", color = Palette.Danger, fontSize = 11.sp)
        TicketAssigneePicker(state, null, focus) { id ->
            if (!id.isNullOrEmpty() && state.connection.isLive) actionScope.launch { controller.editTicket(t.id, agentId = id) }
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
) {
    fun landedIn(t: Ticket): Boolean = t.title == title && t.description == description &&
        t.epicId?.takeIf { it.isNotBlank() } == epicId && TicketPins.of(t) == pins && t.images == images
}

/**
 * M2-17: edit a ticket's title, description and Epic (None or one of the active project's epics). Save sends only the
 * changed fields; the form stays open until the daemon's `ticket` answer reads back what was sent, and shows a
 * `ticket_save refused: ...` answer in red instead of closing (the BUG-PROF-1 ProfileForm pattern).
 */
@Composable
private fun TicketEditForm(t: Ticket, state: AppState, controller: Controller, onDone: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    val currentEpic = t.epicId?.takeIf { it.isNotBlank() }
    var title by remember(t.id) { mutableStateOf(t.title) }
    var description by remember(t.id) { mutableStateOf(t.description) }
    var epicId by remember(t.id) { mutableStateOf(currentEpic) }
    // TICKET-EXEC-UI: the assignee ("" = unassigned) and the provider / model pins (null = inherit).
    val currentPins = TicketPins.of(t)
    var assignee by remember(t.id) { mutableStateOf(currentPins.agentId.orEmpty()) }
    var provider by remember(t.id) { mutableStateOf(currentPins.provider) }
    var model by remember(t.id) { mutableStateOf(currentPins.model) }
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
                    when (val r = controller.uploadTicketImage(t.projectId, image.fileName, image.mimeType, image.bytes)) {
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
    LaunchedEffect(pending, state.ticketError, t) {
        val sent = pending ?: return@LaunchedEffect
        val error = state.ticketError
        if (error != null) {
            refusal = error
            pending = null
        } else if (sent.landedIn(t)) {
            pending = null
            onDone()
        }
    }
    val epics = boardEpics(state)
    // An epic outside the active project (or not loaded yet) still reads by name, else by its short id.
    val epicName = epicId?.let { id -> state.epics.values.flatten().firstOrNull { it.id == id }?.name ?: id.takeLast(8) } ?: "None"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
            // IMG-4: Ctrl/Cmd+V anywhere in the form attaches a clipboard image; text on the clipboard pastes as usual.
            .onPreviewKeyEvent { e ->
                val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                val image = if (paste && state.connection.isLive && pending == null && !uploading) host.clipboardImage() else null
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
        TicketAssigneePicker(state, assignee) { assignee = it.orEmpty() }
        TicketExecutorField(state, t.id, provider, model) { p, m -> provider = p; model = m }
        TicketImagesSection(
            images = images,
            projectDirectory = state.projects.firstOrNull { it.id == t.projectId }?.directory,
            uploaded = uploaded,
            enabled = state.connection.isLive && pending == null,
            busy = uploading,
            error = imageError,
            onAttach = ::attach,
            onRemove = { relPath -> images = images - relPath },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = state.connection.isLive && title.isNotBlank() && pending == null && !uploading,
                modifier = Modifier.testTag(TICKET_EDIT_SAVE_TAG),
                onClick = {
                    actionScope.launch {
                        val pins = TicketPins(assignee.ifEmpty { null }, provider, model)
                        val edit = TicketEdit(title.trim(), description.trim(), epicId, pins, images)
                        if (edit.landedIn(t)) return@launch onDone() // nothing changed: nothing to send
                        refusal = null
                        // TICKET-EXEC-UI: only the pins that changed; "" clears one (unassigned / inherit).
                        val pinEdit = ticketPinEdit(currentPins, pins)
                        val r = controller.editTicket(
                            t.id,
                            title = edit.title.takeIf { it != t.title },
                            description = edit.description.takeIf { it != t.description },
                            // "" files the ticket under no epic (contract 1.60.0).
                            epicId = if (edit.epicId != currentEpic) edit.epicId ?: "" else null,
                            agentId = pinEdit.agentId,
                            provider = pinEdit.provider,
                            model = pinEdit.model,
                            images = images.takeIf { it != t.images },
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
private fun TicketForm(state: AppState, controller: Controller, initialEpicId: String?, onDone: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // null = not picked yet (the first role lane, see below), "" = unassigned, else a profile id or role:<role>
    var picked by remember { mutableStateOf<String?>(null) }
    val assignee = picked ?: state.visibleProfiles.firstOrNull()?.let { "role:${it.role}" } ?: ""
    // M2-14: null = no epic; starts on the board's filtered epic, if any.
    var epicId by remember { mutableStateOf(initialEpicId) }
    // TICKET-EXEC-UI: null = inherit.
    var provider by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    val epics = boardEpics(state)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Description (the agent's task)") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
        TicketAssigneePicker(state, assignee) { picked = it.orEmpty() }
        TicketExecutorField(state, "new-ticket", provider, model) { p, m -> provider = p; model = m }
        LabelledDropdown(
            label = "Epic",
            selected = epics.firstOrNull { it.id == epicId }?.name ?: "None",
            options = listOf("none" to "None") + epics.map { it.id to it.name },
            anchorTag = TICKET_EPIC_PICKER_TAG,
            optionTagPrefix = TICKET_EPIC_OPTION_TAG_PREFIX,
        ) { key -> epicId = key.takeUnless { it == "none" } }
        if (state.visibleProfiles.isEmpty()) Text("No agent profile exists yet: create one in the Profiles tab so a started ticket has someone to run it.", color = Palette.Muted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = state.connection.isLive && title.isNotBlank(),
                onClick = {
                    actionScope.launch {
                        if (controller.createTicket(title, description, null, assignee, epicId?.takeIf { id -> epics.any { it.id == id } }, provider, model) is CommandResult.Sent) onDone()
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
