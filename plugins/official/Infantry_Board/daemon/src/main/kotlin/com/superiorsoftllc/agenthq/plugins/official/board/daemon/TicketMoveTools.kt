package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.board.TicketPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }
private const val START = "agenthq_ticket_start"
private const val IN_PROGRESS = "in_progress"

private fun refused(text: String) = ToolResult(text, isError = true)

/**
 * RUNTIME-LOAD-4 (F5): `agenthq_ticket_move` (MCP-MOVE-1, BUG-REORDER), moved out of the CLI's MCP server. Same descriptor, answer
 * and texts. Move ONE ticket over `ticket_move` (the frame `agenthq ticket move` sends, naming the calling session so it is recorded
 * as starter); a move INTO in_progress is the board's start and goes through `agenthq_ticket_start`; `pending` is derived by the
 * daemon and refused there. ONE of `before` / `after` / `top` / `bottom` / `position` places it in its column, through the API's
 * [TicketPlacement] (the one rule `agenthq ticket move` uses too); with a placement the status may be left out.
 */
class TicketMoveTool(private val api: DaemonApi) : DaemonTool {
    override val name = MOVE
    override val description =
        "Move ONE ticket to a status (full id or 8-char id), e.g. done when your work is finished, or todo, " +
            "or backlog (parked: never started until moved back to todo), " +
            "and/or reorder it: ticket order inside a column is priority (the board and `tickets` list it top " +
            "first). Give at most one of before / after (just above / below that ticket), top, bottom or " +
            "position (0 = top); with one of them the status may be left out and the ticket is reordered in " +
            "its own column. Same as `agenthq ticket move [status] --before|--after|--top|--bottom|--position`. " +
            "To START a ticket use $START; pending is " +
            "set by the daemon from open prerequisites and is refused here. The answer reads back the stored " +
            "ticket and its position (0 = top)."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix."))
            put("status", ToolArgs.prop("Target status, e.g. done, todo, backlog. Optional when before, top or bottom is given."))
            put("before", ToolArgs.prop("Place the ticket just above this ticket (full id or 8-char suffix), which must be in the target column."))
            put("after", ToolArgs.prop("Place the ticket just below this ticket (full id or 8-char suffix), which must be in the target column."))
            put("top", ToolArgs.boolProp("Place the ticket at the top of the column (highest priority)."))
            put("bottom", ToolArgs.boolProp("Place the ticket at the bottom of the column."))
            put("position", buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Place the ticket at this index of the column, 0 = the top (past the end = the bottom).")
            })
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val requested = ToolArgs.string(args, "ticketId") ?: return refused("$MOVE needs a ticketId")
        val rawPosition = args["position"]?.let { it as? JsonPrimitive }
        val position = rawPosition?.let { p -> p.contentOrNull?.trim()?.toIntOrNull() ?: return refused("$MOVE: position must be a whole number, 0 = the top") }
        val placement = TicketPlacement(ToolArgs.flag(args, "top"), ToolArgs.flag(args, "bottom"), ToolArgs.string(args, "before"), ToolArgs.string(args, "after"), position)
        if (placement.named > 1) return refused("$MOVE takes one of before, after, top, bottom, position")
        val status = ToolArgs.string(args, "status")?.lowercase()
        if (status == null && placement.named == 0) return refused("$MOVE needs a status, or a place in the column (before, after, top, bottom or position)")
        if (status == IN_PROGRESS) return refused("$MOVE refused: to start a ticket use $START")
        val ticket = WireTickets.find(api, call, requested).getOrElse { return refused("$MOVE refused: ${it.message ?: "no ticket '$requested'"}") }
        val target = if (placement.named == 0) {
            null
        } else {
            // before / after resolve their target on the whole board, like the ticket itself.
            val board = WireTickets.request(call, TICKET_LIST, "", TICKETS).mapCatching { WireTickets.tickets(it.data) }
                .getOrElse { return refused("$MOVE refused: ${it.message}") }
            placement.resolve(WireTickets.placed(ticket), status, board.map(WireTickets::placed)).getOrElse { return refused("$MOVE refused: ${it.message}") }
        }
        val move = buildJsonObject {
            put("id", ticket.id)
            status?.let { put("status", it) }
            target?.let { put("position", it) }
        }
        // NOT idempotent: the host sends it once; a lost answer is reported, never re-sent.
        val answer = WireTickets.request(call, TICKET_MOVE, json.encodeToString(JsonObject.serializer(), move), TICKET, sessionId = call.clientSessionId)
            .getOrElse { return refused("$MOVE refused: ${it.message}") }
        val moved = runCatching { WireTickets.ticket(answer.data) }
            .getOrElse { return refused("$MOVE: malformed ticket answer from the daemon: ${it.message}") }
        if (!ToolArgs.flag(args, "verbose")) return ToolResult("ok ${WireTickets.shortId(moved.id)} ${moved.status}" + if (target != null) " pos ${moved.position}" else "")
        return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(TicketGetTool.answer(moved, ToolArgs.flag(args, "verbose")) + buildJsonObject {
            put("position", moved.position)
            put("message", "Moved ticket ${WireTickets.shortId(moved.id)} -> ${moved.status}" + if (target != null) " at position ${moved.position}" else "")
        })))
    }

    companion object {
        const val MOVE = "agenthq_ticket_move"
        private const val TICKET_LIST = "ticket_list"
        private const val TICKET_MOVE = "ticket_move"
        private const val TICKETS = "tickets"
        private const val TICKET = "ticket"
    }
}

/**
 * RUNTIME-LOAD-4 (F5): `agenthq_ticket_delete` (MCP-DEL-1), moved out of the CLI's MCP server. Same descriptor, answer and texts.
 * Deletes ONE ticket over `ticket_delete` after resolving the id (full or 8-char) in the caller's scope; an unknown or ambiguous id
 * is refused before any frame is sent, and a ticket IN PROGRESS is refused here (its live worker holds it).
 */
class TicketDeleteTool(private val api: DaemonApi) : DaemonTool {
    override val name = DELETE
    override val description =
        "Delete ONE ticket from the board (full id or the 8-char id `tickets` prints), with its links. An " +
            "unknown or ambiguous id is refused and nothing is deleted. A ticket in progress is refused: stop " +
            "its worker first. One ticket per call; there is no bulk delete. The answer names the deleted " +
            "ticket's id and title."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix."))
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false, destructive = true)

    override fun call(call: ToolCall): ToolResult {
        val requested = ToolArgs.string(call.arguments, "ticketId") ?: return refused("$DELETE needs a ticketId")
        val ticket = WireTickets.find(api, call, requested).getOrElse { return refused("$DELETE refused: ${it.message ?: "no ticket '$requested'"}") }
        val short = WireTickets.shortId(ticket.id)
        if (ticket.status == IN_PROGRESS) {
            return refused(
                "$DELETE refused: ticket $short is in progress (worker session ${ticket.sessionId ?: "unknown"}); " +
                    "stop its worker first (agenthq_kill), then delete it.",
            )
        }
        // NOT idempotent: the host sends it once; a lost answer is reported, never re-sent.
        val answer = WireTickets.request(call, TICKET_DELETE, ticket.id, TICKETS).getOrElse { return refused("$DELETE refused: ${it.message}") }
        val board = runCatching { WireTickets.tickets(answer.data) }
            .getOrElse { return refused("$DELETE: malformed board answer from the daemon: ${it.message}") }
        if (board.any { it.id == ticket.id }) return refused("$DELETE: the daemon answered but ticket $short is still on the board")
        return ToolResult("ok $short deleted")
    }

    companion object {
        const val DELETE = "agenthq_ticket_delete"
        private const val TICKET_DELETE = "ticket_delete"
        private const val TICKETS = "tickets"
    }
}
