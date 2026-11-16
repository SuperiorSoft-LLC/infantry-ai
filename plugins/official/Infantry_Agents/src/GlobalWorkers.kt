// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.clickable
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState

/**
 * WORKERS-GLOBAL-1: the experimental "who is working, in every project" view behind the sidebar's Working counter.
 * Off by default ([GLOBAL_WORKERS_KEY] on node [GLOBAL_WORKERS_NODE]); with it off the counter is the plain label it was.
 */
const val GLOBAL_WORKERS_NODE = "infantry.agents"
const val GLOBAL_WORKERS_KEY = "ui.experimental.globalWorkers"

const val GLOBAL_WORKERS_BUTTON_TAG = "global-workers-button"
const val GLOBAL_WORKERS_DIALOG_TAG = "global-workers-dialog"
const val GLOBAL_WORKERS_EMPTY_TAG = "global-workers-empty"
const val GLOBAL_WORKERS_LANE_TAG_PREFIX = "global-workers-lane-"
const val GLOBAL_WORKERS_CHAT_TAG = "global-workers-chat"
const val GLOBAL_WORKERS_OPEN_SESSION_TAG = "global-workers-open-session"
const val GLOBAL_WORKERS_TRANSCRIPT_TAG = "global-workers-transcript"

/** The lane of sessions that belong to no project (the Assistant, an off-board spawn). */
const val GLOBAL_WORKERS_CLOSE_TAG = "global-workers-close"
const val GLOBAL_WORKERS_EMPTY_TEXT = "No agents are working right now"
const val NO_PROJECT_LANE = "No project"

/** One lane of the modal: a project (null = none) and its working sessions, newest first. */
data class WorkerLane(val projectId: String?, val name: String, val agents: List<AgentView>)

/**
 * The working sessions of [state] (the same set the Working counter counts), one lane per project: the active
 * project first, then by name; sessions without a project last. A project the client does not know shows its id.
 */
fun workerLanes(state: AppState): List<WorkerLane> {
    val names = state.projects.associate { it.id to it.name }
    return state.workingAgents.groupBy { it.session.projectId }
        .map { (pid, rows) -> WorkerLane(pid, pid?.let { names[it] ?: it } ?: NO_PROJECT_LANE, rows) }
        .sortedWith(compareBy<WorkerLane>({ it.projectId == null }, { it.projectId != state.activeProjectId }, { it.name.lowercase() }))
}

/** The Working counter as a button (flag on): the same look as the other counters, a click opens the modal. */
@Composable
internal fun WorkingCounterButton(n: Int, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = "Show working agents in every project", onClick = onClick)
            .testTag(GLOBAL_WORKERS_BUTTON_TAG),
    ) { Counter("Working", n, Palette.Ok) }
}

/**
 * The lanes modal: one bordered lane per project, each holding the left panel's own [AgentRow] cards. It reads [state]
 * live, so lanes appear and empty as workers start and stop. A card click calls [onOpenChat].
 */
@Composable
fun GlobalWorkersDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onOpenChat: (AgentView) -> Unit,
    onRemove: (String) -> Unit = {},
    work: AgentWorkActions = AgentWorkActions.None,
) {
    val lanes = workerLanes(state)
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        modifier = Modifier.fillMaxSize().padding(28.dp).testTag(GLOBAL_WORKERS_DIALOG_TAG),
        wide = true,
        containerColor = Palette.Panel,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Working in every project (${state.workingCount})", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(GLOBAL_WORKERS_CLOSE_TAG)) { Text("Close") }
            }
        },
        text = {
            if (lanes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(GLOBAL_WORKERS_EMPTY_TEXT, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.testTag(GLOBAL_WORKERS_EMPTY_TAG))
                }
            } else {
                Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lanes.forEach { lane -> WorkerLaneColumn(lane, state, onOpenChat, onRemove, work) }
                }
            }
        },
    )
}

@Composable
private fun WorkerLaneColumn(lane: WorkerLane, state: AppState, onOpenChat: (AgentView) -> Unit, onRemove: (String) -> Unit, work: AgentWorkActions) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(280.dp).fillMaxHeight().clip(shape).background(Palette.Bg).border(1.dp, Palette.Border, shape)
            .testTag(GLOBAL_WORKERS_LANE_TAG_PREFIX + (lane.projectId ?: "none")),
    ) {
        Row(Modifier.fillMaxWidth().background(Palette.Raised).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lane.name, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${lane.agents.size}", color = Palette.Ok, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
            items(lane.agents, key = { it.id }) { a ->
                AgentRow(a, state, selected = false, onClick = { onOpenChat(a) }, onRemove = onRemove, modifier = Modifier.padding(bottom = 6.dp), work = work)
            }
        }
    }
}

/**
 * The chat modal stacked over the lanes: the shared chat panel ([chatPanel], Infantry_Chat's, initialised by session id
 * alone) for [agent], with Open session (switch to its project and select it) and Close (back to the lanes as they were).
 */
@Composable
fun GlobalWorkerChatDialog(
    agent: AgentView,
    projectName: String,
    onDismiss: () -> Unit,
    onOpenSession: () -> Unit,
    chatPanel: (@Composable (Modifier) -> Unit)?,
) {
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        modifier = Modifier.fillMaxSize().padding(28.dp).testTag(GLOBAL_WORKERS_CHAT_TAG),
        wide = true,
        containerColor = Palette.Panel,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${agent.role ?: agent.shortId} · $projectName", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSession, modifier = Modifier.testTag(GLOBAL_WORKERS_OPEN_SESSION_TAG)) { Text("Open session") }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(GLOBAL_WORKERS_CLOSE_TAG)) { Text("Close") }
            }
        },
        text = {
            Box(Modifier.fillMaxSize()) {
                if (chatPanel != null) chatPanel(Modifier.fillMaxSize())
                else Text("The chat is not loaded (Infantry_Chat is off).", color = Palette.Muted, fontSize = 12.sp)
            }
        },
    )
}

/**
 * The lanes modal and, over it, the chat modal of the card last clicked. The lanes stay composed under the chat, so
 * closing the chat returns to them as they were. Open session switches to the session's project (when it is another
 * one) and selects it, then closes both. The chat panel is Infantry_Chat's, handed over through [ChatPanels].
 */
@Composable
internal fun GlobalWorkersHost(
    state: AppState,
    controller: com.superiorsoftllc.infantry.ui.net.Controller?,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    work: AgentWorkActions,
) {
    var chatId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    GlobalWorkersDialog(state, onDismiss = onClose, onOpenChat = { chatId = it.id }, onRemove = onRemove, work = work)
    val agent = chatId?.let { state.agents[it] }
    if (agent != null) {
        val pid = agent.session.projectId
        val projectName = pid?.let { id -> state.projects.firstOrNull { it.id == id }?.name ?: id } ?: NO_PROJECT_LANE
        val panel = com.superiorsoftllc.infantry.ui.shell.ChatPanels.panel
        val chat = com.superiorsoftllc.infantry.ui.rememberChatPanelState(agent.id)
        GlobalWorkerChatDialog(
            agent, projectName,
            onDismiss = { chatId = null },
            onOpenSession = {
                // UNDISPATCHED: selectProject updates the state before its first suspension, so the select below sticks.
                if (controller != null && pid != null && pid != state.activeProjectId) {
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.selectProject(pid) }
                }
                onSelect(agent.id)
                chatId = null
                onClose()
            },
            chatPanel = if (panel != null && controller != null) { m -> panel(agent.id, controller, m, chat, false, GLOBAL_WORKERS_TRANSCRIPT_TAG) } else null,
        )
    }
}
