package com.superiorsoftllc.agenthq.session


// RUNTIME-LOAD-4: the model moved to the daemon API (daemon.api.progress), unchanged; these aliases keep every core use.
typealias SessionProgress = com.superiorsoftllc.agenthq.daemon.api.progress.SessionProgress
typealias ProgressReport = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressReport

/** The pure rule that folds a [ProgressReport] into the current [SessionProgress]. No clock, no I/O. */
object ProgressReducer {
    const val MAX_LABEL = 120
    const val MAX_STEP = 80
    const val MAX_STEPS = 200
    val OPS = setOf("percent", "plan", "step", "bytes", "count", "status", "done", "fail", "clear", "output", "get")

    /** The next value, or null to clear. Throws [IllegalArgumentException] for a malformed report. */
    fun apply(
        previous: SessionProgress?, report: ProgressReport, now: Long,
        /** The daemon's learned estimate (ms) for a label, when it has one. */
        learned: (String) -> Long? = { null },
    ): SessionProgress? {
        require(report.op in OPS) { "unknown progress op '${report.op.take(40)}' (${OPS.joinToString(", ")})" }
        if (report.op == "get") return previous
        // TODO-PROGRESS-1: a bar derived from the to-do list is not the agent's job; its own report starts fresh over it.
        val own = previous?.takeIf { it.origin == null }
        val running = own?.takeIf { it.state == SessionProgress.RUNNING }
        if (report.op == "output") return running?.copy(lastOutputAt = now) ?: previous
        val next = fold(own, report, now)
        if (next == null || report.op !in STAMPED) return next
        return stamp(running, next, report, now, learned)
    }

    private val STAMPED = setOf("percent", "plan", "step", "bytes", "count", "status")

    /** What a job's current stretch is called: the running step, else the label. The learned estimates key on it. */
    fun key(p: SessionProgress): String? =
        (if (p.mode == "steps") p.step ?: p.label else p.label)?.takeIf { it.isNotBlank() }

    private fun stamp(running: SessionProgress?, next: SessionProgress, report: ProgressReport, now: Long, learned: (String) -> Long?): SessionProgress {
        val key = key(next)
        val same = running != null && key == key(running)
        val explicit = report.estimateMs?.takeIf { it > 0 }
        val keep = same && running?.estimateMs != null
        val learnedMs = if (explicit == null && !keep && key != null) learned(key)?.takeIf { it > 0 } else null
        val (est, source) = when {
            explicit != null -> explicit to SessionProgress.SOURCE_WORKER
            keep -> running?.estimateMs to running?.estimateSource
            learnedMs != null -> learnedMs to SessionProgress.SOURCE_LEARNED
            else -> null to null
        }
        return next.copy(
            labelStartedAt = if (same) running?.labelStartedAt ?: running?.startedAt else now,
            estimateMs = est, estimateSource = source, lastOutputAt = now, estimated = if (est != null) true else next.estimated,
        )
    }

    /**
     * The label that just ended and how long it ran, for the learned medians: [previous] was running a labelled stretch
     * and [next] moved to another label or finished it. A failed stretch teaches nothing; under a second is noise.
     */
    fun finished(previous: SessionProgress?, next: SessionProgress?, now: Long): Pair<String, Long>? {
        if (previous == null || next == null || previous.state != SessionProgress.RUNNING) return null
        if (next.state == SessionProgress.FAILED) return null
        val key = key(previous) ?: return null
        if (next.state != SessionProgress.DONE && key(next) == key) return null
        val ms = now - (previous.labelStartedAt ?: previous.startedAt)
        return if (ms >= MIN_LEARN_MS) key to ms else null
    }

    const val MIN_LEARN_MS = 1_000L

    /** A pace measured over less than this is noise: no "time left" is extrapolated from it. */
    const val MIN_PACE_MS = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.MIN_PACE_MS

    // RUNTIME-LOAD-4: the countdown and the one-line text live in the API (daemon.api.progress.ProgressText), shared with plugins.
    fun eta(p: SessionProgress, now: Long) = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.eta(p, now)
    fun countdownText(e: com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.Eta) = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.countdownText(e)
    fun coarse(ms: Long, up: Boolean) = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.coarse(ms, up)

    private fun fold(previous: SessionProgress?, report: ProgressReport, now: Long): SessionProgress? {
        require(report.op in OPS) { "unknown progress op '${report.op.take(40)}' (${OPS.joinToString(", ")})" }
        val label = report.label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
        // A job that finished (or never existed) starts its clock again on the next report.
        val running = previous?.takeIf { it.state == SessionProgress.RUNNING }
        val startedAt = running?.startedAt ?: now
        return when (report.op) {
            "get" -> previous
            "clear" -> null
            "percent" -> {
                val p = requireNotNull(report.percent) { "percent expects a number 0-100" }
                require(p.isFinite()) { "percent expects a number 0-100" }
                SessionProgress(
                    mode = "percent", percent = p.coerceIn(0.0, 100.0),
                    label = label ?: running?.label, startedAt = startedAt, updatedAt = now,
                )
            }
            "status" -> {
                // PROGRESS-S5: an estimated job stays estimated across its own status updates; the daemon fills estimateMs.
                val keep = running?.takeIf { it.mode == "indeterminate" && it.estimated == true }
                val estimated = report.estimate == true || keep != null
                val nextLabel = label ?: running?.label
                if (report.estimate == true) require(nextLabel != null) { "an estimate needs a label (past runs are remembered by it)" }
                SessionProgress(
                    mode = "indeterminate", label = nextLabel, startedAt = startedAt, updatedAt = now,
                    estimated = if (estimated) true else null,
                    estimateMs = keep?.takeIf { it.label == nextLabel }?.estimateMs,
                )
            }
            "plan" -> {
                val names = report.steps.map { it.trim().take(MAX_STEP) }.filter { it.isNotEmpty() }.take(MAX_STEPS)
                val total = if (names.isNotEmpty()) names.size else requireNotNull(report.count?.takeIf { it > 0 }) {
                    "plan expects step names or a positive count"
                }.coerceAtMost(10_000)
                steps(names, total, 0, label ?: running?.label, startedAt, now)
            }
            "step" -> {
                val base = running?.takeIf { it.mode == "steps" }
                val name = report.step?.trim()?.take(MAX_STEP)?.takeIf { it.isNotEmpty() }
                var names = base?.steps ?: emptyList()
                var total = base?.stepsTotal ?: 0
                var doneCount = base?.stepsDone ?: 0
                val at = name?.let { names.indexOf(it) } ?: -1
                when {
                    at >= 0 -> doneCount = maxOf(doneCount, at + 1)
                    // Unnamed, or a name of a bare-count plan: the current step completed.
                    (name == null || names.isEmpty()) && doneCount < total -> doneCount++
                    // A step the plan did not name: it is added where the job stands, already complete.
                    name != null && names.size < MAX_STEPS && (names.isNotEmpty() || total == 0) -> {
                        names = names.take(doneCount) + name + names.drop(doneCount)
                        total = maxOf(total + 1, names.size)
                        doneCount++
                    }
                    // More steps completed than planned: the plan grows by one.
                    else -> {
                        total = maxOf(total, doneCount + 1)
                        doneCount++
                    }
                }
                steps(names, total, doneCount.coerceAtMost(total), label ?: base?.label, base?.startedAt ?: startedAt, now)
            }
            "bytes", "count" -> {
                val done = requireNotNull(report.done?.takeIf { it >= 0 }) { "${report.op} expects done >= 0" }
                val total = report.total?.takeIf { it > 0 }
                val same = running?.takeIf { it.mode == report.op }
                val speed = if (report.op != "bytes") null else bytesPerSec(same, done, now)
                SessionProgress(
                    mode = report.op,
                    percent = total?.let { (done.toDouble() * 100.0 / it).coerceIn(0.0, 100.0) },
                    label = label ?: same?.label, done = done, total = total, bytesPerSec = speed,
                    startedAt = startedAt, updatedAt = now,
                )
            }
            "done" -> (previous ?: SessionProgress(mode = "indeterminate", startedAt = now, updatedAt = now)).let { p ->
                p.copy(
                    state = SessionProgress.DONE, updatedAt = now, label = label ?: p.label,
                    percent = if (p.mode == "indeterminate") p.percent else 100.0,
                    stepsDone = p.stepsTotal ?: p.stepsDone, step = if (p.mode == "steps") null else p.step,
                    done = if (p.total != null) p.total else p.done,
                )
            }
            "fail" -> (previous ?: SessionProgress(mode = "indeterminate", startedAt = now, updatedAt = now))
                .copy(state = SessionProgress.FAILED, updatedAt = now, label = label ?: previous?.label)
            else -> previous
        }
    }

    private fun steps(names: List<String>, total: Int, done: Int, label: String?, startedAt: Long, now: Long) = SessionProgress(
        mode = "steps", steps = names, stepsTotal = total, stepsDone = done, step = names.getOrNull(done),
        percent = if (total > 0) done * 100.0 / total else 0.0, label = label, startedAt = startedAt, updatedAt = now,
    )

    /** Exponentially smoothed speed over successive `bytes` reports; null until two reports are apart in time. */
    private fun bytesPerSec(previous: SessionProgress?, done: Long, now: Long): Double? {
        val prevDone = previous?.done ?: return null
        val dt = (now - previous.updatedAt) / 1000.0
        if (dt <= 0.0 || done < prevDone) return previous.bytesPerSec
        val instant = (done - prevDone) / dt
        return previous.bytesPerSec?.let { it * 0.7 + instant * 0.3 } ?: instant
    }

    fun line(p: SessionProgress, now: Long): String = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.line(p, now)
    fun bytes(n: Long): String = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.bytes(n)
    fun duration(ms: Long): String = com.superiorsoftllc.agenthq.daemon.api.progress.ProgressText.duration(ms)
}

/**
 * TODO-PROGRESS-1 (contract 1.128.0): the progress bar the agent's own to-do list drives, when the agent reports nothing
 * itself. Pure. Precedence, highest first: a RUNNING report of the agent's own (`agenthq run` markers, `agenthq_progress`),
 * then this, then the sleep hint. A finished own report gives the bar back at the next list change.
 */
object TodoProgress {
    /** N of M: how many items are done, how many there are, and the one being worked on (else the first open one). */
    data class Counts(val done: Long, val total: Long, val current: String?) {
        val finished: Boolean get() = total > 0 && done == total
    }

    /** THE one N-of-M rule for a to-do list ([items] = text to status `pending|in_progress|done`). */
    fun of(items: List<Pair<String, String>>): Counts = Counts(
        done = items.count { it.second == "done" }.toLong(),
        total = items.size.toLong(),
        current = items.firstOrNull { it.second == "in_progress" }?.first ?: items.firstOrNull { it.second != "done" }?.first,
    )

    /**
     * The next value of a session's progress after the agent wrote [items] (the FULL list), or [previous] unchanged when
     * an own running report holds the bar.
     */
    fun apply(previous: SessionProgress?, items: List<Pair<String, String>>, now: Long): SessionProgress? {
        val ownRunning = previous != null && previous.origin == null && previous.state == SessionProgress.RUNNING &&
            previous.estimateSource != SessionProgress.SOURCE_SLEEP
        if (ownRunning) return previous
        val mine = previous?.takeIf { it.origin == SessionProgress.ORIGIN_TODO }
        if (items.isEmpty()) return if (mine != null) null else previous
        val c = of(items)
        val restart = mine == null || mine.state != SessionProgress.RUNNING
        return SessionProgress(
            mode = "count", state = if (c.finished) SessionProgress.DONE else SessionProgress.RUNNING,
            percent = c.done * 100.0 / c.total, label = SessionProgress.TODO_LABEL,
            step = if (c.finished) null else c.current?.take(ProgressReducer.MAX_STEP),
            done = c.done, total = c.total,
            startedAt = if (restart) now else mine!!.startedAt, updatedAt = now, lastOutputAt = now,
            origin = SessionProgress.ORIGIN_TODO,
        )
    }
}
