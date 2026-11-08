// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ProgressState
import com.superiorsoftllc.infantry.plugin.api.ProgressTask
import com.superiorsoftllc.infantry.ui.LocalTabFactory
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.GuardedBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import kotlinx.coroutines.flow.StateFlow

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): what the core progress views read: the client's mirror of the
 * progress system (the controller provides it at the shell root as [LocalProgress]).
 */
interface ProgressUi {
    /** Every task, live first (oldest first), then the finished ones (newest first). */
    val tasks: StateFlow<List<ProgressTask>>
    fun cancel(id: String)
    fun retry(id: String)
    fun clearFinished()
}

/** CORE-PROGRESS-DOWNLOAD-1: the nearest [ProgressUi]; null = no progress system (a preview, a test). */
val LocalProgress = staticCompositionLocalOf<ProgressUi?> { null }

/** CORE-PROGRESS-DOWNLOAD-1: what a renderer may do with a task: null = that action is not offered for it. */
data class ProgressActions(val cancel: (() -> Unit)? = null, val retry: (() -> Unit)? = null)

/**
 * CORE-PROGRESS-DOWNLOAD-1: a plugin's drawing of one task. Register it with
 * `implementation(PROGRESS_RENDERER_REGISTRY, "<your module>", renderer)` (declared under `implementations` in
 * module.json); the last one registered draws every task, in the inline indicators and the running-tasks list. A
 * renderer that throws while composing is dropped for the core drawing ([CoreProgressView]) and reported as its fault.
 */
interface ProgressRenderer {
    @Composable
    fun Task(task: ProgressTask, actions: ProgressActions, modifier: Modifier)
}

/** CORE-PROGRESS-DOWNLOAD-1: the implementations registry a [ProgressRenderer] goes in. */
const val PROGRESS_RENDERER_REGISTRY = "progress.renderer"

const val PROGRESS_LIST_TAG = "progress-running-tasks"
const val PROGRESS_TASK_TAG = "progress-task"
const val PROGRESS_RETRY_TAG = "progress-retry"
const val PROGRESS_CANCEL_TAG = "progress-cancel"

/**
 * CORE-PROGRESS-DOWNLOAD-1: the inline indicator any screen binds to by task id. Draws nothing until the task exists
 * (and after it is forgotten); [hideWhenDone] also hides it once it ended well.
 */
@Composable
fun ProgressIndicator(taskId: String, modifier: Modifier = Modifier, hideWhenDone: Boolean = false) {
    val ui = LocalProgress.current ?: return
    val tasks by ui.tasks.collectAsState()
    val task = tasks.firstOrNull { it.id == taskId } ?: return
    if (hideWhenDone && task.state == ProgressState.DONE) return
    ProgressView(task, modifier)
}

/** CORE-PROGRESS-DOWNLOAD-1: one task through the active renderer (a plugin's, else the core drawing). */
@Composable
fun ProgressView(task: ProgressTask, modifier: Modifier = Modifier) {
    val ui = LocalProgress.current
    val actions = ProgressActions(
        cancel = if (ui != null && task.cancellable && !task.finished) ({ ui.cancel(task.id) }) else null,
        retry = if (ui != null && task.retryable && (task.state == ProgressState.FAILED || task.state == ProgressState.CANCELLED)) ({ ui.retry(task.id) }) else null,
    )
    val impls = LocalTabFactory.current.implementations
    val key = impls.keys(PROGRESS_RENDERER_REGISTRY).lastOrNull()
    val renderer = key?.let { impls.get(PROGRESS_RENDERER_REGISTRY, it) as? ProgressRenderer }
    if (renderer == null) { CoreProgressView(task, actions, modifier); return }
    val module = impls.moduleOf(PROGRESS_RENDERER_REGISTRY, key!!) ?: key
    GuardedBlock(
        key = OverrideHealth.key(module, "progress", PROGRESS_RENDERER_REGISTRY),
        instance = renderer,
        fallback = { CoreProgressView(task, actions, modifier) },
    ) { renderer.Task(task, actions, modifier) }
}

/** CORE-PROGRESS-DOWNLOAD-1: the core drawing of one task: title, step, bar, amount, ETA, error, Cancel / Retry. */
@Composable
fun CoreProgressView(task: ProgressTask, actions: ProgressActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(PROGRESS_TASK_TAG), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                task.title.ifBlank { task.id }, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            actions.cancel?.let { c -> TextButton(onClick = c, contentPadding = SMALL, modifier = Modifier.testTag(PROGRESS_CANCEL_TAG)) { Text("Cancel", fontSize = 11.sp) } }
            actions.retry?.let { r -> TextButton(onClick = r, contentPadding = SMALL, modifier = Modifier.testTag(PROGRESS_RETRY_TAG)) { Text("Retry", fontSize = 11.sp) } }
        }
        val line = progressLine(task)
        if (line.isNotEmpty()) Text(line, color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!task.finished) ProgressBar(task.fraction, Modifier.fillMaxWidth())
        task.error?.takeIf { task.state == ProgressState.FAILED }?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
    }
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: THE progress bar: [fraction] 0..1, or null for an indeterminate one. Every screen draws its
 * bar through this (or a whole task through [ProgressView]); the guard test refuses a raw Material indicator elsewhere.
 */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null) LinearProgressIndicator(modifier, color = Palette.Accent, trackColor = Palette.Border)
    else LinearProgressIndicator({ fraction.coerceIn(0f, 1f) }, modifier, color = Palette.Accent, trackColor = Palette.Border)
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: the global running-tasks list (drawn over the window by the shell). Shown while a task is
 * live or a finished one is kept; it folds to one line, and Clear forgets the finished ones.
 */
@Composable
fun RunningTasks(modifier: Modifier = Modifier) {
    val ui = LocalProgress.current ?: return
    val tasks by ui.tasks.collectAsState()
    if (tasks.isEmpty()) return
    var open by remember { mutableStateOf(true) }
    val live = tasks.count { !it.finished }
    Column(
        modifier.widthIn(max = 340.dp).background(Palette.Raised, SHAPE).border(1.dp, Palette.Border, SHAPE).padding(8.dp).testTag(PROGRESS_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (live > 0) "Running tasks ($live)" else "Tasks", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (tasks.any { it.finished }) TextButton(onClick = ui::clearFinished, contentPadding = SMALL) { Text("Clear", fontSize = 11.sp) }
            TextButton(onClick = { open = !open }, contentPadding = SMALL) { Text(if (open) "Hide" else "Show", fontSize = 11.sp) }
        }
        if (open) for (t in tasks.take(MAX_SHOWN)) ProgressView(t)
    }
}

/** CORE-PROGRESS-DOWNLOAD-1: the second line of a task: its step (k of n), its amount and its ETA. */
fun progressLine(task: ProgressTask): String = buildList {
    when (task.state) {
        ProgressState.DONE -> add(task.step.ifBlank { "Done" })
        ProgressState.CANCELLED -> add("Cancelled")
        ProgressState.QUEUED -> add("Queued")
        else -> if (task.step.isNotBlank()) add(if (task.stepIndex >= 0 && task.steps.isNotEmpty()) "${task.step} (${task.stepIndex + 1} of ${task.steps.size})" else task.step)
    }
    if (!task.finished && task.done >= 0) add(amount(task))
    if (!task.finished && task.etaMs > 0) add("about ${duration(task.etaMs)} left")
}.joinToString(" · ")

private fun amount(t: ProgressTask): String {
    val bytes = t.unit == "bytes"
    fun f(n: Long) = if (bytes) size(n) else n.toString()
    val tail = if (bytes) "" else t.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return if (t.total > 0) "${f(t.done)} of ${f(t.total)}$tail" else "${f(t.done)}$tail"
}

private fun size(n: Long): String = when {
    n >= GB -> "${tenths(n, GB)} GB"
    n >= MB -> "${tenths(n, MB)} MB"
    n >= KB -> "${n / KB} KB"
    else -> "$n B"
}

private fun tenths(n: Long, unit: Long): String = (n * 10 / unit).let { "${it / 10}.${it % 10}" }

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60} min" else "${s.coerceAtLeast(1)} s"
}

private val SHAPE = RoundedCornerShape(8.dp)
private val SMALL = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
private const val MAX_SHOWN = 8
private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024
