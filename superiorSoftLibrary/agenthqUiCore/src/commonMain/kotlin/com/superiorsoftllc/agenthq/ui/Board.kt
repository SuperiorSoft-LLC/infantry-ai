package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import com.superiorsoftllc.agenthq.ui.model.TicketReorder
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
internal fun assigneeLabel(state: AppState, agentId: String?): String = when {
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
    TicketColumns.TODO -> "To do" to Palette.Info
    TicketColumns.IN_PROGRESS -> "In progress" to Palette.Accent
    TicketColumns.PENDING -> "Pending" to Palette.Muted
    else -> "Done" to Palette.Ok
}

/**
 * The scrum board (contracts 1.5.0 / 1.6.0): the daemon's backlog in its four columns. The daemon owns the
 * columns, `pending` and the hand-out; this screen only renders them and sends the requests a click means
 * (create, start, finish, reopen, delete, start next).
 */
@Composable
fun BoardPane(state: AppState, controller: Controller, onOpenAgent: (String) -> Unit) {
    val actionScope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val live = state.connection.isLive
    // M2-14: the Epic filter lives in memory only, and resets when the shell switches project.
    var filter by remember(state.activeProjectId) { mutableStateOf<EpicFilter>(EpicFilter.All) }
    val epics = boardEpics(state)
    // M2-14: the board loads the project's epics itself, so the filter and the form work without the Epics pane.
    LaunchedEffect(state.activeProjectId, live) {
        state.activeProjectId?.let { if (live) controller.loadEpics(it) }
    }
    // An epic no longer listed falls back to every ticket rather than an empty board.
    val active: EpicFilter = (filter as? EpicFilter.Only)?.takeIf { f -> epics.none { it.id == f.epicId } }?.let { EpicFilter.All } ?: filter
    val shown = state.visibleTickets.count { active.matches(it) }
    // IMG-4: a ticket's thumbnail opens full size over the whole board.
    ImageViewerHost {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            ) { key -> filter = when (key) { "all" -> EpicFilter.All; "none" -> EpicFilter.NoEpic; else -> EpicFilter.Only(key) } }
            Spacer(Modifier.width(8.dp))
            Text(
                if (active == EpicFilter.All) "${state.visibleTickets.size} tickets" else "$shown of ${state.visibleTickets.size} tickets",
                color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { actionScope.launch { controller.startNextTicket() } }, enabled = state.connection.isLive && state.column(TicketColumns.TODO).isNotEmpty()) {
                Text("Start next", fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { creating = !creating }, enabled = state.connection.isLive) { Text(if (creating) "Close" else "+ New ticket", fontSize = 12.sp) }
        }
        if (creating) TicketForm(state, controller, initialEpicId = (active as? EpicFilter.Only)?.epicId, onDone = { creating = false })
        if (state.visibleTickets.isEmpty() && !creating) {
            Column(Modifier.padding(16.dp)) {
                Text(if (state.connection.isLive) "The backlog is empty." else "Connect to a daemon to load its backlog.", color = Palette.Text)
                Text("Create a ticket, give it an assignee (a profile or a role), then start it to hand it to that agent.", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        // MOBILE-LAYOUT-2: a compact window gives each column a fixed width in a row that scrolls sideways; at 600dp and
        // above the columns share the width as before.
        val compact = state.panels.compactWindow
        val columnsScroll = rememberScrollState()
        Row(
            Modifier.weight(1f).fillMaxWidth().then(if (compact) Modifier.horizontalScroll(columnsScroll) else Modifier).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TicketColumns.ALL.forEach { status ->
                val tickets = state.column(status).filter { active.matches(it) }
                val (title, color) = columnTitle(status)
                Column((if (compact) Modifier.width(BoardCompactColumnWidth) else Modifier.weight(1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Palette.Panel)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Chip("${tickets.size}", color)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReorderableCards(tickets, live, controller) { t, handle -> TicketCard(t, state, controller, onOpenAgent, handle) }
                    }
                }
            }
        }
    }
    }
}

/** PIPELINE-WATCHDOG: the watchdog flag; urgent states in the attention colour. */
@Composable
internal fun WatchFlagChip(flag: WatchFlag?, modifier: Modifier = Modifier) {
    if (flag != null) Chip(flag.label, if (flag.urgent) Palette.Accent else Palette.Info, modifier)
}

/** TRAITS-T9: the REPORT BACK flag; `unreported` in the attention colour, `reported` muted, nothing otherwise. */
@Composable
internal fun ReportFlagChip(flag: ReportFlag?, modifier: Modifier = Modifier) {
    when (flag) {
        ReportFlag.UNREPORTED -> Chip(flag.label, Palette.Accent, modifier)
        ReportFlag.REPORTED -> Chip(flag.label, Palette.Muted, modifier)
        null -> Unit
    }
}

@Composable
private fun TicketCard(t: Ticket, state: AppState, controller: Controller, onOpenAgent: (String) -> Unit, reorder: ReorderHandle) {
    val actionScope = rememberCoroutineScope()
    var open by remember(t.id) { mutableStateOf(false) }
    val live = state.connection.isLive
    Column(
        reorder.modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      // M2-17: the summary is what opens (and closes) the card's edit form; the form itself is not a click target.
      Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) "Close the ticket" else "Edit the ticket") { open = !open }
            .testTag(TICKET_CARD_TAG_PREFIX + t.id),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(t.title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = if (open) 6 else 2)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        t.holdReason?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, maxLines = if (open) 6 else 2) }
        // TICKET-EXEC-UI: what the ticket will run on, and where each value comes from (ticket / profile / role / default).
        executorLine(t.effectiveExecutor)?.let {
            Text("runs on $it", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, modifier = Modifier.testTag(TICKET_EXECUTOR_LINE_TAG_PREFIX + t.id))
        }
        // BRANCH-PER-TASK 2/3: read-only; a ticket that never started has no branch line at all.
        t.branchLine()?.let { Text(it, color = Palette.Info, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag("ticket-branch-${t.id}")) }
        t.lastRun?.let { run ->
            val outcome = run.outcome ?: "running"
            Text("last run: ${outcome.lowercase().replace('_', ' ')}", color = if (run.outcome == null || run.outcome == "SUCCEEDED") Palette.Muted else Palette.Danger, fontSize = 10.sp)
        }
      }
        if (open) {
            TicketEditForm(t, state, controller, onDone = { open = false })
            if (t.links.isNotEmpty()) Text(t.links.joinToString(", ") { "${it.type.replace('_', ' ')} ${it.ticketId.takeLast(8)}" }, color = Palette.Muted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            when (t.status) {
                TicketColumns.TODO -> CardButton("Start", live) { controller.moveTicket(t.id, TicketColumns.IN_PROGRESS) }
                TicketColumns.IN_PROGRESS -> {
                    CardButton("Done", live) { controller.moveTicket(t.id, TicketColumns.DONE) }
                    CardButton("Back", live) { controller.moveTicket(t.id, TicketColumns.TODO) }
                }
                TicketColumns.DONE -> CardButton("Reopen", live) { controller.moveTicket(t.id, TicketColumns.TODO) }
            }
            t.sessionId?.takeIf { it in state.agents }?.let { sid ->
                TextButton(onClick = { onOpenAgent(sid) }) { Text("Agent", fontSize = 11.sp) }
            }
            Spacer(Modifier.weight(1f))
            // Keyboard / screen-reader route to the same move the drag makes.
            if (reorder.canMoveUp) CardButton("▲", live, "Move ${t.title} up") { reorder.move(-1) }
            if (reorder.canMoveDown) CardButton("▼", live, "Move ${t.title} down") { reorder.move(1) }
            if (open) TextButton(onClick = { actionScope.launch { controller.deleteTicket(t.id) } }, enabled = live) { Text("Delete", color = Palette.Danger, fontSize = 11.sp) }
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

/** What one card needs to take part in its column's reordering. */
internal class ReorderHandle(
    val modifier: Modifier,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val move: suspend (Int) -> CommandResult,
)

/**
 * One board column's cards, top first, reorderable by long-press and drag (touch and mouse alike; a plain click still
 * opens the card). A drop sends one `ticket_move` position; the card snaps back until the daemon's answer reorders
 * the list, so the daemon stays the only owner of the order.
 */
@Composable
private fun ReorderableCards(tickets: List<Ticket>, live: Boolean, controller: Controller, card: @Composable (Ticket, ReorderHandle) -> Unit) {
    val scope = rememberCoroutineScope()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0f) }
    val tops = remember { mutableStateMapOf<String, Float>() }
    val heights = remember { mutableStateMapOf<String, Float>() }
    suspend fun send(id: String, slot: Int): CommandResult {
        val position = TicketReorder.positionFor(tickets, id, slot) ?: return CommandResult.Sent
        return controller.reorderTicket(id, position)
    }
    tickets.forEachIndexed { index, t ->
        val dragging = draggedId == t.id
        val handle = ReorderHandle(
            modifier = Modifier
                .onGloballyPositioned { tops[t.id] = it.positionInParent().y; heights[t.id] = it.size.height.toFloat() }
                .then(if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = offset; alpha = 0.85f } else Modifier)
                .pointerInput(t.id, live) {
                    if (!live) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggedId = t.id; offset = 0f },
                        onDrag = { change, amount -> change.consume(); offset += amount.y },
                        onDragEnd = {
                            val centers = tickets.map { (tops[it.id] ?: 0f) + (heights[it.id] ?: 0f) / 2 }
                            val slot = TicketReorder.slotFor(centers, index, centers[index] + offset)
                            draggedId = null
                            offset = 0f
                            scope.launch { send(t.id, slot) }
                        },
                        onDragCancel = { draggedId = null; offset = 0f },
                    )
                },
            canMoveUp = index > 0,
            canMoveDown = index < tickets.size - 1,
            move = { delta -> send(t.id, index + delta) },
        )
        card(t, handle)
    }
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
