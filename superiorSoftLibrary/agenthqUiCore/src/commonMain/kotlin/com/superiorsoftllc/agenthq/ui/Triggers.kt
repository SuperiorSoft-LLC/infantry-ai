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
    t.action.agentId?.takeIf { it.isNotBlank() }?.let { "next ticket assigned to ${assigneeLabel(state, it)}" } ?: "next startable ticket"

/**
 * Manual triggers (contract 1.14.0): each one starts the next ticket when fired. A fire is one event; the daemon
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
            Text("A trigger starts the next ticket from the board each time you fire it. Past its hourly ceiling, or after any failed run, it trips and waits for you to re-arm it.", color = Palette.Muted, fontSize = 11.sp)
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
    // The rows are history the daemon keeps; refresh them whenever the trigger changes while they are open.
    LaunchedEffect(t.id, t.state, t.trippedAt, showEvents, live) { if (showEvents && live) controller.loadTriggerEvents(t.id) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(t.state, triggerStateColor(t.state))
        }
        Text(triggerTarget(state, t), color = Palette.Muted, fontSize = 11.sp)
        Text(
            "window ${if (t.coalesceMs == 0L) "none (starts at once)" else "${t.coalesceMs / 1000}s"}  -  at most ${t.ceilingRuns} starts per ${t.ceilingWindowMs / 60_000} min",
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
            ) { Text("Fire", fontSize = 12.sp) }
            if (t.state == "tripped" || t.state == "disabled") {
                OutlinedButton(onClick = { actionScope.launch { controller.armTrigger(t.id) } }, enabled = live) {
                    Text(if (t.state == "tripped") "Re-arm" else "Enable", fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = { actionScope.launch { controller.disableTrigger(t.id) } }, enabled = live && t.state == "armed") { Text("Disable", fontSize = 12.sp) }
            }
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

/** Create ([existing] null) or edit a manual `start_next` trigger. */
@Composable
private fun TriggerForm(state: AppState, controller: Controller, onDone: () -> Unit, existing: TriggerView? = null) {
    val actionScope = rememberCoroutineScope()
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    // null = any ticket, else a profile id or role:<role>
    var agentId by remember(existing?.id) { mutableStateOf(existing?.action?.agentId?.takeIf { it.isNotBlank() }) }
    var coalesce by remember(existing?.id) { mutableStateOf((existing?.coalesceMs ?: 0L).toString()) }
    var ceiling by remember(existing?.id) { mutableStateOf((existing?.ceilingRuns ?: 6).toString()) }
    val lanes = triggerLanes(state)
    val coalesceMs = coalesce.trim().toLongOrNull()?.takeIf { it >= 0 }
    val ceilingRuns = ceiling.trim().toIntOrNull()?.takeIf { it > 0 }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (existing == null) "New manual trigger" else "Edit ${existing.name}", color = Palette.Text, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Only tickets assigned to", color = Palette.Muted, fontSize = 11.sp)
            Chip("anyone", if (agentId == null) Palette.Accent else Palette.Muted, Modifier.clickable { agentId = null })
            lanes.forEach { lane -> Chip(lane, if (agentId == lane) Palette.Accent else Palette.Muted, Modifier.clickable { agentId = lane }) }
            state.visibleProfiles.forEach { p -> p.id?.let { id -> Chip(p.name, if (agentId == id) Palette.Accent else Palette.Muted, Modifier.clickable { agentId = id }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                coalesce, { coalesce = it }, label = { Text("Coalesce window ms (0 = start at once)") }, singleLine = true,
                isError = coalesceMs == null, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                ceiling, { ceiling = it }, label = { Text("Max starts / hour") }, singleLine = true,
                isError = ceilingRuns == null, modifier = Modifier.width(150.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = state.connection.isLive && name.isNotBlank() && coalesceMs != null && ceilingRuns != null,
                onClick = {
                    actionScope.launch {
                        if (controller.saveTrigger(existing?.id, name, agentId, coalesceMs!!, ceilingRuns!!) is CommandResult.Sent) onDone()
                    }
                },
            ) { Text(if (existing == null) "Create trigger" else "Save changes") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
