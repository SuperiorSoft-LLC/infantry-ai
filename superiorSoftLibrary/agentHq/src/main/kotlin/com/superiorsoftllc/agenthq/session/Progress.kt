package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    /** When this job began (the first report since the last finished one). */
    val startedAt: Long,
    /** The newest report; a client reads "no update for N min" against it. */
    val updatedAt: Long,
) {
    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        val MODES = setOf("percent", "steps", "bytes", "count", "indeterminate")
    }
}

/**
 * One `progress` request (the CLI, a marker line and the MCP tool all build this). [op] is
 * `percent` (percent, label), `plan` (steps or count, label), `step` (step = the one that just completed, or null for
 * "the current one"), `bytes` / `count` (done, total, label), `status` (label: indeterminate), `done`, `fail` (label =
 * the reason), `clear`, or `get` (no change, answers the current value).
 */
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
)

/** The pure rule that folds a [ProgressReport] into the current [SessionProgress]. No clock, no I/O. */
object ProgressReducer {
    const val MAX_LABEL = 120
    const val MAX_STEP = 80
    const val MAX_STEPS = 200
    val OPS = setOf("percent", "plan", "step", "bytes", "count", "status", "done", "fail", "clear", "get")

    /** The next value, or null to clear. Throws [IllegalArgumentException] for a malformed report. */
    fun apply(previous: SessionProgress?, report: ProgressReport, now: Long): SessionProgress? {
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
            "status" -> SessionProgress(
                mode = "indeterminate", label = label ?: running?.label, startedAt = startedAt, updatedAt = now,
            )
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

    /** One line for a terminal or an MCP answer: `62% step 3/5 unit tests - gate (updated 12s ago)`. */
    fun line(p: SessionProgress, now: Long): String {
        val parts = mutableListOf<String>()
        if (p.state != SessionProgress.RUNNING) parts += p.state
        p.percent?.let { parts += "${it.roundToInt()}%" }
        when (p.mode) {
            "steps" -> parts += "step ${minOf((p.stepsDone ?: 0) + if (p.state == SessionProgress.RUNNING) 1 else 0, p.stepsTotal ?: 0)}/${p.stepsTotal ?: 0}" +
                (p.step?.let { " $it" } ?: "")
            "bytes" -> parts += "${bytes(p.done ?: 0)}${p.total?.let { "/" + bytes(it) } ?: ""}" +
                (p.bytesPerSec?.let { ", ${bytes(it.toLong())}/s" } ?: "")
            "count" -> parts += "${p.done ?: 0}${p.total?.let { "/$it" } ?: ""}"
            "indeterminate" -> parts += "elapsed ${duration(now - p.startedAt)}"
        }
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
