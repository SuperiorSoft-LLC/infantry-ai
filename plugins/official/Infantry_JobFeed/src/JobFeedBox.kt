// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.clickable
import com.superiorsoftllc.infantry.ui.plugin.LocalSlotArgs
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import kotlinx.coroutines.delay

const val JOBFEED_BOX_TAG = "jobfeed-box"
const val JOBFEED_TOGGLE_TAG = "jobfeed-toggle"
const val JOBFEED_PAUSE_TAG = "jobfeed-pause"
const val JOBFEED_LINES_TAG = "jobfeed-lines"

private const val CLOCK_TICK_MS = 1000L
private const val BOX_MAX_HEIGHT_DP = 200
private const val JOB_CHIPS_SHOWN = 5

/** Reads [JobFeedSettings] from the resolved config node. Every key is optional; an unset one keeps its default. */
fun settingsOf(ctx: TabContext): JobFeedSettings {
    val v = ctx.config(JobFeedKeys.NODE)
    return JobFeedSettings(
        maxLines = v.int(JobFeedKeys.MAX_LINES) ?: JobFeedDefaults.MAX_LINES,
        updatesPerSecond = v.int(JobFeedKeys.UPDATES_PER_SECOND) ?: JobFeedDefaults.UPDATES_PER_SECOND,
        lowPower = v.boolean(JobFeedKeys.LOW_POWER) ?: false,
        autoCollapseUnfocused = v.boolean(JobFeedKeys.AUTO_COLLAPSE) ?: true,
        openByDefault = v.boolean(JobFeedKeys.OPEN_BY_DEFAULT) ?: false,
        failureMarkers = v.string(JobFeedKeys.FAILURE_MARKERS) ?: JobFeedDefaults.FAILURE_MARKERS,
        quietSeconds = v.int(JobFeedKeys.QUIET_SECONDS) ?: JobFeedDefaults.QUIET_SECONDS,
    )
}

/**
 * JOBFEED-1: the box under the chat feed. While it is closed (or the window is not focused, or it is paused) NOTHING is read from
 * the daemon and nothing but one header line is composed; the header's job list comes from the session record the window already has.
 * Open, it asks the daemon for the lines appended since its offset every `1000 / updates-per-second` ms (a pull: the daemon keeps no stream).
 */
@Composable
fun JobFeedBox(ctx: TabContext) {
    val sessionId = LocalSlotArgs.current["sessionId"] as? String ?: return
    val session = ctx.state.agents[sessionId]?.session
    val settings = settingsOf(ctx)
    val daemon = ctx.services.get(Services.DAEMON)

    var open by remember(sessionId) { mutableStateOf(settings.openByDefault) }
    var paused by remember(sessionId) { mutableStateOf(false) }
    var picked by remember(sessionId) { mutableStateOf<String?>(null) }
    var lastSeen by remember(sessionId) { mutableStateOf<Pair<String, String>?>(null) }

    val running = session?.bgJobs.orEmpty()
    val newest = running.maxByOrNull { it.startedAt }
    LaunchedEffect(newest?.id) { newest?.let { lastSeen = it.id to it.label } }
    val jobId = picked ?: newest?.id ?: lastSeen?.first
    if (jobId == null) return // no job to show: draw nothing at all

    val focused = LocalWindowInfo.current.isWindowFocused
    val active = open && !paused && (focused || !settings.autoCollapseUnfocused) && daemon != null

    val buffer = remember(sessionId, jobId, settings.effectiveMaxLines) { LineBuffer { settings.effectiveMaxLines } }
    var lines by remember(sessionId, jobId) { mutableStateOf(emptyList<String>()) }
    var info by remember(sessionId, jobId) { mutableStateOf<JobFeedChunk?>(null) }
    var offset by remember(sessionId, jobId) { mutableStateOf(-1L) }
    var lastOutputAt by remember(sessionId, jobId) { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }

    LaunchedEffect(sessionId, jobId, active, settings.pollMs) {
        if (!active || daemon == null) return@LaunchedEffect
        while (true) {
            val reply = daemon.call(JobFeedWire.READ_ROUTE, JobFeedWire.readBody(sessionId, jobId, offset))
            val chunk = (reply as? DaemonReply.Ok)?.takeIf { it.status == 200 }?.let { JobFeedWire.parseChunk(it.body) }
            if (chunk != null && chunk.known) {
                if (!chunk.throttled) {
                    if (chunk.reset) buffer.clear()
                    if (chunk.text.isNotEmpty()) { buffer.append(chunk.text); lastOutputAt = now.takeIf { it > 0 } ?: lastOutputAt; lines = buffer.snapshot() }
                    offset = chunk.next
                    info = chunk
                }
                // An ended job whose log is read to its end needs no more reads.
                if (!chunk.throttled && !chunk.running && chunk.next >= chunk.size) return@LaunchedEffect
            } else if (chunk != null) return@LaunchedEffect
            delay(settings.pollMs)
        }
    }
    // One clock tick a second, only while the box is open (the title shows the elapsed time).
    LaunchedEffect(open, jobId) {
        while (open) { now = platformNow(); delay(CLOCK_TICK_MS) }
    }

    val label = info?.label?.takeIf { it.isNotBlank() } ?: running.firstOrNull { it.id == jobId }?.label ?: lastSeen?.takeIf { it.first == jobId }?.second ?: jobId
    val isRunning = info?.running ?: running.any { it.id == jobId }
    val started = info?.startedAt?.takeIf { it > 0 } ?: running.firstOrNull { it.id == jobId }?.startedAt ?: 0L
    val markers = remember(settings.failureMarkers) { settings.markers }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag(JOBFEED_BOX_TAG)
            .background(Palette.Panel, RoundedCornerShape(6.dp)).border(1.dp, Palette.Border, RoundedCornerShape(6.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (open) "v " else "> ") + label + "  -  " + jobStatusText(isRunning, info?.exitCode) +
                    (if (open && started > 0 && now > 0) "  -  " + elapsedText(started, now) else "") +
                    (if (running.size > 1) "  (+${running.size - 1} more)" else ""),
                color = if (!isRunning && (info?.exitCode ?: 0) != 0) Palette.Danger else Palette.Text, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.weight(1f).testTag(JOBFEED_TOGGLE_TAG).clickable(onClickLabel = "Show or hide the job output") { open = !open },
            )
            if (open) Text(
                if (paused) "Follow" else "Pause", color = Palette.Accent, fontSize = 12.sp,
                modifier = Modifier.testTag(JOBFEED_PAUSE_TAG).clickable(onClickLabel = "Pause or follow the output") { paused = !paused },
            )
        }
        if (open) {
            if (running.size > 1) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                running.sortedByDescending { it.startedAt }.take(JOB_CHIPS_SHOWN).forEach { j ->
                    Text(j.label, color = if (j.id == jobId) Palette.Text else Palette.Muted, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.clickable { picked = j.id })
                }
            }
            quietNotice(isRunning, lastOutputAt, now, settings.quietSeconds)?.let {
                Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size, paused) { if (!paused && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = BOX_MAX_HEIGHT_DP.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag(JOBFEED_LINES_TAG), state = listState) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                        color = if (isFailureLine(line, markers)) Palette.Danger else Palette.Text)
                }
            }
        }
    }
}
