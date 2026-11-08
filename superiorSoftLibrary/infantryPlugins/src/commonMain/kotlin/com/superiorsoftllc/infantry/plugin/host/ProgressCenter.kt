// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.Progress
import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import com.superiorsoftllc.infantry.plugin.api.ProgressPresenter
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import com.superiorsoftllc.infantry.plugin.api.ProgressState
import com.superiorsoftllc.infantry.plugin.api.ProgressTask
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the core progress system, the one implementation of [Progress] that
 * the daemon and every client use (common code: no JVM API, so wasm and Android share it).
 *
 * - Tasks live in one atomic map; a change is published to the active presenter and to every observer.
 * - The active presenter is the one [presenterSetting] names, or (blank) the last one registered with [present], else
 *   the core default ([setDefault]). A presenter that throws is reported to [onFault] and the core default shows that
 *   change, so a broken plugin never hides progress.
 * - Amount-only changes are published at most every [minIntervalMs] per task (a download reports every buffer); a step,
 *   state or error change always is.
 * - [onFailed] hears each task that ends FAILED (the host raises a notification there).
 * - Finished tasks are kept, newest first, up to [keepFinished] (0 = none).
 *
 * [forSource] gives a module its own view: tasks it starts carry its id as `source`, and presenters it registers are its.
 */
@OptIn(ExperimentalAtomicApi::class)
class ProgressCenter(
    private val clock: () -> Long,
    private val presenterSetting: () -> String = { "" },
    private val keepFinished: () -> Int = { DEFAULT_KEEP_FINISHED },
    private val minIntervalMs: () -> Long = { DEFAULT_MIN_INTERVAL_MS },
    private val onFault: (presenterId: String, error: Throwable) -> Unit = { _, _ -> },
    private val onFailed: (ProgressTask) -> Unit = {},
) {
    private class Entry(
        val task: ProgressTask,
        val retry: (() -> Unit)?,
        val cancelRequested: Boolean = false,
        val cancelListeners: List<() -> Unit> = emptyList(),
        val publishedAt: Long = 0,
    )

    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())
    private val finishedOrder = AtomicReference<List<String>>(emptyList())
    private val observers = AtomicReference<List<(ProgressTask) -> Unit>>(emptyList())
    private val presenters = AtomicReference<List<Pair<String, ProgressPresenter>>>(emptyList())
    private val defaultPresenter = AtomicReference<ProgressPresenter?>(null)
    private val seq = AtomicLong(0)

    /** The core default presenter (the host's own drawing / broadcast). */
    fun setDefault(presenter: ProgressPresenter?) { defaultPresenter.store(presenter) }

    /** The id of the presenter that shows changes now ("" = the core default). */
    fun activePresenterId(): String = active()?.first.orEmpty()

    /** The registered presenter ids, in registration order. */
    fun presenterIds(): List<String> = presenters.load().map { it.first }

    /** The view of module [source]: its tasks carry [source], its presenters are registered under it. */
    fun forSource(source: String): Progress = object : Progress {
        override fun start(spec: ProgressSpec) = this@ProgressCenter.start(source, spec)
        override fun tasks() = this@ProgressCenter.tasks()
        override fun task(id: String) = this@ProgressCenter.task(id)
        override fun observe(listener: (ProgressTask) -> Unit) = this@ProgressCenter.observe(listener)
        override fun cancel(id: String) = this@ProgressCenter.cancel(id)
        override fun retry(id: String) = this@ProgressCenter.retry(id)
        override fun present(presenterId: String, presenter: ProgressPresenter) = this@ProgressCenter.present(presenterId.ifBlank { source }, presenter)
    }

    fun start(source: String, spec: ProgressSpec): ProgressHandle {
        val id = spec.id.ifBlank { "progress-${clock()}-${seq.fetchAndIncrement()}" }
        val now = clock()
        val fresh = ProgressTask(
            id = id, source = source, title = spec.title, state = ProgressState.RUNNING, total = spec.total, unit = spec.unit,
            steps = spec.steps, cancellable = spec.cancellable, retryable = spec.retry != null, startedAt = now, updatedAt = now, link = spec.link,
        )
        var started = false
        update(id) { old ->
            started = old == null || old.task.finished // one task per id: a live one is reused
            if (started) Entry(fresh, spec.retry) else old
        }
        if (started) {
            finishedOrder.update { it - id }
            publish(fresh, force = true)
        }
        return Handle(id)
    }

    fun tasks(): List<ProgressTask> {
        val all = entries.load()
        val live = all.values.map { it.task }.filter { !it.finished }.sortedBy { it.startedAt }
        val done = finishedOrder.load().mapNotNull { all[it]?.task }
        return live + done
    }

    fun task(id: String): ProgressTask? = entries.load()[id]?.task

    fun observe(listener: (ProgressTask) -> Unit): AutoCloseable {
        observers.update { it + listener }
        return AutoCloseable { observers.update { list -> list.filter { it !== listener } } }
    }

    fun present(presenterId: String, presenter: ProgressPresenter): AutoCloseable {
        presenters.update { list -> list.filter { it.first != presenterId } + (presenterId to presenter) }
        return AutoCloseable { presenters.update { list -> list.filter { it.second !== presenter } } }
    }

    fun cancel(id: String): Boolean {
        var listeners: List<() -> Unit> = emptyList()
        var asked = false
        update(id) { e ->
            asked = false
            if (e == null || e.task.finished || !e.task.cancellable || e.cancelRequested) e
            else { asked = true; listeners = e.cancelListeners; Entry(e.task, e.retry, true, emptyList(), e.publishedAt) }
        }
        if (asked) for (l in listeners) runCatching { l() }
        return asked
    }

    fun retry(id: String): Boolean {
        val e = entries.load()[id] ?: return false
        val r = e.retry?.takeIf { e.task.state == ProgressState.FAILED || e.task.state == ProgressState.CANCELLED } ?: return false
        r()
        return true
    }

    /**
     * Mirrors [task] pushed by another host (a client mirroring the daemon): stored and published as is, with [retry] /
     * [cancel] going back to where it came from.
     */
    fun mirror(task: ProgressTask, retry: (() -> Unit)? = null, cancel: (() -> Unit)? = null) {
        update(task.id) { old ->
            Entry(task, retry ?: old?.retry, old?.cancelRequested ?: false, if (cancel != null && !task.finished) listOf(cancel) else emptyList(), old?.publishedAt ?: 0)
        }
        if (task.finished) remember(task.id) else finishedOrder.update { it - task.id }
        publish(task, force = true)
    }

    /** Forgets every finished task (the "Clear" of a running-tasks list). */
    fun clearFinished() {
        val ids = finishedOrder.load().toSet()
        finishedOrder.store(emptyList())
        entries.update { m -> m.filterKeys { it !in ids } }
    }

    // ---------------------------------------------------------------- internals

    private inner class Handle(override val id: String) : ProgressHandle {
        override val task: ProgressTask get() = entries.load()[id]?.task ?: ProgressTask(id, state = ProgressState.CANCELLED)
        override val cancelRequested: Boolean get() = entries.load()[id]?.cancelRequested ?: false

        override fun step(label: String, index: Int) = change { t ->
            val i = if (index >= 0) index else t.steps.indexOf(label)
            t.copy(step = label, stepIndex = i, done = if (label != t.step) -1 else t.done, total = if (label != t.step) -1 else t.total, etaMs = -1)
        }

        override fun amount(done: Long, total: Long, unit: String) =
            change(force = false) { it.copy(done = done, total = total, unit = unit.ifBlank { it.unit }) }

        override fun eta(ms: Long) = change(force = false) { it.copy(etaMs = ms) }

        override fun done(step: String) = finish { it.copy(state = ProgressState.DONE, step = step.ifBlank { it.step }, etaMs = -1, done = if (it.total > 0) it.total else it.done) }

        override fun fail(reason: String, retryable: Boolean) =
            finish { it.copy(state = ProgressState.FAILED, error = reason, retryable = retryable && it.retryable, etaMs = -1) }

        override fun cancelled() = finish { it.copy(state = ProgressState.CANCELLED, retryable = it.retryable, etaMs = -1) }

        override fun onCancel(listener: () -> Unit): AutoCloseable {
            var now = false
            update(id) { e ->
                now = false
                when {
                    e == null || e.task.finished -> e
                    e.cancelRequested -> { now = true; e }
                    else -> Entry(e.task, e.retry, false, e.cancelListeners + listener, e.publishedAt)
                }
            }
            if (now) runCatching { listener() }
            return AutoCloseable {
                update(id) { e -> e?.let { Entry(it.task, it.retry, it.cancelRequested, it.cancelListeners.filter { l -> l !== listener }, it.publishedAt) } }
            }
        }

        private fun change(force: Boolean = true, f: (ProgressTask) -> ProgressTask) {
            var out: ProgressTask? = null
            var due = force
            val now = clock()
            update(id) { e ->
                out = null
                due = force
                if (e == null || e.task.finished) e
                else {
                    val next = f(e.task).copy(updatedAt = now)
                    if (!force && now - e.publishedAt >= minIntervalMs()) due = true
                    out = next
                    Entry(next, e.retry, e.cancelRequested, e.cancelListeners, if (due) now else e.publishedAt)
                }
            }
            out?.let { if (due) publish(it, force = true) }
        }

        private fun finish(f: (ProgressTask) -> ProgressTask) {
            var out: ProgressTask? = null
            val now = clock()
            update(id) { e ->
                out = null
                if (e == null || e.task.finished) e
                else f(e.task).copy(updatedAt = now).let { out = it; Entry(it, e.retry, e.cancelRequested, emptyList(), now) }
            }
            val t = out ?: return
            remember(id)
            publish(t, force = true)
            if (t.state == ProgressState.FAILED) runCatching { onFailed(t) }
        }
    }

    private fun remember(id: String) {
        val keep = keepFinished().coerceAtLeast(0)
        var dropped: List<String> = emptyList()
        finishedOrder.update { list ->
            val next = listOf(id) + (list - id)
            dropped = next.drop(keep)
            next.take(keep)
        }
        if (dropped.isNotEmpty()) entries.update { m -> m.filterKeys { it !in dropped || m[it]?.task?.finished == false } }
    }

    private fun active(): Pair<String, ProgressPresenter>? {
        val list = presenters.load()
        val wanted = presenterSetting().trim()
        return if (wanted.isEmpty()) list.lastOrNull() else list.firstOrNull { it.first == wanted }
    }

    private fun publish(task: ProgressTask, force: Boolean) {
        if (!force) return
        val chosen = active()
        val shown = chosen != null && runCatching { chosen.second.show(task) }.onFailure { onFault(chosen.first, it) }.isSuccess
        if (!shown) defaultPresenter.load()?.let { d -> runCatching { d.show(task) } }
        for (o in observers.load()) runCatching { o(task) }
    }

    private fun update(id: String, f: (Entry?) -> Entry?) {
        while (true) {
            val old = entries.load()
            val e = f(old[id])
            val next = if (e == null) old - id else old + (id to e)
            if (entries.compareAndSet(old, next)) return
        }
    }

    private fun <T> AtomicReference<T>.update(f: (T) -> T) {
        while (true) {
            val old = load()
            if (compareAndSet(old, f(old))) return
        }
    }

    companion object {
        /** The default of `limits.progress.keepFinished` where the host passes none. */
        const val DEFAULT_KEEP_FINISHED = 20
        /** The default of `limits.progress.minIntervalMs` where the host passes none. */
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}
