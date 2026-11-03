package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-7 (F5 last, docs/RUNTIME-LOAD.md section 13): `infantry_ticket_start` (BUG-ASSIGN-1), moved out of the CLI's MCP server.
 * Same descriptor (golden-checked) and answer ([SpawnText.startAnswer]). It is [com.superiorsoftllc.infantry.daemon.api.spawns.Spawns.startTicket]:
 * the host checks the caller's depth, concurrency and pod cap, then runs the board's own start with the caller recorded as starter.
 */
class TicketStartTool(private val api: DaemonApi) : DaemonTool {
    override val name = START
    override val description =
        "Start ONE ticket: THE way to put an agent to work on a ticket. It is the board's own start (the card " +
            "moving to In Progress): the worker runs the ticket's role, provider and model, in its own git " +
            "worktree on branch ticket/<short-id>-<slug>, and the ticket goes In Progress with you recorded " +
            "as its starter. Pin role / provider / model first with infantry_ticket_create or infantry_ticket_update. A ticket whose " +
            "lane is busy is queued; one held by open prerequisites, or that cannot start, is refused with " +
            "the reason. Starting a ticket already in progress returns its worker. Guarded like infantry_spawn."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix."))
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    /** The start creates a worktree and may wait on the pod guard; the default tool timeout is for reads. */
    override val timeoutMs = 120_000L

    override fun call(call: ToolCall): ToolResult {
        val requested = ToolArgs.string(call.arguments, "ticketId") ?: return SpawnText.refused("$START needs a ticketId")
        return when (val r = api.spawns.startTicket(requested, WireTickets.by(call), call.clientNarrowing ?: SpawnNarrowing.NONE)) {
            is SessionsResult.Ok -> SpawnText.startAnswer(START, r.value, ToolArgs.flag(call.arguments, "verbose"))
            is SessionsResult.Missing -> SpawnText.refused("$START refused: ${r.text}")
            is SessionsResult.Refused -> SpawnText.refused("$START refused: ${r.reason}")
            is SessionsResult.Unavailable -> SpawnText.refused("$START is unavailable: ${r.reason}")
        }
    }

    companion object {
        const val START = "infantry_ticket_start"
    }
}
