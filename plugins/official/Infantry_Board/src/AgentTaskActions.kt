// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** AGENT-TASKS-6: a board-plugin tool call: its name and arguments, exactly what the card menu sends through `pluginTool`. */
data class AgentTaskCall(val tool: String, val args: JsonObject)

/** AGENT-TASKS-6: the menu choice -> request mapping (pure, so a test pins it). The card then updates from the board frame. */
object AgentTaskRequests {
    const val UNASSIGN = "infantry_ticket_unassign"
    const val ASSIGN = "infantry_ticket_assign"
    const val SWITCH = "infantry_agent_switch"

    fun unassign(ticketId: String) = AgentTaskCall(UNASSIGN, buildJsonObject { put("ticketId", ticketId) })
    fun assignNow(ticketId: String, agentId: String) = AgentTaskCall(ASSIGN, buildJsonObject { put("ticketId", ticketId); put("agentId", agentId) })
    fun assignQueue(ticketId: String, agentId: String) =
        AgentTaskCall(ASSIGN, buildJsonObject { put("ticketId", ticketId); put("agentId", agentId); put("queue", true) })
    /** [agentId] is the in-progress ticket's agent; [ticketId] the todo ticket it moves to. */
    fun switchTo(agentId: String, ticketId: String) = AgentTaskCall(SWITCH, buildJsonObject { put("agentId", agentId); put("ticketId", ticketId) })

    /** `has work` badge text: tip sha (7) and "+N commits"; null when the ticket carries no kept work. */
    fun hasWorkLabel(t: Ticket): String? = t.hasWork?.let { "has work ${it.tip.take(7)} +${it.ahead} commit${if (it.ahead == 1) "" else "s"}" }

    /** `queued: <agent> #n` badge text; null when the ticket is not in an agent's queue. */
    fun queuedLabel(t: Ticket): String? = t.queuedAgent?.let { "queued: ${t.queuedAgentName ?: it.takeLast(8)}${t.queueRank?.let { r -> " #$r" }.orEmpty()}" }
}

const val AGENT_MENU_TAG_PREFIX = "ticket-agent-menu-"

/** AGENT-TASKS-6: the has-work and queued badges of a todo card; nothing for a null field. */
@Composable
fun AgentTaskBadges(t: Ticket) {
    AgentTaskRequests.hasWorkLabel(t)?.let { Chip(it, Palette.Ok, Modifier.testTag("ticket-haswork-${t.id}")) }
    AgentTaskRequests.queuedLabel(t)?.let { Chip(it, Palette.Accent, Modifier.testTag("ticket-queued-${t.id}")) }
}

/**
 * AGENT-TASKS-6: one overflow menu on the card (phone width: a menu, not more buttons). In progress: Unassign (confirm) and
 * Switch to...; to do: Assign to... (Start now / Add to queue). Every pick calls the board tool; no local state guess.
 * EPIC-MOVE-1: every card's menu also has Move to epic... (the board's epic move dialog), and each item wears an icon.
 */
@Composable
fun AgentTaskMenu(card: BoardCardHost) {
    val t = card.ticket
    val deps = card.deps
    val canMove = card.can("board.ticket.move")
    val canSave = card.can("board.ticket.save")
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<String?>(null) } // unassign | switch | assign
    var picked by remember { mutableStateOf<String?>(null) } // the agent chosen for assign
    var error by remember { mutableStateOf<String?>(null) }
    val inProgress = t.status == TicketColumns.IN_PROGRESS
    val todo = t.status == TicketColumns.TODO
    val agentItems = canMove && (inProgress || todo)
    fun run(c: AgentTaskCall) {
        step = null; picked = null; menu = false
        scope.launch { deps.tickets.pluginTool(c.tool, c.args).onFailure { error = it.message } }
    }
    Box {
        TextButton(onClick = { menu = true }, enabled = agentItems || canSave, modifier = Modifier.testTag(AGENT_MENU_TAG_PREFIX + t.id)) { Text("More", fontSize = 11.sp) }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (agentItems && inProgress) {
                DropdownMenuItem(text = { BoardMenuLabel(AhqIcons.Cancel, "Unassign") }, onClick = { menu = false; step = "unassign" })
                if (t.agentId != null) DropdownMenuItem(text = { BoardMenuLabel(AhqIcons.PlayArrow, "Switch to...") }, onClick = { menu = false; step = "switch" })
            } else if (agentItems) {
                DropdownMenuItem(text = { BoardMenuLabel(AhqIcons.PersonAdd, "Assign to...") }, onClick = { menu = false; step = "assign" })
            }
            if (canSave) DropdownMenuItem(
                text = { BoardMenuLabel(AhqIcons.SwapHoriz, "Move to epic...") },
                onClick = { menu = false; step = "epic" },
                modifier = Modifier.testTag(TICKET_MENU_MOVE_EPIC_TAG_PREFIX + t.id),
            )
        }
    }
    val close = { step = null; picked = null }
    when (step) {
        "unassign" -> AlertDialog(
            onDismissRequest = close, title = { Text("Unassign ticket?") },
            text = { Text("The ticket goes back to To do. Its branch and commits are kept, and the next worker continues from them.") },
            confirmButton = { TextButton(onClick = { run(AgentTaskRequests.unassign(t.id)) }) { Text("Unassign") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        "switch" -> {
            val agent = t.agentId
            val todos = deps.tickets.column(TicketColumns.TODO).filter { it.projectId == t.projectId }
            AlertDialog(
                onDismissRequest = close, title = { Text("Switch to which ticket?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (todos.isEmpty()) Text("No To do tickets.", fontSize = 12.sp)
                    todos.forEach { o -> TextButton(onClick = { if (agent != null) run(AgentTaskRequests.switchTo(agent, o.id)) }) { Text(o.title, maxLines = 2, fontSize = 12.sp) } }
                } },
                confirmButton = {}, dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
        "assign" -> AlertDialog(
            onDismissRequest = close, title = { Text(if (picked == null) "Assign to which agent?" else "Assign to ${deps.roles.profiles.firstOrNull { it.id == picked }?.name ?: "agent"}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val p = picked
                if (p == null) {
                    val agents = deps.roles.profiles.filter { it.id != null }
                    if (agents.isEmpty()) Text("No agents in this project.", fontSize = 12.sp)
                    agents.forEach { a -> TextButton(onClick = { picked = a.id }) { Text(a.name, fontSize = 12.sp) } }
                } else {
                    TextButton(onClick = { run(AgentTaskRequests.assignNow(t.id, p)) }) { Text("Start now") }
                    TextButton(onClick = { run(AgentTaskRequests.assignQueue(t.id, p)) }) { Text("Add to queue") }
                }
            } },
            confirmButton = {}, dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        "epic" -> EpicMoveDialog(deps, listOf(t.id), card.epics, onClose = close)
    }
    error?.let { msg -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Refused") }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}
