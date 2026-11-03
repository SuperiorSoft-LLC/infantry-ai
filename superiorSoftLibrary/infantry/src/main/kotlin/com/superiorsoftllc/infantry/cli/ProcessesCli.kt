package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesText
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessReport
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesAnswer
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesRequest
import com.superiorsoftllc.infantry.daemon.api.processes.TrackedProcess
import kotlinx.serialization.json.Json

/**
 * ROGUE-1 (contract 1.120.0): `infantry processes [--all]` and `infantry processes reap <pid> | --all`. The MCP tools
 * `infantry_processes` / `infantry_processes_reap` go through the same [request] and [render].
 */
internal object ProcessesCli {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A usage problem with [args] (the words after `processes`), or null. [all] = `--all` was given. */
    fun usageError(args: List<String>, all: Boolean): String? = when {
        args.isEmpty() -> null
        args.first() != "reap" -> "usage: infantry processes [--all] | infantry processes reap <pid> | infantry processes reap --all"
        args.size == 1 && all -> null
        args.size == 2 && !all && args[1].toLongOrNull() != null -> null
        else -> "usage: infantry processes reap <pid> | infantry processes reap --all"
    }

    /** The request the arguments mean (already validated by [usageError]). */
    fun requestFor(args: List<String>, all: Boolean): ProcessesRequest = when {
        args.isEmpty() -> ProcessesRequest("list")
        all -> ProcessesRequest("reap_rogue")
        else -> ProcessesRequest("reap", args[1].toLong())
    }

    /** POLICY-1 (1.143.0): the frame [req] goes as: a list is `processes`, a reap `processes_reap`. */
    fun frameType(req: ProcessesRequest): String = if (req.op == "list") Envelope.PROCESSES else Envelope.PROCESSES_REAP

    suspend fun request(stream: FrameStream, req: ProcessesRequest): ProcessesAnswer {
        val answer = stream.request(
            // POLICY-1 (1.143.0): a reap is the `processes_reap` frame, so a read-only policy can still list.
            Envelope(frameType(req), json.encodeToString(ProcessesRequest.serializer(), req)),
            answerType = Envelope.PROCESSES,
            what = "processes",
            idempotent = req.op == "list",
        )
        return json.decodeFromString(ProcessesAnswer.serializer(), answer.data)
    }

    /** Human text. [showAll] adds the healthy tracked processes and the unattributed ones. */
    fun render(a: ProcessesAnswer, showAll: Boolean, now: Long = System.currentTimeMillis()): String = ProcessesText.render(a, showAll, now)

    internal fun age(ms: Long): String = ProcessesText.age(ms)
}
