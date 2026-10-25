package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.EpicView
import com.superiorsoftllc.agenthq.ui.model.epicTicketColumns
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlinx.coroutines.launch

/** Column headers, keyed by [TicketColumns]. */
private val COLUMN_LABELS = mapOf(
    TicketColumns.BACKLOG to "Backlog", TicketColumns.TODO to "To do", TicketColumns.IN_PROGRESS to "In progress",
    TicketColumns.PENDING to "Pending", TicketColumns.DONE to "Done",
)

/** The columns a client may move a ticket to: `pending` is derived by the daemon, never set; a backlog ticket never goes straight to In progress. */
private val MOVE_TARGETS = listOf(TicketColumns.BACKLOG, TicketColumns.TODO, TicketColumns.IN_PROGRESS, TicketColumns.DONE)

const val EPICS_ADD_BUTTON_TAG = "epics-add"
const val EPIC_DIALOG_TAG = "epic-dialog"
const val EPIC_NAME_TAG = "epic-name-input"
const val EPIC_GOAL_TAG = "epic-goal-input"
const val EPIC_SAVE_TAG = "epic-save"
const val EPIC_CANCEL_TAG = "epic-cancel"

/** FORM-MODAL-1: what the new-epic modal holds. Create only: `epic_save` carries no id, so the daemon cannot edit. */
private data class EpicDraft(val name: String = "", val goal: String = "")

/**
 * Epics of the selected project and their work (contract 1.19.0, E1-d one work model). Selecting a project (or
 * going live) asks the daemon for its epics and its tickets; each epic card lists the tickets carrying its id in the
 * board's four columns, with a move button per other column (`ticket_move`). "Add task" files a new ticket under the
 * epic (`ticket_save{projectId, epicId}`). FORM-MODAL-1: a new epic is made in the shared [EditorModal], opened by an
 * icon-only "+" in the header, never in a field under the growing list; Save sends `epic_save` and closes on Sent.
 * Everything shown comes from [state].
 */
@Composable
fun EpicsPane(state: AppState, controller: Controller) {
    val scope = rememberCoroutineScope()
    val live = state.connection.isLive
    val active = state.activeProject
    var draft by remember(active?.id) { mutableStateOf<EpicDraft?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active?.id, live) {
        if (live && active != null) controller.loadEpics(active.id)
    }

    fun run(onSent: () -> Unit = {}, request: suspend () -> CommandResult) {
        localError = null
        scope.launch {
            when (val r = request()) {
                is CommandResult.Sent -> onSent()
                is CommandResult.Refused -> localError = r.reason
                // Only prompts queue; these requests never answer Queued.
                is CommandResult.Queued -> Unit
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (active != null) "EPICS · ${active.name}" else "EPICS", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (active != null) {
                    IconAction(
                        AhqIcons.Add, "New epic", onClick = { localError = null; draft = EpicDraft() }, enabled = live,
                        style = IconActionStyle.TEXT, modifier = Modifier.testTag(EPICS_ADD_BUTTON_TAG),
                    )
                }
            }
            if (active == null) {
                Text("Select a project to see its epics.", color = Palette.Muted, fontSize = 12.sp)
                return@Column
            }
            // While the modal is open its refusals show inside it; otherwise (a task or move refused) here.
            if (draft == null) localError?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            val epics = state.epics[active.id].orEmpty()
            if (epics.isEmpty()) Text("No epics yet.", color = Palette.Muted, fontSize = 12.sp)
            epics.forEach { epic ->
                EpicCard(epic, state.epicTicketColumns(epic.id), live,
                    onNewTask = { title, done -> run(onSent = done) { controller.addEpicTask(active.id, title, epic.id) } },
                    onMove = { id, status -> run { controller.moveTicket(id, status) } },
                )
            }
        }

        val open = draft
        if (active != null && open != null) {
            EditorModal(
                title = "New epic",
                onSave = { run(onSent = { draft = null }) { controller.saveEpic(active.id, open.name, open.goal) } },
                onCancel = { draft = null; localError = null },
                modifier = Modifier.testTag(EPIC_DIALOG_TAG),
                saveEnabled = live && open.name.isNotBlank(), error = localError,
                saveLabel = "Create epic", saveTag = EPIC_SAVE_TAG, cancelTag = EPIC_CANCEL_TAG,
            ) {
                OutlinedTextField(
                    open.name, { draft = open.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = live,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(EPIC_NAME_TAG),
                )
                OutlinedTextField(
                    open.goal, { draft = open.copy(goal = it) }, label = { Text("Goal (optional)") }, minLines = 2, maxLines = 6, enabled = live,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(EPIC_GOAL_TAG),
                )
            }
        }
    }
}

@Composable
private fun EpicCard(
    epic: EpicView,
    columns: Map<String, List<Ticket>>,
    live: Boolean,
    onNewTask: (title: String, onSent: () -> Unit) -> Unit,
    onMove: (id: String, status: String) -> Unit,
) {
    var title by remember(epic.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp),
    ) {
        Text(epic.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (epic.goal.isNotBlank()) Text(epic.goal, color = Palette.Muted, fontSize = 11.sp)
        if (columns.values.all { it.isEmpty() }) Text("No tasks yet.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TicketColumns.ALL.forEach { column ->
                val tickets = columns[column].orEmpty()
                Column(Modifier.weight(1f).testTag("epic-column-${epic.id}-$column")) {
                    Text("${COLUMN_LABELS[column] ?: column} · ${tickets.size}", color = Palette.Muted, fontSize = 10.sp)
                    tickets.forEach { t -> EpicTicketRow(t, live, onMove) }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                title, { title = it }, label = { Text("New task") }, singleLine = true, enabled = live,
                textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f).testTag("new-task-${epic.id}"),
            )
            OutlinedButton(onClick = { onNewTask(title) { title = "" } }, enabled = live && title.isNotBlank(), modifier = Modifier.testTag("create-task-${epic.id}")) {
                Text("Add task", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EpicTicketRow(t: Ticket, live: Boolean, onMove: (id: String, status: String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Bg).padding(6.dp).testTag("epic-ticket-${t.id}"),
    ) {
        Text(t.title, color = Palette.Text, fontSize = 12.sp, maxLines = 2)
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MOVE_TARGETS.filter { it != t.status && !(t.status == TicketColumns.BACKLOG && it == TicketColumns.IN_PROGRESS) }.forEach { target ->
                OutlinedButton(onClick = { onMove(t.id, target) }, enabled = live, modifier = Modifier.testTag("move-${t.id}-$target")) {
                    Text("-> ${COLUMN_LABELS[target] ?: target}", fontSize = 10.sp)
                }
            }
        }
    }
}
