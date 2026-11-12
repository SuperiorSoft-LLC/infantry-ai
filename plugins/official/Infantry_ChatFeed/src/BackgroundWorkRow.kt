// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.superiorsoftllc.infantry.ui.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Mono
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.model.ChatStatus
import com.superiorsoftllc.infantry.ui.model.formatElapsed
import com.superiorsoftllc.infantry.ui.model.turnInFlight
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord

/** CHAT-BG-1: test tags on the trailing background-work row, its header and one expanded job line. */
const val CHAT_BG_ROW_TAG = "chat-bg-row"
const val CHAT_BG_HEADER_TAG = "chat-bg-header"
const val CHAT_BG_JOB_TAG = "chat-bg-job"

/** CHAT-BG-1: the newest job's label is cut to this many characters on the one-line header. */
const val CHAT_BG_LABEL_MAX_CHARS = 60

/** CHAT-BG-1: one background job as the row shows it; [startedAt] is epoch millis, 0 = unknown. */
data class BgWorkItem(val label: String, val startedAt: Long)

/** CHAT-BG-1: what the trailing row shows: the jobs (newest last) and the count the header reads. */
data class BgWorkSummary(val count: Int, val jobs: List<BgWorkItem>) {
    /** The newest job's label: the one with the latest start, else the last listed. */
    val newestLabel: String get() = (jobs.maxByOrNull { it.startedAt } ?: jobs.last()).label
}

/**
 * CHAT-BG-1: the session's live background work, from the same session fields the Running section reads: the agent's
 * registered `bg run` jobs first, else the daemon-observed background processes. Null when there is none.
 */
fun backgroundWorkOf(s: SessionRecord): BgWorkSummary? {
    if (s.bgJobs.isNotEmpty()) return BgWorkSummary(s.bgJobs.size, s.bgJobs.map { BgWorkItem(it.label, it.startedAt) })
    val w = s.backgroundWork ?: return null
    return BgWorkSummary(w.count.coerceAtLeast(1), listOf(BgWorkItem(w.label, w.startedAt)))
}

/**
 * CHAT-BG-1: the row shows only while the agent's turn has ended and background work still runs. A turn in flight (or a
 * "Thinking..." status, which an older daemon without `turnActive` also shows for background work) owns the chat's
 * bottom instead, so the two never show at once.
 */
fun trailingBackgroundWork(s: SessionRecord, status: ChatStatus?): BgWorkSummary? =
    if (turnInFlight(s) || status is ChatStatus.Thinking) null else backgroundWorkOf(s)

/** CHAT-BG-1: "Background: 2 running · gradlew test" (the label cut to one line of [CHAT_BG_LABEL_MAX_CHARS]). */
fun bgHeaderText(w: BgWorkSummary): String {
    val label = w.newestLabel.lineSequence().firstOrNull().orEmpty().trim()
    val cut = if (label.length > CHAT_BG_LABEL_MAX_CHARS) label.take(CHAT_BG_LABEL_MAX_CHARS - 1) + "…" else label
    return "Background: ${w.count} running" + if (cut.isNotEmpty()) " · $cut" else ""
}

/**
 * CHAT-BG-1: the quiet status row at the very end of the chat (the transcript list's footer, never a message): a pulsing
 * dot, "Background: N running" and the newest job. A click expands it into one line per job with its elapsed time.
 */
@Composable
fun BackgroundWorkRow(work: BgWorkSummary, now: Long) {
    var open by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "chatBg")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "chatBgPulse",
    )
    val header = bgHeaderText(work)
    Column(Modifier.testTag(CHAT_BG_ROW_TAG).padding(vertical = 4.dp)) {
        Row(
            Modifier.testTag(CHAT_BG_HEADER_TAG)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide background jobs" else "Show background jobs") { open = !open }
                .semantics { contentDescription = header },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(Palette.Muted))
            Spacer(Modifier.width(8.dp))
            Text(header, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(if (open) "  ▾" else "  ▸", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
        }
        if (open) for (job in work.jobs) {
            val elapsed = if (job.startedAt > 0) "  " + formatElapsed((now - job.startedAt).coerceAtLeast(0)) else ""
            Text(
                job.label.lineSequence().firstOrNull().orEmpty() + elapsed,
                color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(CHAT_BG_JOB_TAG).padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}
