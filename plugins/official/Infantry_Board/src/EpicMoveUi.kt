// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.model.EpicView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** EPIC-MOVE-1: the request a "Move to epic" sends: Infantry_Board's `infantry_ticket_epic` tool (pure, so a test pins it). */
object EpicMoveRequests {
    const val TOOL = "infantry_ticket_epic"
    const val NONE = "none"

    /** [epic] is an epic id, a (new) epic name, or [NONE]. */
    fun move(ticketIds: List<String>, epic: String, force: Boolean = false) = AgentTaskCall(TOOL, buildJsonObject {
        put("tickets", JsonArray(ticketIds.map { JsonPrimitive(it) }))
        put("epic", epic)
        if (force) put("force", true)
    })

    /** The tool's `message` line (its answer is JSON); the raw text when it is not. */
    fun message(text: String): String =
        runCatching { (Json.parseToJsonElement(text) as JsonObject)["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: text
}

const val TICKET_SELECT_TAG_PREFIX = "ticket-select-"
const val BOARD_SELECTION_BAR_TAG = "board-selection-bar"
const val BOARD_MOVE_EPIC_TAG = "board-move-epic"
const val BOARD_SELECTION_CLEAR_TAG = "board-selection-clear"
const val EPIC_MOVE_DIALOG_TAG = "epic-move-dialog"
const val EPIC_MOVE_OPTION_TAG_PREFIX = "epic-move-option-"
const val EPIC_MOVE_NEW_NAME_TAG = "epic-move-new-name"
const val EPIC_MOVE_CONFIRM_TAG = "epic-move-confirm"
const val EPIC_MOVE_RESULT_TAG = "epic-move-result"
const val TICKET_MENU_MOVE_EPIC_TAG_PREFIX = "ticket-menu-move-epic-"

/** EPIC-MOVE-1: the board's multi-select, in memory only (it resets with the project). Ids no longer on the board drop out. */
class BoardSelection {
    val ids = mutableStateListOf<String>()
    fun has(id: String) = id in ids
    fun toggle(id: String) { if (id in ids) ids.remove(id) else ids.add(id) }
    fun clear() = ids.clear()
    fun keepOnly(present: Set<String>) { ids.retainAll(present) }
}

/** A menu row in the shared shape: leading icon, label, tint (the agent card menu's style). */
@Composable
internal fun BoardMenuLabel(icon: ImageVector, label: String, tint: Color = Palette.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** A card's small select box (a 16dp square, accent with a check when selected). */
@Composable
internal fun TicketSelectBox(selected: Boolean, title: String, ticketId: String, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.padding(top = 3.dp).size(16.dp).clip(shape)
            .background(if (selected) Palette.Accent else Color.Transparent)
            .border(1.dp, if (selected) Palette.Accent else Palette.Muted, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .handCursor(true)
            .semantics { contentDescription = "Select $title" }
            .testTag(TICKET_SELECT_TAG_PREFIX + ticketId),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(AhqIcons.Check, contentDescription = null, tint = Palette.Bg, modifier = Modifier.size(12.dp))
    }
}

/** The bar a non-empty selection shows under the filters: the count, Move to epic..., Clear. */
@Composable
internal fun BoardSelectionBar(board: BoardPaneHost) {
    val selection = board.selection
    // Tickets that left the board (deleted, archived, another project) leave the selection.
    val present = board.deps.tickets.visible.map { it.id }.toSet()
    LaunchedEffect(present) { selection.keepOnly(present) }
    // The ids the open dialog moves (a snapshot): the dialog outlives the selection it clears, to show its answer.
    var moving by remember { mutableStateOf<List<String>?>(null) }
    moving?.let { ids -> EpicMoveDialog(board.deps, ids, board.epics, onMoved = { selection.clear() }) { moving = null } }
    if (selection.ids.isEmpty()) return
    val canSave = board.can("board.ticket.save")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
            .padding(horizontal = 8.dp, vertical = 2.dp).testTag(BOARD_SELECTION_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${selection.ids.size} selected", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { moving = selection.ids.toList() }, enabled = canSave, modifier = Modifier.testTag(BOARD_MOVE_EPIC_TAG)) {
            BoardMenuLabel(AhqIcons.SwapHoriz, "Move to epic...", if (canSave) Palette.Text else Palette.Border)
        }
        TextButton(onClick = { selection.clear() }, modifier = Modifier.testTag(BOARD_SELECTION_CLEAR_TAG)) { Text("Clear", fontSize = 12.sp) }
    }
}

/**
 * EPIC-MOVE-1: pick an existing epic, No epic, or type a new name, then Move. One `infantry_ticket_epic` call; the dialog shows
 * its answer (moved / not moved and why) until closed, and an in-progress refusal offers Move anyway (force). The cards and the
 * Epics tab update from the board's own ticket frames; the epic list is re-read so a new epic shows at once.
 */
@Composable
internal fun EpicMoveDialog(deps: BoardDeps, ticketIds: List<String>, epics: List<EpicView>, onMoved: () -> Unit = {}, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<String?>(null) } // epic id, NONE, or null = the new name
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val target = choice ?: newName.trim()
    fun send(force: Boolean) {
        busy = true
        scope.launch {
            val c = EpicMoveRequests.move(ticketIds, target, force)
            deps.tickets.pluginTool(c.tool, c.args)
                .onSuccess { text -> result = EpicMoveRequests.message(text); failed = false; onMoved() }
                .onFailure { result = it.message ?: "refused"; failed = true }
            deps.session.activeProjectId?.let { deps.tickets.loadEpics(it) }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(EPIC_MOVE_DIALOG_TAG),
        title = { Text(if (ticketIds.size == 1) "Move ticket to epic" else "Move ${ticketIds.size} tickets to epic") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val r = result
                if (r != null) {
                    Text(r, color = if (failed) Palette.Danger else Palette.Text, fontSize = 12.sp, modifier = Modifier.testTag(EPIC_MOVE_RESULT_TAG))
                    return@Column
                }
                epics.forEach { e -> EpicOption(e.name, choice == e.id, e.id) { choice = e.id } }
                EpicOption("No epic", choice == EpicMoveRequests.NONE, EpicMoveRequests.NONE) { choice = EpicMoveRequests.NONE }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it; choice = null }, singleLine = true,
                    label = { Text("New epic name", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().testTag(EPIC_MOVE_NEW_NAME_TAG),
                )
            }
        },
        confirmButton = {
            val r = result
            when {
                r == null -> TextButton(onClick = { send(false) }, enabled = !busy && target.isNotEmpty(), modifier = Modifier.testTag(EPIC_MOVE_CONFIRM_TAG)) { Text("Move") }
                "Not moved" in r -> TextButton(onClick = { result = null; send(true) }, enabled = !busy) { Text("Move anyway") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (result == null) "Cancel" else "Close") } },
    )
}

@Composable
private fun EpicOption(label: String, selected: Boolean, key: String, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (selected) Palette.Raised else Color.Transparent)
            .clickable(role = Role.RadioButton, onClickLabel = "Move to $label") { onPick() }
            .padding(horizontal = 6.dp, vertical = 4.dp).testTag(EPIC_MOVE_OPTION_TAG_PREFIX + key),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.Text, fontSize = 12.sp)
    }
}
