package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.TicketImageFiles
import com.superiorsoftllc.agenthq.cli.TicketPrefixMatch
import com.superiorsoftllc.agenthq.cli.TicketStarter
import com.superiorsoftllc.agenthq.cli.matchTicketPrefix
import com.superiorsoftllc.agenthq.cli.BoardSummary
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.ContextLimits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The board tool still served by `agenthq mcp serve` itself: `agenthq_ticket_start` (served by [McpWriteTools.startTicket], under
 * this server's spawn guardrails, which are this process's own state). RUNTIME-LOAD-4 (F5) moved the rest of the board family
 * (create, update, move, delete, epic base, get) into Infantry_Board's daemon half; what stays here is the ticket lookup and the
 * ticket summary the start answer prints.
 */
internal object McpTicketTools {
    /** BUG-ASSIGN-1: start ONE ticket through the board (served by [McpWriteTools.startTicket], under its guardrails). */
    const val START = "agenthq_ticket_start"
    val NAMES = listOf(START)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /**
     * The ONE ticket [requested] names (a full id, or the 8-char suffix `tickets` prints), searched across the whole
     * board: a full id must resolve whatever project it is filed under. No match, or several, is the failure, worded
     * for the agent; a daemon that cannot be read throws, like every other request.
     */
    internal suspend fun findTicket(stream: FrameStream, requested: String, getenv: ((String) -> String?)? = null): Result<Ticket> {
        val found = findTicketUnscoped(stream, requested)
        val ticket = found.getOrNull() ?: return found
        if (getenv == null) return found
        val caller = ProjectScope.callerOf(ProjectScope.sessionsOf(stream), getenv)
        val refusal = ProjectScope.foreignRefusal("ticket ${shortTicketId(ticket.id)}", ticket.projectId, caller, getenv)
        return if (refusal == null) found else Result.failure(IllegalStateException(refusal))
    }

    private suspend fun findTicketUnscoped(stream: FrameStream, requested: String): Result<Ticket> {
        val answer = stream.request(Envelope(Envelope.TICKET_LIST, ""), answerType = Envelope.TICKETS, what = "ticket_list", idempotent = true)
        val board = json.decodeFromString(ListSerializer(Ticket.serializer()), answer.data)
        return when (val match = matchTicketPrefix(requested, board)) {
            is TicketPrefixMatch.Unique -> Result.success(match.ticket)
            TicketPrefixMatch.None -> Result.failure(NoSuchElementException("no ticket '$requested' on this daemon"))
            is TicketPrefixMatch.Ambiguous -> Result.failure(
                IllegalArgumentException(
                    "ticket id '$requested' is ambiguous; use a longer id or the full id: " +
                        match.tickets.joinToString("; ") { "${it.id} (${BoardSummary.cutTitle(it.title)})" },
                ),
            )
        }
    }

    /**
     * What a ticket tool answers: the fields an agent sets and reads back, the description verbatim. `role` is the
     * role the ticket asks for (BUG-ASSIGN-1), read off `agentId` (`role:<id>`); null when it names one profile.
     */
    internal fun summary(ticket: Ticket) = buildJsonObject {
        put("ticketId", ticket.id)
        put("title", ticket.title)
        put("description", ticket.description)
        put("status", ticket.status)
        put("projectId", ticket.projectId)
        put("epicId", ticket.epicId)
        put("agentId", ticket.agentId)
        put("role", TicketStarter.roleOf(ticket))
        put("provider", ticket.provider)
        put("model", ticket.model)
        put("weight", ticket.weight)
        put("contextLimit", ticket.contextLimit)
        ticket.effectiveContextLimit?.let { e ->
            put("effectiveContextLimit", e.value)
            put("effectiveContextLimitSource", e.source)
            put("contextLimitSupport", e.support)
        }
        put("effectiveProvider", ticket.effectiveExecutor?.provider?.value)
        put("effectiveProviderSource", ticket.effectiveExecutor?.provider?.source)
        put("effectiveModel", ticket.effectiveExecutor?.model?.value)
        put("effectiveModelSource", ticket.effectiveExecutor?.model?.source)
        put("effectiveRule", ticket.effectiveExecutor?.rule)
        put("branch", ticket.branch)
        put("sessionId", ticket.sessionId)
        put("images", buildJsonArray { ticket.images.forEach { add(JsonPrimitive(it)) } })
    }

    fun descriptors() = buildJsonArray {
        add(tool(
            START,
            "Start ONE ticket: THE way to put an agent to work on a ticket. It is the board's own start (the card " +
                "moving to In Progress): the worker runs the ticket's role, provider and model, in its own git " +
                "worktree on branch ticket/<short-id>-<slug>, and the ticket goes In Progress with you recorded " +
                "as its starter. Pin role / provider / model first with agenthq_ticket_create or agenthq_ticket_update. A ticket whose " +
                "lane is busy is queued; one held by open prerequisites, or that cannot start, is refused with " +
                "the reason. Starting a ticket already in progress returns its worker. Guarded like agenthq_spawn.",
            readOnly = false,
            required = listOf("ticketId"),
        ) {
            put("ticketId", prop("Full ticket id, or its 8-char suffix."))
        })
    }

    private fun prop(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun tool(
        name: String,
        description: String,
        readOnly: Boolean,
        required: List<String>,
        destructive: Boolean = false,
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject(properties))
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        })
        put("annotations", buildJsonObject {
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
            put("openWorldHint", false)
        })
    }
}
