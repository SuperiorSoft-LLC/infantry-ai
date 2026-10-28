package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.TriggerView
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal fun triggerStateColor(state: String): Color = when (state) {
    "armed" -> Palette.Ok
    "running" -> Palette.Info
    "tripped" -> Palette.Danger
    else -> Palette.Muted
}

private val BUILT_IN_TRIGGER_ROLES = listOf("pm", "qa", "version-control", "assistant")

/**
 * The `role:<role>` targets a trigger can aim at: the built-in roles (the PM, QA, ... run without a profile, so
 * profiles alone would omit them), the daemon's role catalog and every role a visible profile has.
 */
internal fun triggerLanes(state: AppState): List<String> =
    (BUILT_IN_TRIGGER_ROLES + state.roles.map { it.id } + state.visibleProfiles.map { it.role }).distinct().map { "role:$it" }

/** "any ticket" / "tickets for <assignee>" -- what a `start_next` trigger picks from. */
internal fun triggerTarget(state: AppState, t: TriggerView): String =
    t.action.agentId?.takeIf { it.isNotBlank() }?.let { "next ticket assigned to ${assigneeLabel(state.visibleProfiles, it)}" } ?: "next startable ticket"

/** One line: when it fires and what it does -- "every 7 min: mail role:qa in AgentHQ: ..." / "manual: next startable ticket". */
internal fun triggerSummary(state: AppState, t: TriggerView): String {
    val whenText = if (t.kind == "schedule") t.intervalMs?.let { "every ${humanDuration(it)}" } ?: "schedule (no interval)" else t.kind
    val what = if (t.action.kind == "prompt") {
        val project = t.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name ?: id }
        "mail ${t.action.agentId?.let { assigneeLabel(state.visibleProfiles, it) } ?: "?"}" + (project?.let { " in $it" } ?: "") + ": \"${t.action.prompt.orEmpty().take(60)}\""
    } else {
        triggerTarget(state, t)
    }
    return "$whenText: $what"
}

/**
 * Triggers (contract 1.14.0; 1.105.0 adds schedules and prompt mail here): each one starts the next ticket, or mails
 * a prompt to a role, when fired by hand or every N minutes. A fire is one event; the daemon
 * coalesces events, caps starts per hour, and TRIPS the trigger on any non-success -- a tripped trigger starts
 * nothing until it is re-armed here. Every event has a durable disposition row, listed under "Events".
 */
@Composable
internal fun TriggersPane(state: AppState, controller: Controller) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.triggers.size} triggers" + state.trippedTriggerCount.takeIf { it > 0 }?.let { ", $it tripped" }.orEmpty(), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Button(onClick = { creating = !creating }, enabled = state.connection.isLive) { Text(if (creating) "Close" else "+ New trigger", fontSize = 12.sp) }
        }
        if (creating) TriggerForm(state, controller, onDone = { creating = false })
        if (state.triggers.isEmpty() && !creating) {
            Text(if (state.connection.isLive) "No trigger yet." else "Connect to a daemon to load its triggers.", color = Palette.Text)
            Text("A trigger starts the next ticket from the board, or mails a prompt to any role or agent, by hand or every N minutes. Past its hourly ceiling, or after any failed run, it trips and waits for you to re-arm it.", color = Palette.Muted, fontSize = 11.sp)
        }
        state.triggers.forEach { t -> TriggerCard(t, state, controller) }
    }
}

@Composable
private fun TriggerCard(t: TriggerView, state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    var showEvents by remember(t.id) { mutableStateOf(false) }
    var editing by remember(t.id) { mutableStateOf(false) }
    val live = state.connection.isLive
    val now = Clock.System.now().toEpochMilliseconds()
    // The rows are history the daemon keeps; refresh them whenever the trigger changes while they are open.
    LaunchedEffect(t.id, t.state, t.trippedAt, t.lastFiredAt, showEvents, live) { if (showEvents && live) controller.loadTriggerEvents(t.id) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(t.state, triggerStateColor(t.state))
        }
        Text(triggerSummary(state, t), color = Palette.Muted, fontSize = 11.sp)
        Text(
            listOfNotNull(
                nextFireLabel(t.nextFireAt, now)?.let { "next fire $it" }.takeIf { t.state != "disabled" && t.state != "tripped" },
                "last fired ${lastFireLabel(t.lastFiredAt, now)}",
            ).joinToString("  -  "),
            color = Palette.Text, fontSize = 11.sp,
        )
        Text(
            "window ${if (t.coalesceMs == 0L) "none (starts at once)" else humanDuration(t.coalesceMs)}  -  at most ${t.ceilingRuns} starts per ${t.ceilingWindowMs / 60_000} min",
            color = Palette.Muted, fontSize = 10.sp,
        )
        if (t.state == "tripped") {
            Text(
                "Tripped: ${t.trippedReason ?: "a run did not succeed"}. Nothing starts until you re-arm it.",
                color = Palette.Danger, fontSize = 11.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = live && t.state != "disabled",
                onClick = { actionScope.launch { controller.fireTrigger(t.id); if (showEvents) controller.loadTriggerEvents(t.id) } },
            ) { Text("Fire now", fontSize = 12.sp) }
            if (t.state == "tripped") {
                OutlinedButton(onClick = { actionScope.launch { controller.armTrigger(t.id) } }, enabled = live) { Text("Re-arm", fontSize = 12.sp) }
            }
            // The enable toggle: off disables (nothing fires), on arms it again (trigger_arm enables since 1.105.0).
            Checkbox(
                checked = t.state != "disabled",
                onCheckedChange = { on -> actionScope.launch { if (on) controller.armTrigger(t.id) else controller.disableTrigger(t.id) } },
                enabled = live,
            )
            Text("Enabled", color = Palette.Muted, fontSize = 11.sp)
            TextButton(onClick = { showEvents = !showEvents }) { Text(if (showEvents) "Hide events" else "Events", fontSize = 11.sp) }
            TextButton(onClick = { editing = !editing }, enabled = live) { Text(if (editing) "Close" else "Edit", fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { actionScope.launch { controller.deleteTrigger(t.id) } }, enabled = live) { Text("Delete", color = Palette.Danger, fontSize = 11.sp) }
        }
        if (editing) TriggerForm(state, controller, onDone = { editing = false }, existing = t)
        if (showEvents) TriggerEvents(t, state, controller)
    }
}

@Composable
private fun TriggerEvents(t: TriggerView, state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    val now = Clock.System.now().toEpochMilliseconds()
    val rows = state.triggerEvents[t.id].orEmpty().asReversed()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EVENTS (newest first)", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { actionScope.launch { controller.loadTriggerEvents(t.id) } }, enabled = state.connection.isLive) { Text("Refresh", fontSize = 11.sp) }
        }
        if (rows.isEmpty()) Text("No event yet: fire the trigger.", color = Palette.Muted, fontSize = 11.sp)
        rows.take(25).forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(e.disposition.replace('_', ' '), dispositionColor(e.disposition))
                Text(e.source, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(ago(e.receivedAt, now) + " ago", color = Palette.Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun dispositionColor(d: String): Color = when (d) {
    "started", "folded", "followup_folded" -> Palette.Ok
    "nothing_to_start", "duplicate", "missed" -> Palette.Muted
    else -> Palette.Danger
}

/**
 * Create ([existing] null) or edit a trigger: manual or every N minutes, starting the next ticket or mailing a prompt
 * to a role in a project (TRIGGER-NUDGE). An edit opens on the trigger as stored, so it keeps its kind and interval.
 */
@Composable
private fun TriggerForm(state: AppState, controller: Controller, onDone: () -> Unit, existing: TriggerView? = null) {
    val actionScope = rememberCoroutineScope()
    var draft by remember(existing?.id) { mutableStateOf(TriggerDraft.from(existing, state.activeProjectId)) }
    val lanes = triggerLanes(state)
    val problem = draft.problem()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (existing == null) "New trigger" else "Edit ${existing.name}", color = Palette.Text, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("When", color = Palette.Muted, fontSize = 11.sp)
            Chip("when I fire it", if (!draft.isSchedule) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(kind = "manual") })
            Chip("every N minutes", if (draft.isSchedule) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(kind = "schedule") })
            if (draft.isSchedule) {
                OutlinedTextField(
                    draft.intervalMinutes, { draft = draft.copy(intervalMinutes = it) }, label = { Text("Interval, minutes (min $MIN_TRIGGER_INTERVAL_MINUTES)") },
                    singleLine = true, isError = draft.intervalMs == null, modifier = Modifier.width(220.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Do", color = Palette.Muted, fontSize = 11.sp)
            Chip("start the next ticket", if (!draft.isPrompt) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(action = "start_next") })
            Chip("send a prompt", if (draft.isPrompt) Palette.Accent else Palette.Muted, Modifier.clickable {
                draft = draft.copy(action = "prompt", projectId = draft.projectId ?: state.activeProjectId)
            })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (draft.isPrompt) "To" else "Only tickets assigned to", color = Palette.Muted, fontSize = 11.sp)
            if (!draft.isPrompt) Chip("anyone", if (draft.agentId == null) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(agentId = null) })
            lanes.forEach { lane -> Chip(lane, if (draft.agentId == lane) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(agentId = lane) }) }
            state.visibleProfiles.forEach { p -> p.id?.let { id -> Chip(p.name, if (draft.agentId == id) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(agentId = id) }) } }
        }
        if (draft.isPrompt) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In project", color = Palette.Muted, fontSize = 11.sp)
                Chip("none", if (draft.projectId == null) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(projectId = null) })
                state.projects.forEach { p -> Chip(p.name, if (draft.projectId == p.id) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(projectId = p.id) }) }
            }
            OutlinedTextField(
                draft.prompt, { draft = draft.copy(prompt = it) }, label = { Text("Prompt") }, minLines = 3,
                isError = draft.prompt.isBlank(), modifier = Modifier.fillMaxWidth(),
            )
            Text("Delivered as mail: a busy agent gets it when its turn ends; at most one waits per trigger.", color = Palette.Muted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                draft.coalesceMinutes, { draft = draft.copy(coalesceMinutes = it) }, label = { Text("Coalesce window, minutes (0 = start at once)") }, singleLine = true,
                isError = draft.coalesceMs == null, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                draft.ceiling, { draft = draft.copy(ceiling = it) }, label = { Text("Max starts / hour") }, singleLine = true,
                isError = draft.ceilingRuns == null, modifier = Modifier.width(150.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            Text("Enabled", color = Palette.Muted, fontSize = 11.sp)
        }
        problem?.takeIf { draft.name.isNotBlank() }?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = state.connection.isLive && problem == null,
                onClick = { actionScope.launch { if (controller.saveTrigger(existing?.id, draft) is CommandResult.Sent) onDone() } },
            ) { Text(if (existing == null) "Create trigger" else "Save changes") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
