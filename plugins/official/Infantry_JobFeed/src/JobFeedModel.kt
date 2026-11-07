// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** JOBFEED-1: the box's settings keys (module.json `config`, node [NODE]) and the slot it fills. */
object JobFeedKeys {
    const val SLOT = "chat.jobfeed"
    const val SECTION_ID = "infantry.jobfeed"
    const val NODE = "infantry.jobfeed"
    const val MAX_LINES = "jobfeed.maxLines"
    const val UPDATES_PER_SECOND = "jobfeed.maxUpdatesPerSecond"
    const val LOW_POWER = "jobfeed.lowPower"
    const val AUTO_COLLAPSE = "jobfeed.autoCollapseUnfocused"
    const val OPEN_BY_DEFAULT = "jobfeed.openByDefault"
    const val FAILURE_MARKERS = "jobfeed.failureMarkers"
    const val QUIET_SECONDS = "jobfeed.quietNoticeSeconds"
}

/** The defaults are chosen for weak machines; the same numbers are in module.json. */
object JobFeedDefaults {
    const val MAX_LINES = 300
    const val UPDATES_PER_SECOND = 4
    const val FAILURE_MARKERS = "FAILED|error:|Exception|BUILD FAILED|FAILURE"
    const val QUIET_SECONDS = 300
    /** The "low power" preset: one update a second, a hundred lines. */
    const val LOW_POWER_UPDATES = 1
    const val LOW_POWER_LINES = 100
    /** What one read asks the daemon for (the daemon caps it again by `limits.jobfeed.maxReadBytes`). */
    const val READ_BYTES = 16384
}

/** The box's settings as read from the resolved config; every one can be changed, and the whole box is off with the plugin. */
data class JobFeedSettings(
    val maxLines: Int = JobFeedDefaults.MAX_LINES,
    val updatesPerSecond: Int = JobFeedDefaults.UPDATES_PER_SECOND,
    val lowPower: Boolean = false,
    val autoCollapseUnfocused: Boolean = true,
    val openByDefault: Boolean = false,
    val failureMarkers: String = JobFeedDefaults.FAILURE_MARKERS,
    /** 0 = never show the quiet notice. */
    val quietSeconds: Int = JobFeedDefaults.QUIET_SECONDS,
) {
    val effectiveUpdates: Int get() = (if (lowPower) minOf(updatesPerSecond, JobFeedDefaults.LOW_POWER_UPDATES) else updatesPerSecond).coerceAtLeast(1)
    val effectiveMaxLines: Int get() = (if (lowPower) minOf(maxLines, JobFeedDefaults.LOW_POWER_LINES) else maxLines).coerceAtLeast(1)
    /** Milliseconds between two reads while the box is open and following. */
    val pollMs: Long get() = 1000L / effectiveUpdates
    val markers: List<Regex> get() = failureMarkers.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
}

/** The newest [maxLines] lines, nothing else is kept. */
class LineBuffer(private val maxLines: () -> Int) {
    private val lines = ArrayDeque<String>()

    /** Appends the lines of [text] (a trailing newline adds no empty line). */
    fun append(text: String) {
        if (text.isEmpty()) return
        val parts = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        parts.forEach { lines.addLast(it) }
        val cap = maxLines().coerceAtLeast(1)
        while (lines.size > cap) lines.removeFirst()
    }

    fun clear() = lines.clear()
    fun snapshot(): List<String> = lines.toList()
    val size: Int get() = lines.size
}

fun isFailureLine(line: String, markers: List<Regex>): Boolean = markers.any { it.containsMatchIn(line) }

/** What one `read` route answer says. [known] false = the daemon does not know the job (any more). */
data class JobFeedChunk(
    val known: Boolean, val text: String, val next: Long, val size: Long, val reset: Boolean, val throttled: Boolean,
    val running: Boolean, val exitCode: Int?, val label: String, val startedAt: Long,
)

object JobFeedWire {
    const val READ_ROUTE = "POST /plugins/Infantry_JobFeed/read"

    fun readBody(sessionId: String, jobId: String, from: Long, maxBytes: Int = JobFeedDefaults.READ_BYTES): String =
        JsonObject(mapOf(
            "sessionId" to JsonPrimitive(sessionId), "jobId" to JsonPrimitive(jobId),
            "from" to JsonPrimitive(from), "maxBytes" to JsonPrimitive(maxBytes),
        )).toString()

    fun parseChunk(body: String): JobFeedChunk? {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
        return JobFeedChunk(
            known = b("known") ?: false, text = s("text").orEmpty(), next = l("next") ?: 0L, size = l("size") ?: 0L,
            reset = b("reset") ?: false, throttled = b("throttled") ?: false, running = b("running") ?: false,
            exitCode = (o["exitCode"] as? JsonPrimitive)?.intOrNull, label = s("label").orEmpty(), startedAt = l("startedAt") ?: 0L,
        )
    }
}

/** "running", "done exit 0" or "failed exit N" (exit unknown: "ended"). */
fun jobStatusText(running: Boolean, exitCode: Int?): String = when {
    running -> "running"
    exitCode == null -> "ended"
    exitCode == 0 -> "done exit 0"
    else -> "failed exit $exitCode"
}

fun elapsedText(startedAt: Long, now: Long): String {
    val s = ((now - startedAt) / 1000L).coerceAtLeast(0L)
    return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

/** The neutral notice when a running job printed nothing for [quietSeconds] (0 = never). */
fun quietNotice(running: Boolean, lastOutputAt: Long, now: Long, quietSeconds: Int): String? {
    if (!running || quietSeconds <= 0) return null
    val quiet = (now - lastOutputAt) / 1000L
    return if (quiet >= quietSeconds) "No output for ${quiet / 60} min. The command may be working quietly, waiting, or stuck." else null
}

/** Epoch millis, for the elapsed time and the quiet notice (the only clock the box reads, and only while it is open). */
fun platformNow(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
