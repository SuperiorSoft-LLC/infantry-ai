package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.ProgressView
import kotlin.math.roundToInt

/** Contract 1.117.0: how a progress bar reads. [Stale] = running, but no report for longer than the stale setting. */
enum class ProgressTone { Running, Stale, Done, Failed }

/**
 * What one [ProgressView] renders as: the bar fill ([fraction], null = indeterminate shimmer), the headline ("62%
 * step 3/5 unit tests"), the detail line (label, speed, elapsed, "no update for 6 min") and its tone. Pure, so the rule
 * is tested without a window.
 */
data class ProgressDisplay(
    val fraction: Float?,
    val headline: String,
    val detail: String,
    val tone: ProgressTone,
)

/** Default "no update for N min" threshold, in minutes; the Configuration dialog changes it. */
const val PROGRESS_STALE_DEFAULT_MIN = 5
val PROGRESS_STALE_CHOICES = listOf(2, 5, 10, 15, 30)
const val PROGRESS_STALE_PREF_KEY = "progressStaleMin"

fun progressDisplay(p: ProgressView, now: Long, staleMin: Int = PROGRESS_STALE_DEFAULT_MIN): ProgressDisplay {
    val running = p.state == "running"
    val quietMs = (now - p.updatedAt).coerceAtLeast(0)
    val tone = when {
        p.state == "failed" -> ProgressTone.Failed
        p.state == "done" -> ProgressTone.Done
        quietMs > staleMin.coerceAtLeast(1) * 60_000L -> ProgressTone.Stale
        else -> ProgressTone.Running
    }
    val fraction = if (p.mode == "indeterminate" || p.percent == null) {
        if (running) null else 1f
    } else (p.percent / 100.0).toFloat().coerceIn(0f, 1f)
    val head = buildList {
        if (p.mode != "indeterminate") p.percent?.let { add("${it.roundToInt()}%") }
        when (p.mode) {
            "steps" -> {
                val total = p.stepsTotal ?: 0
                val at = minOf((p.stepsDone ?: 0) + if (running) 1 else 0, total)
                add("step $at/$total" + (p.step?.let { " $it" } ?: ""))
            }
            "bytes" -> add(formatBytes(p.done ?: 0) + (p.total?.let { " / " + formatBytes(it) } ?: ""))
            "count" -> add("${p.done ?: 0}" + (p.total?.let { " / $it" } ?: ""))
        }
        when (p.state) {
            "done" -> add("done")
            "failed" -> add("failed")
        }
    }.joinToString(" · ").ifEmpty { p.label ?: "working" }
    val detail = buildList {
        if (head != p.label) p.label?.let { add(it) }
        if (p.mode == "bytes" && running) p.bytesPerSec?.let { add(formatBytes(it.toLong()) + "/s") }
        if (running) add(formatElapsed(now - p.startedAt))
        if (tone == ProgressTone.Stale) add("no update for ${quietMs / 60_000} min")
    }.joinToString(" · ")
    return ProgressDisplay(fraction, head, detail, tone)
}

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> oneDecimal(n / (1L shl 30).toDouble()) + " GB"
    n >= 1L shl 20 -> oneDecimal(n / (1L shl 20).toDouble()) + " MB"
    n >= 1L shl 10 -> oneDecimal(n / (1L shl 10).toDouble()) + " KB"
    else -> "$n B"
}

private fun oneDecimal(v: Double): String {
    val tenths = (v * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** "42s", "12m 05s", "1h 03m". */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
        else -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
    }
}
