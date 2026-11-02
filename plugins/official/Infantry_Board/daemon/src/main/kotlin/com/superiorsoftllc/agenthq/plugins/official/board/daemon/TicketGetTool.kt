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
class TicketGetTool(private val api: DaemonApi, private val comments: TicketComments? = null) : DaemonTool {
    override val name = GET
    override val description =
        "Read ONE ticket back: title, verbatim description, status, project, role/agent, provider, model, " +
            "branch, worker session, attached images. The RELATED (auto) block is left out (related tickets and their files are a separate call: " +
            "agenthq_ticket_related_files); pass related=true to include it."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticketId", buildJsonObject {
                put("type", "string")
                put("description", "Full ticket id, or its 8-char suffix.")
            })
            put("detail", buildJsonObject {
                put("type", "string")
                put("description", "summary = id, title, status, role, model, branch, epic, open comment count, first chars of the description (default; setting limits.ticket.getDefaultDetail); full = every field and the whole description.")
            })
            put("related", ToolArgs.boolProp("true = keep the stored RELATED (auto) block in the description (default false; use agenthq_ticket_related_files)."))
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
        val asked = (call.arguments["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        val full = when (asked) {
            "full" -> true
            "summary" -> false
            else -> (api.settings.limit("limits.ticket.getDefaultDetail") ?: 0L) == 1L
        }
        return when (val r = api.tickets.get(requested, by)) {
            is BoardResult.Ok -> {
                val chars = (api.settings.limit("limits.ticket.summaryDescriptionChars") ?: DEFAULT_SUMMARY_CHARS.toLong()).toInt()
                val base = if (full) summary(r.value, ToolArgs.flag(call.arguments, "related")) else compact(r.value, chars)
                ToolResult(Json.encodeToString(JsonObject.serializer(), withComments(r.value, base, full)))
            }
            is BoardResult.Refused -> ToolResult(r.text, isError = true)
            is BoardResult.Unavailable -> ToolResult("$GET is unavailable: ${r.reason}", isError = true)
        }
    }

    /** TICKET-COMMENTS-2: createdBy, the notify list, the open comment count, the latest open comment's first line and who was not reached. */
    private fun withComments(ticket: TicketInfo, base: JsonObject, full: Boolean = true): JsonObject {
        val c = comments ?: return base
        val sum = c.summary(ticket.id)
        if (!full) return JsonObject(base + buildJsonObject { put("openComments", sum.open) })
        val meta = c.meta(ticket.id)
        return JsonObject(base + buildJsonObject {
            put("createdBy", meta?.createdBy)
            put("notify", buildJsonArray { meta?.notify.orEmpty().forEach { add(JsonPrimitive(it)) } })
            put("openComments", sum.open)
            sum.latestOpen?.let { put("latestOpenComment", CommentsWiring.line(it, full = false)) }
            if (sum.undelivered.isNotEmpty()) put("undeliveredTo", buildJsonArray { sum.undelivered.forEach { add(JsonPrimitive(it)) } })
        })
    }

    companion object {
        const val GET = "agenthq_ticket_get"
        const val DEFAULT_SUMMARY_CHARS = 300

        /** TICKET-COMPACT-1: the board-wide read: identity, status, routing and the description's first [chars] chars only. */
        internal fun compact(ticket: TicketInfo, chars: Int) = buildJsonObject {
            val body = stripRelated(ticket.description)
            put("ticketId", ticket.id)
            put("title", ticket.title)
            put("status", ticket.status)
            put("role", ticket.agentId?.takeIf { it.startsWith("role:") }?.removePrefix("role:"))
            put("model", ticket.model)
            put("branch", ticket.branch)
            put("epicId", ticket.epicId)
            put("description", if (chars <= 0 || body.length <= chars) body else body.take(chars) + "... [cut at $chars chars; detail=full for all]")
        }

        private const val RELATED_BEGIN = "<!-- RELATED (auto) -->"
        private const val RELATED_END = "<!-- /RELATED -->"

        /** TOKEN-1: [description] without its stored RELATED (auto) block (read layer only; storage is unchanged). */
        internal fun stripRelated(description: String): String {
            if (!description.startsWith(RELATED_BEGIN)) return description
            val end = description.indexOf(RELATED_END)
            if (end < 0) return description
            return description.substring(end + RELATED_END.length).trimStart { it == '\r' || it == '\n' }
        }

        /** TOKEN-1: the one-line confirmation of a write: id, status, title, branch, worker session (no description, no RELATED block). */
        internal fun brief(ticket: TicketInfo) = buildJsonObject {
            put("ticketId", ticket.id)
            put("status", ticket.status)
            put("title", ticket.title)
            ticket.branch?.let { put("branch", it) }
            ticket.sessionId?.let { put("sessionId", it) }
        }

        /** TOKEN-1: a write's answer: the [brief] line, or the full [summary] when the call passed `verbose: true`. */
        internal fun answer(ticket: TicketInfo, verbose: Boolean) = if (verbose) summary(ticket) else brief(ticket)

        /** The fields an agent reads back, the description verbatim; `role` is read off `agentId` (`role:<id>`). */
        internal fun summary(ticket: TicketInfo, related: Boolean = true) = buildJsonObject {
            put("ticketId", ticket.id)
            put("title", ticket.title)
            put("description", if (related) ticket.description else stripRelated(ticket.description))
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
            // EFFORT-1: the ticket's reasoning effort and the effective one (source ticket / rule / agent / role / default, the harness's support).
            put("effort", ticket.effort)
            if (ticket.effectiveEffortSource != null) {
                put("effectiveEffort", ticket.effectiveEffort)
                put("effectiveEffortSource", ticket.effectiveEffortSource)
                put("effortSupport", ticket.effortSupport)
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
