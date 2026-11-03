package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/** TICKET-COMMENTS-2: how the board plugin wires [TicketComments] to the daemon, and who a call's author is. */
internal object CommentsWiring {
    private val dead = setOf("EXITED", "FAILED", "ORPHANED")

    /** COMMENT-TOOL-1: what the comment tools answer when the store could not start (they stay listed, never vanish). */
    const val UNAVAILABLE = "ticket comments are unavailable: the comment store did not start (see the daemon log 'ticket comments unavailable')"

    fun create(api: DaemonApi): TicketComments = TicketComments(
        db = api.storage,
        isRegistryAgent = { api.agents.get(it) != null },
        isSession = { id -> runCatching { api.sessions.get(id) }.getOrNull() != null },
        liveSessions = { agent, project ->
            api.sessions.list().filter { it.agentId == agent && it.state !in dead && (project == null || it.projectId == project) }.map { it.id }
        },
        notifier = api.sessionNotifier,
        setting = { api.boardRuntime.setting(it) },
        onAdded = { publishAdded(api, it) },
    ).also { it.migrate() }

    /** SCRIPT-3b: an added comment publishes the named event `ticket.commented`. A failed publish never fails the comment. */
    fun publishAdded(api: DaemonApi, c: TicketComments.Comment) {
        val payload = buildJsonObject {
            put("ticketId", c.ticketId)
            put("commentId", c.id)
            put("author", c.author.label)
            put("bodyPreview", c.body.lineSequence().firstOrNull().orEmpty().take(200))
        }.toString()
        runCatching { api.events.publish("ticket.commented", payload) }
    }

    /** The owner (CLI / UI without a session), or the calling session with its agent. */
    fun author(api: DaemonApi, call: ToolCall): TicketComments.Author {
        val view = (api.sessionReads.caller(WireTickets.by(call)) as? SessionsResult.Ok)?.value
        val sid = call.clientSessionId ?: view?.id
        if (view == null && sid == null) return TicketComments.Author.owner
        return TicketComments.Author(TicketComments.Author.SESSION, sid, view?.agentId ?: view?.role?.let { "role:$it" })
    }

    /** A string-array argument: null when absent, refused (as an IllegalArgumentException) when not an array of strings. */
    fun list(args: JsonObject, key: String): List<String>? {
        val v = args[key] ?: return null
        val array = v as? JsonArray ?: throw IllegalArgumentException("$key must be an array of agent ids")
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("$key must be an array of agent ids") }
    }

    fun line(c: TicketComments.Comment, full: Boolean): String {
        val head = "${c.shortId} ${c.kind}/${c.state} by ${c.author.label} ${Instant.ofEpochMilli(c.createdAt)}"
        val text = if (full) c.body else c.body.lineSequence().firstOrNull().orEmpty().take(200)
        return "$head: $text"
    }

    fun notifyProps(b: kotlinx.serialization.json.JsonObjectBuilder, update: Boolean) {
        b.put("notify", TicketSave.pathsProp(if (update) "Replace the ticket's notify list: user-created agent ids that get a mail line for every new comment. Invoked ticket workers are refused." else "Agents to notify on every comment (user-created agent ids). Default: you plus the project's default list (setting board.notify.<projectId>)."))
        if (update) {
            b.put("addNotify", TicketSave.pathsProp("Agent ids to ADD to the notify list."))
            b.put("removeNotify", TicketSave.pathsProp("Agent ids to REMOVE from the notify list."))
        }
    }
}

/** `infantry_ticket_comment`: add a comment to a ticket, or resolve one by its id. */
class TicketCommentTool(private val api: DaemonApi, private val store: TicketComments?, private val unavailable: String = CommentsWiring.UNAVAILABLE) : DaemonTool {
    override val name = ADD
    override val description =
        "Comment on a ticket (the extra channel next to mail): every agent on the ticket's notify list gets ONE short mail line at once. " +
            "kind done = the work is finished (say the commit), issue = a problem to answer, note = anything else (default). " +
            "With commentId and no ticketId/body, RESOLVES that comment (no mail). Read them with infantry_ticket_comments."
    override val inputSchema = TicketSave.schema("") {
        put("ticketId", TicketSave.prop("Full ticket id, or its 8-char suffix (add)."))
        put("body", TicketSave.prop("The comment, Markdown (add)."))
        put("kind", TicketSave.prop("done | issue | note (default note)."))
        put("state", TicketSave.prop("open (default) or resolved: a comment that needs no answer can be posted resolved."))
        put("commentId", TicketSave.prop("Resolve: the comment's id (the 8-char id is enough)."))
    }.let { JsonObject(it - "required") }
    override val annotations = ToolArgs.annotations(readOnly = false)

    override fun call(call: ToolCall): ToolResult = try {
        val comments = store ?: return TicketSave.refused("$ADD refused: $unavailable")
        val args = call.arguments
        val author = CommentsWiring.author(api, call)
        val commentId = ToolArgs.string(args, "commentId")
        if (commentId != null) {
            val existing = comments.get(commentId) ?: return TicketSave.refused("$ADD: no comment '$commentId'")
            WireTickets.find(api, call, existing.ticketId).getOrElse { return TicketSave.refused(it.message ?: "no ticket for that comment") }
            val done = comments.resolve(existing.id, author)!!
            ToolResult("resolved ${done.shortId} on ${WireTickets.shortId(done.ticketId)}")
        } else {
            val requested = ToolArgs.string(args, "ticketId") ?: return TicketSave.refused("$ADD needs a ticketId and a body (or a commentId to resolve)")
            val body = TicketSave.verbatim(args, "body")?.takeIf { it.isNotBlank() } ?: return TicketSave.refused("$ADD needs a body")
            val ticket = WireTickets.find(api, call, requested).getOrElse { return TicketSave.refused(it.message ?: "no ticket '$requested'") }
            val c = comments.add(
                ticket.id, ticket.projectId, ticket.title, author,
                ToolArgs.string(args, "kind")?.lowercase() ?: "note", ToolArgs.string(args, "state")?.lowercase() ?: TicketComments.OPEN, body,
            )
            ToolResult(
                "ok ${c.shortId} ${c.kind}/${c.state} on ${WireTickets.shortId(ticket.id)}" +
                    (if (c.undelivered.isEmpty()) "" else " | not delivered (no live session): ${c.undelivered.joinToString(", ")}"),
            )
        }
    } catch (e: IllegalArgumentException) {
        TicketSave.refused("$ADD refused: ${e.message}")
    }

    companion object { const val ADD = "infantry_ticket_comment" }
}

/** `infantry_ticket_comments`: read a ticket's comments, compact. */
class TicketCommentsTool(private val api: DaemonApi, private val store: TicketComments?, private val unavailable: String = CommentsWiring.UNAVAILABLE) : DaemonTool {
    override val name = READ
    override val description =
        "Read a ticket's comments, one compact line each (id, kind/state, author, time, text). which = latestOpen (default: the newest " +
            "unresolved one), latest, first or all; kind filters done|issue|note; limit keeps the newest N of all. latestOpen/latest/first and limit show full text for one comment."
    override val inputSchema = TicketSave.schema("ticketId") {
        put("ticketId", TicketSave.prop("Full ticket id, or its 8-char suffix."))
        put("which", TicketSave.prop("latestOpen (default) | latest | first | all."))
        put("kind", TicketSave.prop("Only comments of this kind: done | issue | note."))
        put("limit", buildJsonObject { put("type", "integer"); put("description", "With which=all: keep only the newest N.") })
    }
    override val annotations = ToolArgs.annotations(readOnly = true)

    override fun call(call: ToolCall): ToolResult = try {
        val comments = store ?: return TicketSave.refused("$READ refused: $unavailable")
        val args = call.arguments
        val requested = ToolArgs.string(args, "ticketId") ?: return TicketSave.refused("$READ needs a ticketId")
        val ticket = WireTickets.find(api, call, requested).getOrElse { return TicketSave.refused(it.message ?: "no ticket '$requested'") }
        val which = ToolArgs.string(args, "which") ?: "latestOpen"
        val limit = (args["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val found = comments.read(ticket.id, which, ToolArgs.string(args, "kind")?.lowercase(), limit)
        val full = which != "all"
        ToolResult(if (found.isEmpty()) "no comments (${which}) on ${WireTickets.shortId(ticket.id)}" else found.joinToString("\n") { CommentsWiring.line(it, full) })
    } catch (e: IllegalArgumentException) {
        TicketSave.refused("$READ refused: ${e.message}")
    }

    companion object { const val READ = "infantry_ticket_comments" }
}
