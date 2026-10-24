package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.ProcessReport
import com.superiorsoftllc.agenthq.session.ProcessesAnswer
import com.superiorsoftllc.agenthq.session.ProcessesRequest
import com.superiorsoftllc.agenthq.session.TrackedProcess
import kotlinx.serialization.json.Json

/**
 * ROGUE-1 (contract 1.120.0): `agenthq processes [--all]` and `agenthq processes reap <pid> | --all`. The MCP tools
 * `agenthq_processes` / `agenthq_processes_reap` go through the same [request] and [render].
 */
internal object ProcessesCli {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A usage problem with [args] (the words after `processes`), or null. [all] = `--all` was given. */
    fun usageError(args: List<String>, all: Boolean): String? = when {
        args.isEmpty() -> null
        args.first() != "reap" -> "usage: agenthq processes [--all] | agenthq processes reap <pid> | agenthq processes reap --all"
        args.size == 1 && all -> null
        args.size == 2 && !all && args[1].toLongOrNull() != null -> null
        else -> "usage: agenthq processes reap <pid> | agenthq processes reap --all"
    }

    /** The request the arguments mean (already validated by [usageError]). */
    fun requestFor(args: List<String>, all: Boolean): ProcessesRequest = when {
        args.isEmpty() -> ProcessesRequest("list")
        all -> ProcessesRequest("reap_rogue")
        else -> ProcessesRequest("reap", args[1].toLong())
    }

    suspend fun request(stream: FrameStream, req: ProcessesRequest): ProcessesAnswer {
        val answer = stream.request(
            Envelope(Envelope.PROCESSES, json.encodeToString(ProcessesRequest.serializer(), req)),
            answerType = Envelope.PROCESSES,
            what = "processes",
            idempotent = req.op == "list",
        )
        return json.decodeFromString(ProcessesAnswer.serializer(), answer.data)
    }

    /** Human text. [showAll] adds the healthy tracked processes and the unattributed ones. */
    fun render(a: ProcessesAnswer, showAll: Boolean, now: Long = System.currentTimeMillis()): String = buildString {
        a.refused?.let { appendLine("refused: $it") }
        for (p in a.reaped) appendLine("reaped pid ${p.pid} ${p.command}${p.reason?.let { " ($it)" } ?: ""}")
        appendReport(a.report, showAll, now)
    }

    private fun StringBuilder.appendReport(r: ProcessReport, showAll: Boolean, now: Long) {
        val free = r.freeMb?.let { "$it MB free" } ?: "free RAM unknown"
        val total = r.totalMb?.let { " of $it MB" } ?: ""
        val pressure = if (r.underPressure) " - UNDER the ${r.floorMb} MB floor" else ""
        appendLine("memory: $free$total$pressure; auto-reap ${if (r.autoReap) "on" else "off"}")
        val rogue = r.tracked.filter { it.rogue }
        appendLine("rogue (${rogue.size}):" + if (rogue.isEmpty()) " none" else "")
        rogue.forEach { appendLine("  " + line(it, now)) }
        if (rogue.isNotEmpty()) appendLine("  reap: agenthq processes reap <pid>   or all: agenthq processes reap --all")
        if (!showAll) {
            appendLine("(${r.tracked.size - rogue.size} healthy tracked, ${r.unattributed.size} unattributed: agenthq processes --all)")
            return
        }
        val healthy = r.tracked.filter { !it.rogue }
        appendLine("tracked, healthy (${healthy.size}):" + if (healthy.isEmpty()) " none" else "")
        healthy.forEach { appendLine("  " + line(it, now)) }
        appendLine("not ours, never reaped (${r.unattributed.size}):" + if (r.unattributed.isEmpty()) " none" else "")
        r.unattributed.forEach { appendLine("  " + line(it, now)) }
    }

    private fun line(p: TrackedProcess, now: Long): String = buildString {
        append("pid ").append(p.pid)
        append(" | ").append(p.memoryMb?.let { "$it MB" } ?: "? MB")
        p.startedAt?.let { append(" | ").append(age(now - it)) }
        p.sessionId?.let { append(" | session ").append(it.take(8)) }
        append(" | ").append(p.command)
        p.reason?.let { append(" | ").append(it) }
    }

    internal fun age(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m < 1440 -> "${m / 60}h${m % 60}m"
            else -> "${m / 1440}d${(m % 1440) / 60}h"
        }
    }
}
