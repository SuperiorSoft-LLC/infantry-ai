// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the state of one progress task. [QUEUED] and [RUNNING] are live;
 * [DONE], [FAILED] and [CANCELLED] are final (a final task never changes again, except a retry, which starts it over).
 */
@Serializable
enum class ProgressState {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: one task of the core progress system, as every presenter and client sees it (plain data, the
 * wire form of `progress_task`). [done] / [total] are amounts of [unit] (`bytes` for a download; -1 = unknown, the bar is
 * indeterminate). [steps] is the whole plan when known, [stepIndex] the running one (-1 = none), [step] its label.
 * [etaMs] = the time left (-1 = unknown). [error] is the reason a [ProgressState.FAILED] task failed; [retryable] = a
 * Retry can start it again. [link] is an optional pointer the presenter may open (a plugin id, `ticket:<id>`, a URL).
 */
@Serializable
data class ProgressTask(
    val id: String,
    val source: String = "",
    val title: String = "",
    val state: ProgressState = ProgressState.QUEUED,
    val done: Long = -1,
    val total: Long = -1,
    val unit: String = "",
    val step: String = "",
    val steps: List<String> = emptyList(),
    val stepIndex: Int = -1,
    val etaMs: Long = -1,
    val cancellable: Boolean = false,
    val retryable: Boolean = false,
    val error: String? = null,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val link: String? = null,
) {
    /** 0..1 while the size is known, else null (indeterminate). */
    val fraction: Float? get() = if (total > 0 && done >= 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val finished: Boolean get() = state.finished
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: what a caller gives to start a task. A blank [id] gets one assigned; a non-blank id that is
 * already live answers the live task's handle (one task per job, never two strips for one download). [retry], when set,
 * is what a Retry press runs (it should start the same work again, usually under the same [id]).
 */
data class ProgressSpec(
    val title: String,
    val id: String = "",
    val steps: List<String> = emptyList(),
    val unit: String = "",
    val total: Long = -1,
    val cancellable: Boolean = false,
    val link: String? = null,
    val retry: (() -> Unit)? = null,
)

/**
 * CORE-PROGRESS-DOWNLOAD-1: the caller's grip on one running task. Every call is cheap and thread-safe; calls after the
 * task is final are ignored. [close] ends a task that is still live as FAILED ("stopped without finishing"), so a
 * `use { }` block that throws never leaves a spinner behind: call [done] for success.
 */
interface ProgressHandle : AutoCloseable {
    val id: String
    /** The task as it stands now. */
    val task: ProgressTask
    /** True once the owner (or a caller) asked to cancel: stop the work, clean up, then call [cancelled]. */
    val cancelRequested: Boolean

    /** Moves to the step labelled [label]; [index] places it in [ProgressSpec.steps] (-1 = look it up by label). */
    fun step(label: String, index: Int = -1)

    /** Reports [done] of [total] [unit]s (-1 total = unknown); `bytes` for a download. */
    fun amount(done: Long, total: Long = -1, unit: String = "")

    /** The time left in milliseconds (-1 = unknown). */
    fun eta(ms: Long)

    fun done(step: String = "")

    /** Ends the task as FAILED with the clear [reason]; [retryable] shows Retry when the spec has a retry. */
    fun fail(reason: String, retryable: Boolean = true)

    /** Ends the task as CANCELLED (the work stopped after [cancelRequested]). */
    fun cancelled()

    /** [listener] runs once when a cancel is requested (at once if one already was). Close to stop listening. */
    fun onCancel(listener: () -> Unit): AutoCloseable

    override fun close() { if (!task.finished) fail("stopped without finishing", retryable = true) }
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: a progress presenter: how tasks reach the owner. The core default presenter is the host's own
 * (the daemon pushes `progress_task` to every client; the UI draws its progress area); a plugin registers one with
 * [Progress.present] to replace it. [show] runs on every change of a task, on the caller's thread: keep it quick. A
 * presenter that throws is recorded as its module's fault and the core default shows that change instead.
 */
fun interface ProgressPresenter {
    fun show(task: ProgressTask)
}

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the ONE progress/loader system, the same for daemon-side and UI-side
 * callers ([com.superiorsoftllc.infantry.plugin.host.ProgressCenter] is the implementation both hosts use). A task's
 * source is set by the host (the module id), never by the caller.
 */
interface Progress {
    fun start(spec: ProgressSpec): ProgressHandle

    /** Live tasks first (oldest first), then the most recent finished ones (newest first). */
    fun tasks(): List<ProgressTask>

    fun task(id: String): ProgressTask?

    /** [listener] hears every change of every task. Close to stop. */
    fun observe(listener: (ProgressTask) -> Unit): AutoCloseable

    /** Asks task [id] to cancel; false when it is not live or not cancellable. */
    fun cancel(id: String): Boolean

    /** Runs the retry of failed task [id]; false when it has none. */
    fun retry(id: String): Boolean

    /**
     * Registers [presenter] as [presenterId]'s (use the module name). It is the active presenter when the setting
     * `progress.presenter` names it, or that setting is blank and it is the last one registered. Close to remove.
     */
    fun present(presenterId: String, presenter: ProgressPresenter): AutoCloseable = AutoCloseable {}
}

/** CORE-PROGRESS-DOWNLOAD-1: the progress system of a host that has none: tasks are tracked by nobody and never shown. */
object NoProgress : Progress {
    override fun start(spec: ProgressSpec): ProgressHandle = object : ProgressHandle {
        override val id = spec.id
        override var task = ProgressTask(spec.id, title = spec.title, state = ProgressState.RUNNING)
        override val cancelRequested = false
        override fun step(label: String, index: Int) {}
        override fun amount(done: Long, total: Long, unit: String) {}
        override fun eta(ms: Long) {}
        override fun done(step: String) { task = task.copy(state = ProgressState.DONE) }
        override fun fail(reason: String, retryable: Boolean) { task = task.copy(state = ProgressState.FAILED, error = reason) }
        override fun cancelled() { task = task.copy(state = ProgressState.CANCELLED) }
        override fun onCancel(listener: () -> Unit) = AutoCloseable {}
    }
    override fun tasks(): List<ProgressTask> = emptyList()
    override fun task(id: String): ProgressTask? = null
    override fun observe(listener: (ProgressTask) -> Unit) = AutoCloseable {}
    override fun cancel(id: String) = false
    override fun retry(id: String) = false
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: the frames of the progress system. The daemon pushes [PUSH] (one [ProgressTask]) on every
 * change and replays the live and recent tasks to a client that connects; a client sends [CANCEL] / [RETRY] (`{"id"}`).
 */
object ProgressWire {
    const val PUSH = "progress_task"
    const val CANCEL = "progress_cancel"
    const val RETRY = "progress_retry"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(task: ProgressTask): String = json.encodeToString(ProgressTask.serializer(), task)

    fun task(data: String): ProgressTask? = runCatching { json.decodeFromString(ProgressTask.serializer(), data) }.getOrNull()?.takeIf { it.id.isNotBlank() }

    fun idFrame(id: String): String = buildJsonObject { put("id", JsonPrimitive(id)) }.toString()

    fun id(data: String): String? = runCatching { json.parseToJsonElement(data).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
}
