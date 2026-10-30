package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.board.BoardResult
import com.superiorsoftllc.agenthq.daemon.api.board.TicketInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * BOARD-API-2 (first F5 slice): `agenthq_ticket_get`, moved out of the CLI's MCP server (`McpTicketTools.get`). Same name, descriptor,
 * answer and refusal texts; it reads through the host's Tickets API ([DaemonApi.tickets]), which applies the project scope and the
 * policy, so this tool holds no scope rule of its own.
 *
 * WHO is asking: the MCP process reports the session it runs for (`AGENTHQ_SESSION_ID`, [ToolCall.clientSessionId]); the host scopes
 * the read to that session's project exactly as the CLI-side `ProjectScope` did (a worker reads its own project, pm / owner reach all).
 */
class TicketGetTool(private val api: DaemonApi) : DaemonTool {
    override val name = GET
    override val description =
        "Read ONE ticket back: title, verbatim description, status, project, role/agent, provider, model, " +
            "branch, worker session, attached images."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", buildJsonObject {
                put("type", "string")
                put("description", "Full ticket id, or its 8-char suffix.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("ticketId")) })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", true)
        put("destructiveHint", false)
        put("openWorldHint", false)
    }

    override fun call(call: ToolCall): ToolResult {
        val requested = (call.arguments["ticketId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("$GET needs a ticketId", isError = true)
        val by = call.caller.copy(activeSession = call.clientSessionId ?: call.caller.activeSession)
        return when (val r = api.tickets.get(requested, by)) {
            is BoardResult.Ok -> ToolResult(Json.encodeToString(JsonObject.serializer(), summary(r.value)))
            is BoardResult.Refused -> ToolResult(r.text, isError = true)
            is BoardResult.Unavailable -> ToolResult("$GET is unavailable: ${r.reason}", isError = true)
        }
    }

    companion object {
        const val GET = "agenthq_ticket_get"

        /** The fields an agent reads back, the description verbatim; `role` is read off `agentId` (`role:<id>`). */
        internal fun summary(ticket: TicketInfo) = buildJsonObject {
            put("ticketId", ticket.id)
            put("title", ticket.title)
            put("description", ticket.description)
            put("status", ticket.status)
            put("projectId", ticket.projectId)
            put("epicId", ticket.epicId)
            put("agentId", ticket.agentId)
            put("role", ticket.agentId?.takeIf { it.startsWith("role:") }?.removePrefix("role:"))
            put("provider", ticket.provider)
            put("model", ticket.model)
            put("weight", ticket.weight)
            // CONTEXT-LIMIT-1: the ticket's context limit and the effective one (source ticket / default, the harness's support).
            put("contextLimit", ticket.contextLimit)
            if (ticket.effectiveContextLimitSource != null) {
                put("effectiveContextLimit", ticket.effectiveContextLimit)
                put("effectiveContextLimitSource", ticket.effectiveContextLimitSource)
                put("contextLimitSupport", ticket.contextLimitSupport)
            }
            put("effectiveProvider", ticket.effectiveProvider)
            put("effectiveProviderSource", ticket.effectiveProviderSource)
            put("effectiveModel", ticket.effectiveModel)
            put("effectiveModelSource", ticket.effectiveModelSource)
            put("effectiveRule", ticket.effectiveRule)
            put("branch", ticket.branch)
            put("sessionId", ticket.sessionId)
            put("images", buildJsonArray { ticket.images.forEach { add(JsonPrimitive(it)) } })
        }
    }
}
