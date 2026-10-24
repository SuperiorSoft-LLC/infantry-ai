package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.usage.UsageReport
import com.superiorsoftllc.agenthq.usage.UsageText
import kotlinx.serialization.json.Json

/**
 * USAGE-PACE S1 (contract 1.119.0): `agenthq usage [show]`, read-only, shared with the MCP `agenthq_usage` tool. Prints
 * the `usage_report` the daemon answers a `usage_get` with.
 */
internal object UsageCli {
    const val USAGE = "usage: agenthq usage [show]"

    private val json = Json { ignoreUnknownKeys = true }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: List<String>): String? = when {
        args.isEmpty() -> null
        args.size == 1 && args[0].lowercase() == "show" -> null
        else -> USAGE
    }

    suspend fun report(stream: FrameStream): UsageReport {
        val answer = stream.request(Envelope(Envelope.USAGE_GET, ""), Envelope.USAGE_REPORT, "usage_get", idempotent = true, timeoutMs = 45_000L)
        return json.decodeFromString(UsageReport.serializer(), answer.data)
    }

    suspend fun run(stream: FrameStream, args: List<String>, out: Appendable): Int {
        out.appendLine(UsageText.render(report(stream)))
        return 0
    }
}
