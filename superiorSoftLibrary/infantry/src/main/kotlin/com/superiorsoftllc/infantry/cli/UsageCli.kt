// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.daemon.api.usage.PacingRule
import com.superiorsoftllc.infantry.daemon.api.usage.UsageReport
import com.superiorsoftllc.infantry.daemon.api.usage.UsageSet
import com.superiorsoftllc.infantry.daemon.api.usage.UsageText
import kotlinx.serialization.json.Json

/**
 * USAGE-PACE: `infantry usage` (contract 1.119.0, read) and its write forms (1.121.0: a manual percent, an allowance
 * override, the pacing rule). Every form prints the report the daemon answers with. The read is shared with the MCP
 * `infantry_usage` tool, which stays read-only.
 */
internal object UsageCli {
    const val USAGE = "usage: infantry usage [show] | set <meter> <percent> [<resetsInMinutes>] | clear <meter> | " +
        "override <meter> <workers|none> [<hours>] | override <meter> clear | rule <60:3,80:2,1|on|off|default> | " +
        "history [<meter>] [<window label>] [<90m|24h|7d>]"

    private val json = Json { ignoreUnknownKeys = true }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: List<String>): String? {
        val verb = args.firstOrNull()?.lowercase() ?: return null
        val ok = when (verb) {
            "show" -> args.size == 1
            "set" -> args.size in 3..4 && args[2].toDoubleOrNull() != null && (args.size == 3 || args[3].toLongOrNull() != null)
            "clear" -> args.size == 2
            "override" -> args.size in 3..4 &&
                (args[2].lowercase() in setOf("none", "clear") || args[2].toIntOrNull() != null) &&
                (args.size == 3 || (args[2].lowercase() != "clear" && args[3].toDoubleOrNull() != null))
            "rule" -> args.size == 2
            "history" -> historyQuery(args.drop(1)) != null
            else -> false
        }
        return if (ok) null else USAGE
    }

    suspend fun report(stream: FrameStream): UsageReport {
        val answer = stream.request(Envelope(Envelope.USAGE_GET, ""), Envelope.USAGE_REPORT, "usage_get", idempotent = true, timeoutMs = 45_000L)
        return json.decodeFromString(UsageReport.serializer(), answer.data)
    }

    suspend fun apply(stream: FrameStream, set: UsageSet): UsageReport {
        val answer = stream.request(Envelope(Envelope.USAGE_SET, UsageSet.encode(set)), Envelope.USAGE_REPORT, "usage_set", idempotent = true)
        return json.decodeFromString(UsageReport.serializer(), answer.data)
    }

    /** Turns a validated `usage` verb into a [UsageSet]; null = just show. Reads the report only when it needs to. */
    suspend fun toSet(stream: FrameStream, args: List<String>, now: Long = System.currentTimeMillis()): UsageSet? {
        val verb = args.firstOrNull()?.lowercase() ?: return null
        return when (verb) {
            "set" -> UsageSet(
                meter = args[1],
                manualPercent = args[2].toDouble(),
                manualResetsAt = args.getOrNull(3)?.toLong()?.let { now + it * 60_000L },
            )
            "clear" -> UsageSet(meter = args[1], clearManual = true)
            "override" -> when (val w = args[2].lowercase()) {
                "clear" -> UsageSet(meter = args[1], clearOverride = true)
                else -> {
                    val hours = args.getOrNull(3)?.toDouble()
                    // No duration: until the meter's window resets, else 2 h.
                    val until = if (hours != null) now + (hours * 3_600_000L).toLong() else
                        report(stream).meters.firstOrNull { it.meter == args[1].lowercase() }?.resetsAt?.takeIf { it > now }
                            ?: (now + DEFAULT_OVERRIDE_MS)
                    UsageSet(meter = args[1], overrideWorkers = if (w == "none") null else w.toInt(), overrideUntil = until)
                }
            }
            "rule" -> {
                val current = if (args[1].lowercase() in setOf("on", "off")) report(stream).rule else PacingRule.DEFAULT
                UsageSet(rule = UsageText.parseRule(args[1], current))
            }
            else -> null
        }
    }

    private val SINCE = Regex("""\d+(\.\d+)?[mhdMHD]""")

    /**
     * PACE-TREND-1: `usage history [<meter>] [<window label>] [<since: 90m|24h|7d>]` -> the `pace_get` JSON query; null = malformed.
     * The first word that is not a duration is the meter, the second the window label. The answer comes from the optional
     * Infantry_Pace daemon plugin; without it the daemon answers an error frame.
     */
    fun historyQuery(words: List<String>): String? {
        val o = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val names = mutableListOf<String>()
        for (w in words) {
            if (SINCE.matches(w)) {
                if ("sinceHours" in o) return null
                val n = w.dropLast(1).toDouble()
                o["sinceHours"] = kotlinx.serialization.json.JsonPrimitive(when (w.last().lowercaseChar()) { 'm' -> n / 60.0; 'h' -> n; else -> n * 24.0 })
            } else names += w
        }
        if (names.size > 2) return null
        names.getOrNull(0)?.let { o["provider"] = kotlinx.serialization.json.JsonPrimitive(it) }
        names.getOrNull(1)?.let { o["window"] = kotlinx.serialization.json.JsonPrimitive(it) }
        return kotlinx.serialization.json.JsonObject(o).toString()
    }

    private suspend fun history(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable): Int {
        val query = historyQuery(args.drop(1)) ?: run { err.appendLine("error: $USAGE"); return 2 }
        return try {
            val answer = stream.request(Envelope("pace_get", query), "pace_report", "pace_get", idempotent = true, timeoutMs = 45_000L)
            val text = json.parseToJsonElement(answer.data).let { (it as kotlinx.serialization.json.JsonObject)["text"] }
            out.appendLine((text as? kotlinx.serialization.json.JsonPrimitive)?.content ?: answer.data)
            0
        } catch (e: Exception) {
            err.appendLine("error: ${e.message ?: "pace_get failed"} (is the Infantry_Pace plugin installed and enabled?)")
            1
        }
    }

    suspend fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable): Int {
        if (args.firstOrNull()?.lowercase() == "history") return history(stream, args, out, err)
        val set = try {
            toSet(stream, args)
        } catch (e: IllegalArgumentException) {
            err.appendLine("error: ${e.message}")
            return 2
        }
        val report = if (set == null) report(stream) else apply(stream, set)
        out.appendLine(UsageText.render(report))
        return 0
    }

    private const val DEFAULT_OVERRIDE_MS = 2 * 3_600_000L
}
