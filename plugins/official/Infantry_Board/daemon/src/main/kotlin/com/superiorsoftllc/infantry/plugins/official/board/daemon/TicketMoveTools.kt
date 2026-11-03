// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true }
private const val START = "infantry_ticket_start"
private const val IN_PROGRESS = "in_progress"

private fun refused(text: String) = ToolResult(text, isError = true)

/**
 * RUNTIME-LOAD-4 (F5): `infantry_ticket_move` (MCP-MOVE-1, BUG-REORDER), moved out of the CLI's MCP server. Same descriptor, answer
 * and texts. Move ONE ticket over `ticket_move` (the frame `infantry ticket move` sends, naming the calling session so it is recorded
 * as starter); a move INTO in_progress is the board's start and goes through `infantry_ticket_start`; `pending` is derived by the
 * daemon and refused there. ONE of `before` / `after` / `top` / `bottom` / `position` places it in its column, through the API's
 * [TicketPlacement] (the one rule `infantry ticket move` uses too); with a placement the status may be left out.
 */
class TicketMoveTool(private val api: DaemonApi) : DaemonTool {
    override val name = MOVE
    override val description =
        "Move ONE ticket to a status (full id or 8-char id), e.g. done when your work is finished, or todo, " +
            "or backlog (parked: never started until moved back to todo), " +
            "and/or reorder it: ticket order inside a column is priority (the board and `tickets` list it top " +
            "first). Give at most one of before / after (just above / below that ticket), top, bottom or " +
            "position (0 = top); with one of them the status may be left out and the ticket is reordered in " +
            "its own column. Same as `infantry ticket move [status] --before|--after|--top|--bottom|--position`. " +
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
        const val MOVE = "infantry_ticket_move"
        private const val TICKET_LIST = "ticket_list"
        private const val TICKET_MOVE = "ticket_move"
        private const val TICKETS = "tickets"
        private const val TICKET = "ticket"
    }
}

/**
 * RUNTIME-LOAD-4 (F5): `infantry_ticket_delete` (MCP-DEL-1), moved out of the CLI's MCP server. Same descriptor, answer and texts.
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
                    "stop its worker first (infantry_kill), then delete it.",
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
        const val DELETE = "infantry_ticket_delete"
        private const val TICKET_DELETE = "ticket_delete"
        private const val TICKETS = "tickets"
    }
}

/**
 * AGENT-TASKS-2: `infantry_ticket_unassign`. Takes ONE in_progress ticket off its worker: the ticket goes back to the top of todo with
 * its branch and work kept (`infantry ticket unassign`), and the board writes a handoff note. The worker is ended after its current
 * turn (or at once with `now`). Over the `ticket_unassign` frame, naming the calling session.
 */
class TicketUnassignTool(private val api: DaemonApi) : DaemonTool {
    override val name = UNASSIGN
    override val description =
        "Take ONE in_progress ticket off its worker (full id or 8-char id): the ticket goes back to todo, at the top, with its " +
            "branch and work kept, so a second start continues where the first stopped. The board writes a handoff comment " +
            "(branch, tip, commits ahead, the worker's last message, your note) that the next worker's brief carries. A busy " +
            "worker finishes its current turn first (bounded by limits.agentTasks.unassignWaitSeconds); now ends it at once. " +
            "Same as `infantry ticket unassign <id> [--now] [--note <text>]`. Refused unless the ticket is in_progress."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix."))
            put("now", ToolArgs.boolProp("End the worker at once instead of after its current turn."))
            put("note", ToolArgs.prop("A note for the next worker, added to the handoff comment."))
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val requested = ToolArgs.string(args, "ticketId") ?: return refused("$UNASSIGN needs a ticketId")
        val ticket = WireTickets.find(api, call, requested).getOrElse { return refused("$UNASSIGN refused: ${it.message ?: "no ticket '$requested'"}") }
        val body = buildJsonObject {
            put("id", ticket.id)
            if (ToolArgs.flag(args, "now")) put("now", true)
            ToolArgs.string(args, "note")?.let { put("note", it) }
        }
        // NOT idempotent: sent once; a lost answer is reported, never re-sent.
        val answer = WireTickets.request(call, TICKET_UNASSIGN, json.encodeToString(JsonObject.serializer(), body), TICKET, sessionId = call.clientSessionId)
            .getOrElse { return refused("$UNASSIGN refused: ${it.message}") }
        val moved = runCatching { WireTickets.ticket(answer.data) }
            .getOrElse { return refused("$UNASSIGN: malformed ticket answer from the daemon: ${it.message}") }
        if (!ToolArgs.flag(args, "verbose")) return ToolResult("ok ${WireTickets.shortId(moved.id)} ${moved.status}")
        return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(TicketGetTool.answer(moved, true) + buildJsonObject {
            put("message", "Unassigned ticket ${WireTickets.shortId(moved.id)} -> ${moved.status}")
        })))
    }

    companion object {
        const val UNASSIGN = "infantry_ticket_unassign"
        private const val TICKET_UNASSIGN = "ticket_unassign"
        private const val TICKET = "ticket"
    }
}

/** AGENT-TASKS-4: `infantry_ticket_assign`, give a todo ticket to an existing agent (start now, or queue). Over `ticket_assign`. */
class TicketAssignTool(private val api: DaemonApi) : DaemonTool {
    override val name = ASSIGN
    override val description =
        "Assign a todo ticket to an EXISTING agent (profile id or unique name). Default starts it on that agent: no session -> a new " +
            "worker on the ticket's own branch; an idle session on no in-progress ticket is ended and the ticket starts on its exit " +
            "(the answer says the start is deferred). An agent on another in-progress ticket is refused (use infantry_agent_switch or " +
            "queue). queue=true adds it to the agent's queue at `at` instead. Same as `infantry ticket assign <id> <agent> [--queue] [--at n]`."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix."))
            put("agentId", ToolArgs.prop("Agent profile id, or its unique name."))
            put("queue", ToolArgs.boolProp("Add to the agent's queue instead of starting."))
            put("at", ToolArgs.prop("1-based place in the queue (with queue)."))
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")); add(JsonPrimitive("agentId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val requested = ToolArgs.string(args, "ticketId") ?: return refused("$ASSIGN needs a ticketId")
        val agent = ToolArgs.string(args, "agentId") ?: return refused("$ASSIGN needs an agentId")
        val at = ToolArgs.string(args, "at")?.let { it.toIntOrNull() ?: return refused("$ASSIGN: at must be a whole number") }
        val ticket = WireTickets.find(api, call, requested).getOrElse { return refused("$ASSIGN refused: ${it.message ?: "no ticket '$requested'"}") }
        val body = buildJsonObject {
            put("id", ticket.id)
            put("agentId", agent)
            if (ToolArgs.flag(args, "queue")) put("queue", true)
            at?.let { put("at", it) }
        }
        val answer = WireTickets.request(call, TICKET_ASSIGN, json.encodeToString(JsonObject.serializer(), body), TICKET, sessionId = call.clientSessionId)
            .getOrElse { return refused("$ASSIGN refused: ${it.message}") }
        return ticketReply(ASSIGN, answer.data, "Assigned", args)
    }

    companion object {
        const val ASSIGN = "infantry_ticket_assign"
        private const val TICKET_ASSIGN = "ticket_assign"
        private const val TICKET = "ticket"
    }
}

/** AGENT-TASKS-4: `infantry_agent_switch`, an agent leaves its in-progress ticket for another. Over `agent_switch`. */
class AgentSwitchTool(private val api: DaemonApi) : DaemonTool {
    override val name = SWITCH
    override val description =
        "Switch an agent from its in-progress ticket A to ticket B: A goes back to todo (top) with a handoff and its branch kept, B " +
            "heads the agent's queue, the worker ends like unassign (now = at once) and B starts for the same agent on exit (deferred: " +
            "the worker ends after its turn). With no current ticket it is an assign. Same as `infantry agent switch <agent> <ticketId> [--now]`."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("agentId", ToolArgs.prop("Agent profile id, or its unique name."))
            put("ticketId", ToolArgs.prop("The ticket to switch to: full id, or its 8-char suffix."))
            put("now", ToolArgs.boolProp("End the worker at once instead of after its current turn."))
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("agentId")); add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val agent = ToolArgs.string(args, "agentId") ?: return refused("$SWITCH needs an agentId")
        val requested = ToolArgs.string(args, "ticketId") ?: return refused("$SWITCH needs a ticketId")
        val ticket = WireTickets.find(api, call, requested).getOrElse { return refused("$SWITCH refused: ${it.message ?: "no ticket '$requested'"}") }
        val body = buildJsonObject {
            put("agentId", agent)
            put("ticketId", ticket.id)
            if (ToolArgs.flag(args, "now")) put("now", true)
        }
        val answer = WireTickets.request(call, AGENT_SWITCH, json.encodeToString(JsonObject.serializer(), body), TICKET, sessionId = call.clientSessionId)
            .getOrElse { return refused("$SWITCH refused: ${it.message}") }
        return ticketReply(SWITCH, answer.data, "Switched to", args)
    }

    companion object {
        const val SWITCH = "infantry_agent_switch"
        private const val AGENT_SWITCH = "agent_switch"
        private const val TICKET = "ticket"
    }
}

/** The shared reply of assign and switch: one line, with the deferred-start note; the full ticket with verbose. */
private fun ticketReply(tool: String, data: String, verb: String, args: JsonObject): ToolResult {
    val moved = runCatching { WireTickets.ticket(data) }.getOrElse { return refused("$tool: malformed ticket answer from the daemon: ${it.message}") }
    val start = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull()?.get("start") as? JsonObject
    val note = if ((start?.get("reason") as? JsonPrimitive)?.content == "deferred") (start["detail"] as? JsonPrimitive)?.content?.let { " ($it)" } ?: "" else ""
    val line = "$verb ${WireTickets.shortId(moved.id)} -> ${moved.status}$note"
    if (!ToolArgs.flag(args, "verbose")) return ToolResult("ok ${WireTickets.shortId(moved.id)} ${moved.status}$note")
    return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(TicketGetTool.answer(moved, true) + buildJsonObject { put("message", line) })))
}

/**
 * AGENT-TASKS-3: `infantry_agent_queue`, one agent's persistent ticket queue (list, add, remove, move, clear). Over the `agent_queue`
 * frame. The queue never starts anything itself: the agent's worker ending does.
 */
class AgentQueueTool(private val api: DaemonApi) : DaemonTool {
    override val name = QUEUE
    override val description =
        "List or change ONE agent's persistent ticket queue. action list (default), add (ticketId, optional at = 1-based place), " +
            "remove (ticketId), move (ticketId, at) or clear. agentId is a profile id or a unique agent name. Only todo tickets can be " +
            "queued, a ticket is in one queue, and a queue holds at most limits.agentTasks.queueMax tickets. When the agent's worker " +
            "ends, the lowest-place ticket starts on that agent; `startNext` never picks a queued ticket. " +
            "Same as `infantry agent queue <agent> [add <id> [--at n] | rm <id> | mv <id> <n> | clear]`."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("agentId", ToolArgs.prop("Agent profile id, or its unique name."))
            put("action", ToolArgs.prop("list (default), add, remove, move or clear."))
            put("ticketId", ToolArgs.prop("Full ticket id, or its 8-char suffix (add, remove, move)."))
            put("at", ToolArgs.prop("1-based place in the queue (add: default the end; move: required)."))
            put("verbose", ToolArgs.verboseProp())
        })
        put("required", buildJsonArray { add(JsonPrimitive("agentId")) })
        put("additionalProperties", false)
    }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult {
        val args = call.arguments
        val agent = ToolArgs.string(args, "agentId") ?: return refused("$QUEUE needs an agentId")
        val action = ToolArgs.string(args, "action") ?: "list"
        val at = ToolArgs.string(args, "at")?.let { it.toIntOrNull() ?: return refused("$QUEUE: at must be a whole number") }
        val ticketId = ToolArgs.string(args, "ticketId")?.let { requested ->
            WireTickets.find(api, call, requested).getOrElse { return refused("$QUEUE refused: ${it.message ?: "no ticket '$requested'"}") }.id
        }
        val body = buildJsonObject {
            put("agentId", agent)
            put("action", action)
            ticketId?.let { put("ticketId", it) }
            at?.let { put("at", it) }
        }
        val answer = WireTickets.request(call, AGENT_QUEUE, json.encodeToString(JsonObject.serializer(), body), AGENT_QUEUE)
            .getOrElse { return refused("$QUEUE refused: ${it.message}") }
        val state = runCatching { com.superiorsoftllc.infantry.protocol.AgentQueueState.decode(answer.data) }
            .getOrElse { return refused("$QUEUE: malformed answer from the daemon: ${it.message}") }
        val label = state.name ?: state.agentId
        val lines = state.tickets.joinToString("\n") { "#${it.rank} ${WireTickets.shortId(it.ticketId)} ${it.title}" }
        val head = "queue of $label: ${state.tickets.size} ticket(s)" + (state.message?.let { " ($it)" } ?: "")
        return ToolResult(if (lines.isEmpty()) head else "$head\n$lines")
    }

    companion object {
        const val QUEUE = "infantry_agent_queue"
        private const val AGENT_QUEUE = "agent_queue"
    }
}
