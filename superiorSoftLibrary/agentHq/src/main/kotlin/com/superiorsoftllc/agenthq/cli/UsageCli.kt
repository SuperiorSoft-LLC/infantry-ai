package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.usage.PacingRule
import com.superiorsoftllc.agenthq.usage.UsageReport
import com.superiorsoftllc.agenthq.usage.UsageSet
import com.superiorsoftllc.agenthq.usage.UsageText
import kotlinx.serialization.json.Json

/**
 * USAGE-PACE: `agenthq usage` (contract 1.119.0, read) and its write forms (1.120.0: a manual percent, an allowance
 * override, the pacing rule). Every form prints the report the daemon answers with. The read is shared with the MCP
 * `agenthq_usage` tool, which stays read-only.
 */
internal object UsageCli {
    const val USAGE = "usage: agenthq usage [show] | set <meter> <percent> [<resetsInMinutes>] | clear <meter> | " +
        "override <meter> <workers|none> [<hours>] | override <meter> clear | rule <60:3,80:2,1|on|off|default>"

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

    suspend fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable): Int {
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
