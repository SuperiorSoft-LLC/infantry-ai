package com.superiorsoftllc.infantry.daemon.api.spawns

import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-7 (DaemonApi 1.9.0, additive): the answer texts of a ticket start, shared by `infantry_ticket_start` (Infantry_Board)
 * and `infantry_spawn` given a `ticketId` (Infantry_Agents), the very JSON and messages `McpWriteTools.startAnswer` printed.
 */
object SpawnText {
    private const val ROLE_PREFIX = "role:"
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** The 8-char suffix of a ticket id, as `tickets` prints it. */
    fun shortTicketId(id: String): String = id.takeLast(8)

    /** The role a ticket asks for (`role:<id>` in its agentId); null when it names one profile. */
    fun roleOf(ticket: TicketInfo): String? = ticket.agentId?.takeIf { it.startsWith(ROLE_PREFIX) }?.removePrefix(ROLE_PREFIX)

    fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    fun refused(text: String) = ToolResult(text, isError = true)

    /** A ticket start's outcome as [tool] answers it. */
    fun startAnswer(tool: String, o: TicketStartOutcome, verbose: Boolean = false): ToolResult {
        val short = shortTicketId(o.ticket.id)
        return when (o.outcome) {
            TicketStartOutcome.STARTED -> if (!verbose) ToolResult("ok $short ${o.ticket.status} ${o.worker?.id ?: o.ticket.sessionId ?: ""}".trim()) else ok(base(o.ticket, o.worker, TicketStartOutcome.STARTED) {
                put("startedBy", o.startedBy)
                put("depth", o.depth)
                put("openSpawns", o.openSpawns)
                put("maxSpawns", o.maxSpawns)
                o.warning?.let { put("warning", it) }
                // BUG-REGRESS-1: what the worker's branch was cut from, so the agent never has to guess what it holds.
                o.note?.let { put("note", it) }
                put("message", "Started ticket $short: ${workerLine(o.ticket, o.worker)}" + (o.note?.let { ", $it" } ?: "") +
                    ". It is In Progress on the board; follow its worker with infantry_get_session.")
            })
            TicketStartOutcome.ALREADY_IN_PROGRESS -> ok(base(o.ticket, o.worker, TicketStartOutcome.ALREADY_IN_PROGRESS) {
                put("message", "Ticket $short was already in progress: nothing new was started. " +
                    (o.worker?.let { "Its worker is ${it.id}." } ?: "Its worker is not listed by the daemon."))
            })
            TicketStartOutcome.QUEUED -> ok(base(o.ticket, null, TicketStartOutcome.QUEUED) {
                put("queuedFor", o.lane)
                put("message", "Ticket $short is queued: every agent of its lane is busy" + (o.lane?.let { " ($it)" } ?: "") +
                    ". The board starts it when one frees up.")
            })
            TicketStartOutcome.HELD -> refused("$tool: ticket $short is held by open prerequisites and did not start" +
                (o.detail?.let { ": $it" } ?: "") + ". It starts once they are done.")
            else -> refused("$tool refused: ticket $short did not start: ${o.detail}")
        }
    }

    private fun base(ticket: TicketInfo, worker: SessionView?, outcome: String, extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("ticketId", ticket.id)
        put("title", ticket.title)
        put("status", ticket.status)
        put("outcome", outcome)
        put("started", outcome == TicketStartOutcome.STARTED)
        put("sessionId", worker?.id ?: ticket.sessionId)
        put("agentId", worker?.agentId)
        put("role", worker?.role ?: roleOf(ticket))
        put("provider", worker?.provider ?: ticket.provider)
        put("model", worker?.model ?: ticket.model)
        put("branch", worker?.worktreeBranch ?: ticket.branch)
        put("worktree", worker?.worktreePath)
        put("workingDir", worker?.workingDir)
        extra()
    }

    private fun workerLine(ticket: TicketInfo, worker: SessionView?): String = if (worker == null) {
        "its worker is not listed by the daemon yet"
    } else {
        "session ${worker.id} (agent ${worker.agentId ?: "?"}, role ${worker.role ?: "?"}) runs " +
            "${worker.provider}${worker.model?.let { " / $it" } ?: ""} on branch ${worker.worktreeBranch ?: ticket.branch ?: "?"} " +
            "in ${worker.worktreePath ?: worker.workingDir}"
    }
}
