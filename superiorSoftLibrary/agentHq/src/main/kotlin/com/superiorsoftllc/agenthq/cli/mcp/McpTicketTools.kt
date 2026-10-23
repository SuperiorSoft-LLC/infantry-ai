package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.TicketImageFiles
import com.superiorsoftllc.agenthq.cli.TicketPrefixMatch
import com.superiorsoftllc.agenthq.cli.TicketStarter
import com.superiorsoftllc.agenthq.cli.matchTicketPrefix
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * MCP-1: the board tools of `agenthq mcp serve` -- create, update and read ONE ticket -- so an agent reaches its board
 * through MCP instead of a shell command (no Tool approval, no shell quoting).
 *
 * Same frames as the CLI's `ticket create` / `ticket update` / `ticket show` (`ticket_save`, `ticket_list`), so the
 * daemon validates exactly what it validates for the CLI: an unknown provider, or a model that provider does not list,
 * is the daemon's refusal, returned as the tool error. Nothing is coerced.
 *
 * THE DESCRIPTION IS SENT VERBATIM. It is the one field never trimmed: a multi-line brief (BUG-CLI-1) arrives as a
 * JSON string, newlines included, and is stored exactly as given. Every other text field is trimmed, as the CLI does.
 *
 * `role` is stored as `role:<id>` (the ticket editor's "any agent of this role"), exactly like the CLI's `--role`.
 * The project is the call's `projectId`, else the server's `--project` / `.agenthq/mcp.json`, else the session's own
 * `AGENTHQ_PROJECT_ID` (the daemon exports it into every session it spawns).
 */
internal class McpTicketTools(
    private val getenv: (String) -> String?,
    private val defaultProjectId: String? = null,
) {
    suspend fun create(stream: FrameStream, arguments: JsonObject): ToolResult {
        val title = arguments.string("title") ?: return refused("$CREATE needs a title")
        val projectId = projectOf(arguments)
        if (arguments.string("projectId") != null) {
            val caller = ProjectScope.callerOf(ProjectScope.sessionsOf(stream), getenv)
            ProjectScope.foreignRefusal("the target project", projectId, caller, getenv)?.let { return refused("$CREATE refused: $it") }
        }
        // IMG-AGENT-1: local files are checked, then uploaded (purpose ticket), before the ticket is saved.
        val images = arguments.images(CREATE) { return it }?.let { attach(stream, projectId, it, CREATE) { r -> return r } }
        val save = TicketSave(
            title = title,
            description = arguments.verbatim("description"),
            projectId = projectId,
            agentId = roleAgentId(arguments.string("role")),
            provider = arguments.string("provider"),
            model = arguments.string("model"),
            weight = arguments.weight(),
            images = images,
        )
        val ticket = save(stream, save, CREATE) { return it }
        return saved(ticket, save, created = true)
    }

    suspend fun update(stream: FrameStream, arguments: JsonObject): ToolResult {
        val requested = arguments.string("ticketId") ?: return refused("$UPDATE needs a ticketId")
        val replace = arguments.images(UPDATE) { return it }
        val add = arguments.images(UPDATE, "addImages") { return it }
        val remove = arguments.images(UPDATE, "removeImages") { return it }
        if (replace != null && (add != null || remove != null)) {
            return refused("$UPDATE refused: give images (the full list) or addImages / removeImages, not both")
        }
        val existing = findTicket(stream, requested, getenv).getOrElse { return refused(it.message ?: "no ticket '$requested'") }
        val fullId = existing.id
        // IMG-AGENT-1: images replaces the list; addImages / removeImages edit the stored one. Every local file is
        // checked, and every removal must name an attached image, before anything is uploaded or saved.
        val images = when {
            replace != null -> attach(stream, existing.projectId, replace, UPDATE) { return it }
            add != null || remove != null -> {
                val unknown = remove.orEmpty().firstOrNull { given -> existing.images.none { TicketImageFiles.matches(it, given) } }
                if (unknown != null) return refused("$UPDATE refused: ticket ${shortTicketId(fullId)} has no image '${unknown.take(200)}'")
                val kept = existing.images.filterNot { stored -> remove.orEmpty().any { TicketImageFiles.matches(stored, it) } }
                (kept + attach(stream, existing.projectId, add.orEmpty(), UPDATE) { return it }).distinct()
            }
            else -> null
        }
        val save = TicketSave(
            id = fullId,
            title = arguments.string("title"),
            description = arguments.verbatim("description"),
            agentId = roleAgentId(arguments.string("role")),
            provider = arguments.string("provider"),
            model = arguments.string("model"),
            weight = arguments.weight(),
            images = images,
        )
        val ticket = save(stream, save, UPDATE) { return it }
        return saved(ticket, save, created = false)
    }

    suspend fun get(stream: FrameStream, arguments: JsonObject): ToolResult {
        val requested = arguments.string("ticketId") ?: return refused("$GET needs a ticketId")
        return findTicket(stream, requested, getenv).fold({ ok(it) }, { refused(it.message ?: "no ticket '$requested'") })
    }

    /**
     * MCP-MOVE-1: move ONE ticket to a status over `ticket_move` -- the frame `agenthq ticket move` sends -- so an agent
     * can finish its ticket (`done`) without the CLI. A move INTO in_progress is the board's start and goes through
     * [START] (guardrails, worker); `pending` is derived by the daemon and refused there. The session is named as origin.
     */
    suspend fun move(stream: FrameStream, arguments: JsonObject): ToolResult {
        val requested = arguments.string("ticketId") ?: return refused("$MOVE needs a ticketId")
        val status = arguments.string("status")?.lowercase() ?: return refused("$MOVE needs a status")
        if (status == TicketStatus.IN_PROGRESS) return refused("$MOVE refused: to start a ticket use $START")
        val ticket = findTicket(stream, requested, getenv).getOrElse { return refused("$MOVE refused: ${it.message ?: "no ticket '$requested'"}") }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val answer = try {
            // NOT idempotent: a dropped request is reported, never re-sent blind.
            stream.request(
                Envelope(Envelope.TICKET_MOVE, json.encodeToString(com.superiorsoftllc.agenthq.protocol.TicketMove.serializer(), com.superiorsoftllc.agenthq.protocol.TicketMove(id = ticket.id, status = status)), session),
                answerType = Envelope.TICKET,
                what = "ticket_move",
                idempotent = false,
            )
        } catch (e: Exception) {
            return refused("$MOVE refused: ${e.message ?: e.javaClass.simpleName}")
        }
        val moved = runCatching { json.decodeFromString(Ticket.serializer(), answer.data) }
            .getOrElse { return refused("$MOVE: malformed ticket answer from the daemon: ${it.message}") }
        return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(summary(moved) + buildJsonObject {
            put("message", "Moved ticket ${shortTicketId(moved.id)} -> ${moved.status}")
        })))
    }

    /**
     * MCP-DEL-1: delete ONE ticket over `ticket_delete` -- the frame the CLI's `ticket delete` sends -- after resolving
     * the id exactly like [update] (a full id, or the 8-char suffix). An unknown or ambiguous id is refused before any
     * frame is sent. A ticket IN PROGRESS is refused HERE, MCP-side: its live worker holds it, so the agent must stop
     * that worker first. The board's own delete is unchanged for the human (the UI and CLI may still remove one).
     * Answers the deleted ticket's id and title, read from the resolved ticket.
     */
    suspend fun delete(stream: FrameStream, arguments: JsonObject): ToolResult {
        val requested = arguments.string("ticketId") ?: return refused("$DELETE needs a ticketId")
        val ticket = findTicket(stream, requested, getenv).getOrElse { return refused("$DELETE refused: ${it.message ?: "no ticket '$requested'"}") }
        val short = shortTicketId(ticket.id)
        if (ticket.status == TicketStatus.IN_PROGRESS) {
            return refused(
                "$DELETE refused: ticket $short is in progress (worker session ${ticket.sessionId ?: "unknown"}); " +
                    "stop its worker first (agenthq_kill), then delete it.",
            )
        }
        val answer = try {
            // NOT idempotent: a dropped request is reported, never re-sent blind.
            stream.request(Envelope(Envelope.TICKET_DELETE, ticket.id), answerType = Envelope.TICKETS, what = "ticket_delete", idempotent = false)
        } catch (e: Exception) {
            return refused("$DELETE refused: ${e.message ?: e.javaClass.simpleName}")
        }
        val board = runCatching { json.decodeFromString(ListSerializer(Ticket.serializer()), answer.data) }
            .getOrElse { return refused("$DELETE: malformed board answer from the daemon: ${it.message}") }
        if (board.any { it.id == ticket.id }) return refused("$DELETE: the daemon answered but ticket $short is still on the board")
        return ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ticketId", ticket.id)
            put("title", ticket.title)
            put("deleted", true)
            put("message", "Deleted ticket $short: ${ticket.title}")
        }))
    }

    /**
     * BASE-1c (contract 1.82.0): read, set or clear ONE epic's base branch over `epic_base` -- the frame
     * `agenthq epic base` sends -- so the daemon runs the same set-time check. Answers the epic's id, name, project and
     * base branch ("" = none).
     */
    suspend fun epicBase(stream: FrameStream, arguments: JsonObject): ToolResult {
        val epicId = arguments.string("epicId") ?: return refused("$EPIC_BASE needs an epicId")
        val clear = (arguments["clear"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.trim() == "true") } ?: false
        val request = com.superiorsoftllc.agenthq.epics.EpicBaseRequest(epicId, arguments.string("branch"), clear)
        val answer = try {
            // A set or clear writes the same value again on a re-send, so it is safe to repeat after a dropped link.
            stream.request(
                Envelope(Envelope.EPIC_BASE, json.encodeToString(com.superiorsoftllc.agenthq.epics.EpicBaseRequest.serializer(), request)),
                answerType = Envelope.EPIC,
                what = "epic_base",
                idempotent = true,
            )
        } catch (e: Exception) {
            return refused("$EPIC_BASE refused: ${e.message ?: e.javaClass.simpleName}")
        }
        val epic = runCatching { json.decodeFromString(com.superiorsoftllc.agenthq.epics.Epic.serializer(), answer.data) }
            .getOrElse { return refused("$EPIC_BASE: malformed epic answer from the daemon: ${it.message}") }
        return ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("epicId", epic.id)
            put("name", epic.name)
            put("projectId", epic.projectId)
            put("baseBranch", epic.baseBranch)
        }))
    }

    /**
     * BUG-ASSIGN-1: the answer to a create or an update SAYS what was saved, read from the daemon's answer (never echoed
     * from the request): the fields this call set that the stored ticket now holds, each with its stored value, and the
     * next step. An agent that pinned a role, provider and model reads back that they were saved.
     */
    private fun saved(ticket: Ticket, save: TicketSave, created: Boolean): ToolResult {
        val role = TicketStarter.roleOf(ticket)
        val fields = buildList {
            if (save.title != null) add("title" to "title")
            if (save.description != null) add("description" to "description")
            if (save.agentId != null && ticket.agentId != null) add("role" to "role ${role ?: ticket.agentId}")
            if (save.provider != null && ticket.provider != null) add("provider" to "provider ${ticket.provider}")
            if (save.model != null && ticket.model != null) add("model" to "model ${ticket.model}")
            if (save.weight != null) add("weight" to "weight ${ticket.weight ?: "cleared"}")
            if (save.images != null) add("images" to "${ticket.images.size} image(s)")
        }
        val short = shortTicketId(ticket.id)
        val what = fields.joinToString(", ") { it.second }.ifEmpty { "nothing new" }
        val next = if (ticket.status == TicketStatus.IN_PROGRESS) {
            "It is in progress: its running worker keeps what it started with, and these apply to its next start."
        } else {
            "Nothing runs until it is started: call $START with ticketId $short."
        }
        val message = (if (created) "Created ticket $short (${ticket.status}); saved $what. " else "Saved on ticket $short: $what. ") + next
        return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(summary(ticket) + buildJsonObject {
            put("saved", buildJsonArray { fields.forEach { add(JsonPrimitive(it.first)) } })
            put("message", message)
        })))
    }

    private suspend inline fun save(stream: FrameStream, save: TicketSave, what: String, fail: (ToolResult) -> Nothing): Ticket {
        val answer = try {
            // NOT idempotent: a request that drops after it was sent is reported, never re-sent (a second ticket).
            stream.request(
                Envelope(Envelope.TICKET_SAVE, json.encodeToString(TicketSave.serializer(), save)),
                answerType = Envelope.TICKET,
                what = "ticket_save",
                idempotent = false,
            )
        } catch (e: Exception) {
            fail(refused("$what refused: ${e.message ?: e.javaClass.simpleName}"))
        }
        return runCatching { json.decodeFromString(Ticket.serializer(), answer.data) }
            .getOrElse { fail(refused("$what: malformed ticket answer from the daemon: ${it.message}")) }
    }

    private fun projectOf(arguments: JsonObject): String? =
        arguments.string("projectId")
            ?: defaultProjectId?.trim()?.takeIf { it.isNotEmpty() }
            ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    private fun ok(ticket: Ticket) = ToolResult(json.encodeToString(JsonObject.serializer(), summary(ticket)))

    private fun refused(text: String) = ToolResult(text, isError = true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * IMG-3: the `images` argument, an array of path strings; null when absent (keep the stored ones), `[]` clears.
     * Anything but an array of strings is refused here; WHERE each path points is the daemon's check (uploads/ticket).
     */
    private inline fun JsonObject.images(tool: String, key: String = "images", fail: (ToolResult) -> Nothing): List<String>? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        val array = value as? JsonArray ?: fail(refused("$tool refused: $key must be an array of paths"))
        return array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: fail(refused("$tool refused: $key must be an array of paths, got $item"))
        }
    }

    /** IMG-AGENT-1: [paths] as stored relPaths, each local file uploaded ([TicketImageFiles]); a refusal saves nothing. */
    private suspend inline fun attach(
        stream: FrameStream,
        projectId: String?,
        paths: List<String>,
        tool: String,
        fail: (ToolResult) -> Nothing,
    ): List<String> = try {
        TicketImageFiles.upload(stream, projectId, paths)
    } catch (e: Exception) {
        fail(refused("$tool refused: ${e.message ?: e.javaClass.simpleName}"))
    }

    /** A text field kept exactly as given (newlines, indentation, trailing spaces); null only when absent. */
    private fun JsonObject.weight(): Int? = (this["weight"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun JsonObject.verbatim(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val CREATE = "agenthq_ticket_create"
        const val UPDATE = "agenthq_ticket_update"
        const val GET = "agenthq_ticket_get"

        /** BUG-ASSIGN-1: start ONE ticket through the board (served by [McpWriteTools.startTicket], under its guardrails). */
        const val START = "agenthq_ticket_start"
        /** BASE-1c (contract 1.82.0): read, set or clear an epic's base branch (the epic's tickets cut from it). */
        const val EPIC_BASE = "agenthq_epic_base"
        /** MCP-DEL-1: delete ONE ticket (refused while it is in progress). */
        const val DELETE = "agenthq_ticket_delete"
        /** MCP-MOVE-1: move ONE ticket to a status (done, todo, ...); a start goes through [START]. */
        const val MOVE = "agenthq_ticket_move"
        val NAMES = listOf(CREATE, UPDATE, GET, START, EPIC_BASE, DELETE, MOVE)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        /** `--role <id>` / `role` as the ticket's `agentId`: `role:<id>`; a value already written `role:<id>` is kept. */
        internal fun roleAgentId(role: String?): String? =
            role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("role:")) it else "role:$it" }

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
                        "ticket id '$requested' is ambiguous; use the full id: ${match.tickets.joinToString(", ") { it.id }}",
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
            put("effectiveProvider", ticket.effectiveExecutor?.provider?.value)
            put("effectiveProviderSource", ticket.effectiveExecutor?.provider?.source)
            put("effectiveModel", ticket.effectiveExecutor?.model?.value)
            put("effectiveModelSource", ticket.effectiveExecutor?.model?.source)
            put("branch", ticket.branch)
            put("sessionId", ticket.sessionId)
            put("images", buildJsonArray { ticket.images.forEach { add(JsonPrimitive(it)) } })
        }

        fun descriptors() = buildJsonArray {
            add(tool(
                CREATE,
                "Create a ticket on the Infantry AI board (it lands in todo; nothing runs until $START starts it). " +
                    "The description is stored verbatim, multi-line included. role / provider / model pin who runs " +
                    "it (agenthq_list_harnesses lists the provider ids and their models); the daemon refuses an " +
                    "unknown provider or a model that provider does not list. The answer says what was saved.",
                readOnly = false,
                required = listOf("title"),
            ) {
                put("title", prop("Ticket title."))
                put("description", prop("Full brief, Markdown, any number of lines; stored exactly as given."))
                put("projectId", prop("Project the ticket belongs to (default: this session's project)."))
                put("role", prop("Role that runs it (stored as role:<id>), e.g. backend."))
                put("provider", prop("Harness (provider id) that runs it, e.g. claude or opencode: see agenthq_list_harnesses."))
                put("model", prop("Model pin, one of that harness's models: see agenthq_list_harnesses."))
                put("weight", JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Ticket weight 1 (light) to 5 (heavy); 0 clears it on update. A weighted ticket with no role/provider/model pin starts on the project's weight mapping (setting tickets.weightExecutors); an explicit pin always wins."))))
                put("images", pathsProp("Images to attach: local image file paths (png, jpeg, gif or webp, at most 10 MB each; each is uploaded to the project's .agenthq/uploads/ticket/), or paths already stored there. A missing, oversized or non-image file refuses the call and nothing is saved. The board shows them as attachments and the started worker's brief lists them."))
            })
            add(tool(
                UPDATE,
                "Edit ONE ticket (full id or the 8-char id `tickets` prints). A field left out keeps its stored " +
                    "value; the description, when given, replaces the stored one verbatim. Set role / provider / " +
                    "model here BEFORE $START: a start runs what the ticket holds. The answer says what was saved.",
                readOnly = false,
                required = listOf("ticketId"),
            ) {
                put("ticketId", prop("Full ticket id, or its 8-char suffix."))
                put("title", prop("New title."))
                put("description", prop("New full description, stored exactly as given."))
                put("role", prop("Role that runs it (stored as role:<id>)."))
                put("provider", prop("Harness (provider id) pin: see agenthq_list_harnesses."))
                put("model", prop("Model pin, one of that harness's models: see agenthq_list_harnesses."))
                put("weight", JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Ticket weight 1 (light) to 5 (heavy); 0 clears it on update. A weighted ticket with no role/provider/model pin starts on the project's weight mapping (setting tickets.weightExecutors); an explicit pin always wins."))))
                put("images", pathsProp("The ticket's full image list, replacing the stored one ([] removes all): local image files (uploaded, as for $CREATE) or paths already under the project's .agenthq/uploads/ticket/. Not with addImages / removeImages. A removed image's file is deleted once no ticket references it."))
                put("addImages", pathsProp("Images to ADD to the stored list: local image files (png, jpeg, gif or webp, at most 10 MB each) or stored paths."))
                put("removeImages", pathsProp("Images to REMOVE from the stored list, each named by its stored path (as $GET answers it), its file name, or the original local file. Naming an image the ticket does not have refuses the call."))
            })
            add(tool(
                GET,
                "Read ONE ticket back: title, verbatim description, status, project, role/agent, provider, model, " +
                    "branch, worker session, attached images.",
                readOnly = true,
                required = listOf("ticketId"),
            ) {
                put("ticketId", prop("Full ticket id, or its 8-char suffix."))
            })
            add(tool(
                START,
                "Start ONE ticket: THE way to put an agent to work on a ticket. It is the board's own start (the card " +
                    "moving to In Progress): the worker runs the ticket's role, provider and model, in its own git " +
                    "worktree on branch ticket/<short-id>-<slug>, and the ticket goes In Progress with you recorded " +
                    "as its starter. Pin role / provider / model first with $CREATE or $UPDATE. A ticket whose " +
                    "lane is busy is queued; one held by open prerequisites, or that cannot start, is refused with " +
                    "the reason. Starting a ticket already in progress returns its worker. Guarded like agenthq_spawn.",
                readOnly = false,
                required = listOf("ticketId"),
            ) {
                put("ticketId", prop("Full ticket id, or its 8-char suffix."))
            })
            add(tool(
                EPIC_BASE,
                "Read, set or clear ONE epic's base branch: new tickets in the epic cut their branch from it, " +
                    "overriding the project's base. A branch that does not resolve where tickets are cut is refused " +
                    "with the reason. Tickets already started keep their recorded base.",
                readOnly = false,
                required = listOf("epicId"),
            ) {
                put("epicId", prop("Full epic id."))
                put("branch", prop("Branch to set; omit (with clear unset) to read."))
                put("clear", boolProp("true removes the setting; the epic's tickets fall back to the project's base."))
            })
            add(tool(
                DELETE,
                "Delete ONE ticket from the board (full id or the 8-char id `tickets` prints), with its links. An " +
                    "unknown or ambiguous id is refused and nothing is deleted. A ticket in progress is refused: stop " +
                    "its worker first. One ticket per call; there is no bulk delete. The answer names the deleted " +
                    "ticket's id and title.",
                readOnly = false,
                destructive = true,
                required = listOf("ticketId"),
            ) {
                put("ticketId", prop("Full ticket id, or its 8-char suffix."))
            })
            add(tool(
                MOVE,
                "Move ONE ticket to a status (full id or 8-char id), e.g. done when your work is finished, or todo. " +
                    "Same as `agenthq ticket move`. To START a ticket use $START; pending is set by the daemon from " +
                    "open prerequisites and is refused here. The answer reads back the stored ticket.",
                readOnly = false,
                required = listOf("ticketId", "status"),
            ) {
                put("ticketId", prop("Full ticket id, or its 8-char suffix."))
                put("status", prop("Target status, e.g. done, todo, review."))
            })
        }

        private fun pathsProp(description: String) = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", description)
        }

        private fun boolProp(description: String) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
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
}
