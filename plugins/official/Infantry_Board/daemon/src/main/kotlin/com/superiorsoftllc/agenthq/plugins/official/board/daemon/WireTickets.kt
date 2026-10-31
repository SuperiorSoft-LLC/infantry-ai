package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.board.BoardResult
import com.superiorsoftllc.agenthq.daemon.api.board.PlacedTicket
import com.superiorsoftllc.agenthq.daemon.api.board.TicketInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * RUNTIME-LOAD-4: what the board's write tools share. The WRITE goes out as the very frame the CLI's MCP tool sent (`ticket_move`,
 * `ticket_delete`, ...) on the caller's own connection ([ToolCall.request]), so the daemon applies the same status machine, gates and
 * texts; the frame's answer is the wire ticket (`ticket` / `tickets`, the published contract), read here into the API's [TicketInfo]
 * so every answer is rendered by the one [TicketGetTool.summary]. Finding the ticket goes through the host's Tickets API, which
 * applies the project scope (the rule the CLI's `ProjectScope` applied) for the calling session.
 */
internal object WireTickets {
    const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    /** The caller as the host scopes it: the MCP process's own session (`AGENTHQ_SESSION_ID`), else the connection's. */
    fun by(call: ToolCall): Caller = call.caller.copy(activeSession = call.clientSessionId ?: call.caller.activeSession)

    /** The ONE ticket [requested] names (full id or a short id), in the caller's scope; failure = the text the CLI tool worded. */
    fun find(api: DaemonApi, call: ToolCall, requested: String): Result<TicketInfo> = when (val r = api.tickets.get(requested, by(call))) {
        is BoardResult.Ok -> Result.success(r.value)
        is BoardResult.Refused -> Result.failure(IllegalStateException(r.text))
        is BoardResult.Unavailable -> Result.failure(IllegalStateException(r.reason))
    }

    /** [type] with [data] on the caller's connection; an `error` answer or an exception is the failure, worded as the CLI's stream did. */
    fun request(call: ToolCall, type: String, data: String, answerType: String, sessionId: String? = null): Result<Frame> = try {
        val reply = call.request(type, data, sessionId)
        when (reply.type) {
            answerType -> Result.success(reply)
            ERROR -> Result.failure(IllegalStateException(reply.data.ifBlank { "$type failed" }))
            else -> Result.failure(IllegalStateException("$type: unexpected answer ${reply.type}"))
        }
    } catch (e: Exception) {
        Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName))
    }

    fun ticket(data: String): TicketInfo = info(json.parseToJsonElement(data) as JsonObject)

    fun tickets(data: String): List<TicketInfo> = (json.parseToJsonElement(data) as JsonArray).map { info(it as JsonObject) }

    fun placed(t: TicketInfo) = PlacedTicket(t.id, t.status, t.position)

    /** A wire `Ticket` as [TicketInfo]: the same flattening core's local binding does (effective executor and context limit). */
    fun info(o: JsonObject): TicketInfo {
        fun str(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull
        fun obj(parent: JsonObject?, key: String) = parent?.get(key) as? JsonObject
        val executor = obj(o, "effectiveExecutor")
        val limit = obj(o, "effectiveContextLimit")
        val effort = obj(o, "effectiveEffort")
        return TicketInfo(
            id = str(o, "id") ?: error("a ticket without an id"),
            title = str(o, "title").orEmpty(),
            description = str(o, "description").orEmpty(),
            ticketType = str(o, "ticketType"),
            projectId = str(o, "projectId"),
            epicId = str(o, "epicId"),
            status = str(o, "status") ?: error("a ticket without a status"),
            position = int("position") ?: 0,
            createdAt = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            updatedAt = (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            agentId = str(o, "agentId"),
            provider = str(o, "provider"),
            model = str(o, "model"),
            weight = int("weight"),
            effectiveProvider = str(obj(executor, "provider"), "value"),
            effectiveProviderSource = str(obj(executor, "provider"), "source"),
            effectiveModel = str(obj(executor, "model"), "value"),
            effectiveModelSource = str(obj(executor, "model"), "source"),
            effectiveRule = str(executor, "rule"),
            sessionId = str(o, "sessionId"),
            branch = str(o, "branch"),
            base = str(o, "base"),
            images = (o["images"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            contextLimit = int("contextLimit"),
            effectiveContextLimit = (limit?.get("value") as? JsonPrimitive)?.intOrNull,
            effectiveContextLimitSource = str(limit, "source"),
            contextLimitSupport = str(limit, "support"),
            effort = str(o, "effort"),
            effectiveEffort = str(effort, "value"),
            effectiveEffortSource = str(effort, "source"),
            effortSupport = str(effort, "support"),
        )
    }

    fun shortId(id: String): String = id.takeLast(8)
}
