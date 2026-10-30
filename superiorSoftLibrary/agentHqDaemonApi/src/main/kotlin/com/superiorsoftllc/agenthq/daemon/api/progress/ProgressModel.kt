package com.superiorsoftllc.agenthq.daemon.api.progress

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

// RUNTIME-LOAD-4 (DaemonApi 1.4.0, additive): the session progress model and its one-line text, moved here from the daemon's
// `session` package (which keeps type aliases) so `agenthq progress`, the daemon and Infantry_Agents' `agenthq_progress` tool
// render one way. Pure: no clock, no I/O. Wire shape unchanged (contract 1.117.0 and its additive steps).

/**
 * Contract 1.117.0 (ticket-1371d44b): how far along an agent's long job is, as the AGENT measured it. Memory only in
 * effect (a daemon start clears it), carried on the session record so the `session` push and the `sessions` list both
 * show it; omitted when the agent never reported.
 *
 * [mode] is how the agent measures: `percent`, `steps` (checkpoints), `bytes` (a transfer), `count` (items done of a
 * total) or `indeterminate` (a label and the elapsed time, no number). [percent] is derived by the daemon for every
 * mode but `indeterminate`, where it is null. Never estimated: a number here is a measurement.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionProgress(
    val mode: String,
    /** `running`, `done` or `failed`. */
    val state: String = RUNNING,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val percent: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
    /** `steps`: the planned step names, in order (empty when the plan is a bare count). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val steps: List<String> = emptyList(),
    /** `steps`: how many steps are planned ([steps] size, or the bare count). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stepsTotal: Int? = null,
    /** `steps`: how many are complete. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stepsDone: Int? = null,
    /** `steps`: the step now running (the first not complete), when it has a name. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val step: String? = null,
    /** `bytes` / `count`: units done and the total (null = unknown total). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val done: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val total: Long? = null,
    /** `bytes`: smoothed transfer speed, derived by the daemon from successive reports. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bytesPerSec: Double? = null,
    /**
     * PROGRESS-S5, opt-in only (`--estimate`): true on an `indeterminate` job whose agent asked for an estimate. The
     * job has no measurement; a client may show [estimateMs] as "~12 min left (estimated)" on a hatched bar, never as
     * [percent]. Omitted otherwise. Any measured report (percent, steps, bytes, count) drops it: a measurement wins.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val estimated: Boolean? = null,
    /** PROGRESS-S5: how long this label's past runs in the project took (median of the last few), in ms; null = no history yet. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val estimateMs: Long? = null,
    /** When this job began (the first report since the last finished one). */
    val startedAt: Long,
    /** The newest report; a client reads "no update for N min" against it. */
    val updatedAt: Long,
    /**
     * Contract 1.123.0 (additive): when the current label / step began (the clock a time estimate counts from), the
     * time the current label is expected to take and where that number came from (`worker` = the agent said so,
     * `learned` = the daemon's rolling median for this project and label; null = no estimate yet), and when output was
     * last seen (a report or a `--watch` heartbeat), the "alive" signal of a job with no number at all.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val labelStartedAt: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val estimateSource: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastOutputAt: Long? = null,
    /**
     * Contract 1.128.0 (TODO-PROGRESS-1, additive): who set this value when the agent did not report it itself. `todo` =
     * derived by the daemon from the agent's own to-do list (mode `count`, done of total items). Omitted = the agent's
     * report (or the sleep hint, which says so in [estimateSource]). An agent report always replaces a `todo` value.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val origin: String? = null,
    /**
     * PROGRESS-ALL (additive): true when the session has shown no tool or event activity for the configured stale limit
     * (`limits.progress.staleMinutes`); the daemon sets it on any running ticket session, reported or not. `origin` =
     * `auto` marks a bar the daemon computed itself (an estimate, from past tickets of the same weight).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stale: Boolean? = null,
) {
    companion object {
        const val ORIGIN_TODO = "todo"
        const val TODO_LABEL = "to-do"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val SOURCE_WORKER = "worker"
        const val SOURCE_LEARNED = "learned"
        /** An agent `sleep N` read from its own tool call ([WaitHint]); the countdown reads "waiting 1m 20s". */
        const val SOURCE_SLEEP = "sleep"
        const val WAIT_LABEL = "waiting"
        val MODES = setOf("percent", "steps", "bytes", "count", "indeterminate")
    }
}

/**
 * One `progress` request (the CLI, a marker line and the MCP tool all build this). [op] is
 * `percent` (percent, label), `plan` (steps or count, label), `step` (step = the one that just completed, or null for
 * "the current one"), `bytes` / `count` (done, total, label), `status` (label: indeterminate), `done`, `fail` (label =
 * the reason), `clear`, `output` (1.123.0: the job printed something, nothing else changed) or `get` (no change, answers the current value).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProgressReport(
    val op: String,
    val percent: Double? = null,
    val label: String? = null,
    val steps: List<String> = emptyList(),
    val count: Int? = null,
    val step: String? = null,
    val done: Long? = null,
    val total: Long? = null,
    /** PROGRESS-S5: on `status`, opt in to an estimate from past runs of the same label (never the default). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val estimate: Boolean? = null,
    /** Contract 1.123.0: how long the current label / step should take, in ms (the worker's own estimate). */
    val estimateMs: Long? = null,
)

/** How a progress value reads as text and as a countdown (pure; [now] is the caller's clock). */
object ProgressText {
    /** A pace measured over less than this is noise: no "time left" is extrapolated from it. */
    const val MIN_PACE_MS = 2_000L

    /** The countdown of a running job. [percent] is the meter fill; [remainingMs] / [overMs] / [noEstimate] its words. */
    data class Eta(val percent: Double?, val remainingMs: Long?, val overMs: Long?, val noEstimate: Boolean, val estimated: Boolean)

    /**
     * The rule that keeps the meter honest: a counter's percent always wins (its time left is extrapolated from the pace
     * so far); else a time estimate (the worker's, else the learned median) gives percent and time left, or "over
     * estimate by" once it is exceeded (the fill stops at 99%); else nothing: [Eta.noEstimate] for a job that is only a label.
     */
    fun eta(p: SessionProgress, now: Long): Eta {
        if (p.state != SessionProgress.RUNNING) return Eta(p.percent, null, null, false, false)
        val pct = p.percent
        if (p.mode != "indeterminate" && pct != null) {
            val elapsed = (now - p.startedAt).coerceAtLeast(0)
            val left = if (pct > 0.0 && pct < 100.0 && elapsed >= MIN_PACE_MS) (elapsed * (100.0 - pct) / pct).toLong() else null
            return Eta(pct, left, null, false, false)
        }
        val est = p.estimateMs ?: return Eta(null, null, null, p.mode == "indeterminate", false)
        val elapsed = (now - (p.labelStartedAt ?: p.startedAt)).coerceAtLeast(0)
        return if (elapsed <= est) Eta(minOf(99.0, elapsed * 100.0 / est), est - elapsed, null, false, true)
        else Eta(99.0, null, elapsed - est, false, true)
    }

    /** "~2m left", "over estimate by 1m", "no estimate yet"; null when the job has no countdown to speak of. */
    fun countdownText(e: Eta): String? = when {
        e.remainingMs != null -> "~${coarse(e.remainingMs, true)} left"
        e.overMs != null -> "over estimate by ${coarse(e.overMs, false)}"
        e.noEstimate -> "no estimate yet"
        else -> null
    }

    /** 45s, 2m, 1h05m: whole minutes rounded up when counting down, down when counting over. */
    fun coarse(ms: Long, up: Boolean): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${(if (up) (s + 59) / 60 else s / 60)}m"
            else -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }

    /** One line for a terminal or an MCP answer: `62% step 3/5 unit tests - gate (updated 12s ago)`. */
    fun line(p: SessionProgress, now: Long): String {
        val parts = mutableListOf<String>()
        if (p.state != SessionProgress.RUNNING) parts += p.state
        val eta = eta(p, now)
        eta.percent?.takeIf { eta.overMs == null }?.let { parts += "${it.roundToInt()}%" }
        when (p.mode) {
            "steps" -> parts += "step ${minOf((p.stepsDone ?: 0) + if (p.state == SessionProgress.RUNNING) 1 else 0, p.stepsTotal ?: 0)}/${p.stepsTotal ?: 0}" +
                (p.step?.let { " $it" } ?: "")
            "bytes" -> parts += "${bytes(p.done ?: 0)}${p.total?.let { "/" + bytes(it) } ?: ""}" +
                (p.bytesPerSec?.let { ", ${bytes(it.toLong())}/s" } ?: "")
            "count" -> parts += "${p.done ?: 0}${p.total?.let { "/$it" } ?: ""}" + (p.step?.let { " $it" } ?: "")
        }
        if (p.state == SessionProgress.RUNNING) countdownText(eta)?.let { parts += "· $it" }
        // No number and no estimate: say the job is alive (last output), never a bare elapsed counter.
        if (p.state == SessionProgress.RUNNING && eta.noEstimate) parts += "· last output ${duration(now - (p.lastOutputAt ?: p.updatedAt))} ago"
        p.label?.let { parts += "- $it" }
        parts += "(updated ${duration(now - p.updatedAt)} ago)"
        return parts.joinToString(" ")
    }

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> "%.1f KB".format(n / (1L shl 10).toDouble())
        else -> "$n B"
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }
}
