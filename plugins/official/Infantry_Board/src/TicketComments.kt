// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.runtime.mutableStateMapOf
import com.superiorsoftllc.infantry.ui.shell.TicketsContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/** TICKET-COMMENTS-3: one comment as the daemon's `infantry_ticket_comments` line carries it. */
data class TicketComment(val id: String, val kind: String, val state: String, val author: String, val time: String, val body: String) {
    val open: Boolean get() = state == "open"
}

/** A ticket's creator, notify list and open comment count (the daemon's `infantry_ticket_get`). */
data class TicketCommentMeta(val createdBy: String?, val notify: List<String>, val open: Int)

/** An agent the notify picker offers. */
data class NotifyAgent(val id: String, val name: String)

/** TICKET-COMMENTS-3: what the comments panel needs from the daemon; the real one calls the board plugin's tools, a test fakes it. */
interface TicketCommentsApi {
    suspend fun comments(ticketId: String): Result<List<TicketComment>>
    suspend fun meta(ticketId: String): Result<TicketCommentMeta>
    /** The owner posts [body]; [resolved] posts it already resolved. */
    suspend fun add(ticketId: String, body: String, resolved: Boolean): Result<Unit>
    suspend fun resolve(commentId: String): Result<Unit>
    /** User-created agents only (the agent registry), never an invoked ticket worker session. */
    suspend fun notifyCandidates(): Result<List<NotifyAgent>>
    suspend fun saveNotify(ticketId: String, agentIds: List<String>): Result<Unit>
}

/** The open-comment count of each ticket the board has asked about, so a card's badge and the modal agree. */
object TicketCommentCounts {
    val open = mutableStateMapOf<String, Int>()
}

class DaemonTicketCommentsApi(private val tickets: TicketsContext) : TicketCommentsApi {
    private suspend fun call(tool: String, args: JsonObject): Result<String> = tickets.pluginTool(tool, args)

    override suspend fun comments(ticketId: String) = call("infantry_ticket_comments", buildJsonObject { put("ticketId", ticketId); put("which", "all") }).map(::parseComments)

    override suspend fun meta(ticketId: String) = call("infantry_ticket_get", buildJsonObject { put("ticketId", ticketId) }).mapCatching { text ->
        val o = Json.parseToJsonElement(text).jsonObject
        TicketCommentMeta(
            o["createdBy"]?.jsonPrimitive?.contentOrNull,
            (o["notify"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            o["openComments"]?.jsonPrimitive?.intOrNull ?: 0,
        ).also { TicketCommentCounts.open[ticketId] = it.open }
    }

    override suspend fun add(ticketId: String, body: String, resolved: Boolean) =
        call("infantry_ticket_comment", buildJsonObject { put("ticketId", ticketId); put("body", body); put("state", if (resolved) "resolved" else "open") }).map { }

    override suspend fun resolve(commentId: String) = call("infantry_ticket_comment", buildJsonObject { put("commentId", commentId) }).map { }

    override suspend fun notifyCandidates() = call("infantry_agent_list", buildJsonObject { }).map(::parseAgents)

    override suspend fun saveNotify(ticketId: String, agentIds: List<String>) =
        call("infantry_ticket_update", buildJsonObject { put("ticketId", ticketId); put("notify", JsonArray(agentIds.map { JsonPrimitive(it) })) }).map { }

    companion object {
        private val line = Regex("""^(\S+) (\w+)/(\w+) by (.+?) (\d{4}-\d\d-\d\dT\S+): (.*)$""")

        /** One comment per line (`id kind/state by author time: text`); a line that is not one continues the previous body. */
        fun parseComments(text: String): List<TicketComment> {
            val out = mutableListOf<TicketComment>()
            for (raw in text.lines()) {
                val m = line.matchEntire(raw)
                if (m != null) out += TicketComment(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5], m.groupValues[6])
                else if (out.isNotEmpty() && raw.isNotBlank()) out[out.lastIndex] = out.last().let { it.copy(body = it.body + "\n" + raw) }
            }
            return out
        }

        /** `id<TAB>name[<TAB>[disabled]]<TAB>(contributor/source)` per agent; disabled agents are not offered. */
        fun parseAgents(text: String): List<NotifyAgent> = text.lines().mapNotNull { l ->
            val cols = l.split('\t')
            if (cols.size < 2 || cols.any { it == "[disabled]" }) null else NotifyAgent(cols[0].trim(), cols[1].trim())
        }.filter { it.id.isNotEmpty() }
    }
}
