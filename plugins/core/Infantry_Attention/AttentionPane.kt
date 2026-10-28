package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.watchFlagOf
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Attention (contract 1.9.0): the durable record of what needs a human -- stalled or overdue unattended
 * runs, tripped triggers, conversation ceilings -- newest first, each acknowledgeable from here.
 */
@Composable
internal fun AttentionPane(state: AppState, controller: Controller, showChat: () -> Unit = {}) {
    val actionScope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.attention.size) { now = Clock.System.now().toEpochMilliseconds() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ASK-1: the owner questions (open, answered, auto-picked, cancelled) come first; an auto-pick can be overridden.
        if (state.questions.isNotEmpty()) {
            QuestionHistory(
                state.questionsNewestFirst, live = state.connection.isLive,
                onOverride = { answer -> actionScope.launch { controller.answerQuestion(answer) } },
                onShow = { id -> controller.openQuestion(id); showChat() },
                onDismiss = { id -> actionScope.launch { controller.cancelQuestion(id) } },
            )
        }
        WatchSection(state, now)
        if (state.attention.isEmpty()) {
            if (state.questions.isNotEmpty()) return
            Text("Nothing needs your attention.", color = Palette.Text)
            Text(
                "Failed sessions, unattended runs that stall or pass their deadline, tripped triggers and conversation ceilings are recorded here, durably.",
                color = Palette.Muted, fontSize = 11.sp,
            )
            return
        }
        state.attentionNewestFirst.forEach { item ->
            val open = item.ackedAt == null
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(item.kind.replace('_', ' '), if (open) Palette.Accent else Palette.Muted)
                    Spacer(Modifier.width(6.dp))
                    Text(ago(item.at, now) + " ago", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    if (open) {
                        TextButton(onClick = { actionScope.launch { controller.ackAttention(item.id) } }, enabled = state.connection.isLive) {
                            Text("Acknowledge", fontSize = 11.sp)
                        }
                    } else {
                        Text("acknowledged", color = Palette.Muted, fontSize = 10.sp)
                    }
                }
                Text(item.detail, color = if (open) Palette.Text else Palette.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.ticketId?.let { Chip("ticket ${it.take(8)}") }
                    item.podId?.let { Chip("pod ${it.take(8)}") }
                    item.triggerId?.let { Chip("trigger ${it.take(12)}") }
                    item.sessionId?.takeIf { it in state.agents }?.let { sid ->
                        TextButton(onClick = { controller.openSession(sid); showChat() }) { Text("Open agent", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/** PIPELINE-WATCHDOG: every agent the daemon flags (stalled, unreported, quiet, waiting), with its pending item and how long. */
@Composable
private fun WatchSection(state: AppState, now: Long) {
    val flagged = state.agents.values.mapNotNull { a -> watchFlagOf(a.session.watch)?.let { Triple(a, a.session.watch!!, it) } }
        .sortedWith(compareBy({ !it.third.urgent }, { it.third.ordinal }))
    if (flagged.isEmpty()) return
    flagged.forEach { (a, w, flag) ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag("watch-${a.id}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WatchFlagChip(flag)
                Spacer(Modifier.width(6.dp))
                Text(state.agentTitle(a), color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text("last activity " + ago(w.lastActivityAt, now) + " ago", color = Palette.Muted, fontSize = 10.sp)
            }
            val since = w.pendingSince?.let { " for " + ago(it, now) } ?: ""
            Text((w.pending ?: flag.label) + since + if (w.nudges > 0) " (${w.nudges} nudge${if (w.nudges == 1) "" else "s"})" else "", color = Palette.Text, fontSize = 12.sp)
        }
    }
}
