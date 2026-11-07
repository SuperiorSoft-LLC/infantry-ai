// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.LocalFile
import com.superiorsoftllc.infantry.daemon.api.LocalFiles
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.ContextLimits
import com.superiorsoftllc.infantry.daemon.api.board.EffortLevels
import com.superiorsoftllc.infantry.daemon.api.board.TicketDraft
import com.superiorsoftllc.infantry.daemon.api.board.TicketImages
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-4 (F5): `infantry_ticket_create` / `infantry_ticket_update` (MCP-1, BUG-ASSIGN-1, IMG-AGENT-1, CONTEXT-LIMIT-1), moved
 * out of the CLI's MCP server. Same descriptors, answers and texts.
 *
 * THE DESCRIPTION IS SENT VERBATIM (BUG-CLI-1); every other text field is trimmed. `role` is stored as `role:<id>`. The project is
 * the call's `projectId`, else the MCP process's default ([ToolCall.clientProjectId]: its `--project` / `.infantry/mcp.json` /
 * `INFANTRY_PROJECT_ID`). Saves go through the host's Tickets API, which scopes them to the calling session; an explicit project is
 * checked ([com.superiorsoftllc.infantry.daemon.api.board.Tickets.mayFile]) before anything is uploaded for it.
 *
 * IMAGES are files on the AGENT's machine: the arguments are declared [localFiles], so `infantry mcp serve` sends their bytes
 * ([LocalFiles]); every file is checked by the shared [TicketImages] rule before the first `image_upload` (purpose `ticket`, the
 * frame `infantry image upload` sends) and nothing is saved on a refusal.
 */
internal object TicketSave {
    const val CREATE = "infantry_ticket_create"
    const val UPDATE = "infantry_ticket_update"
    const val START = "infantry_ticket_start"
    private const val IN_PROGRESS = "in_progress"
    private const val IMAGE_UPLOAD = "image_upload"
    private const val IMAGE = "image"
    private val json = Json { ignoreUnknownKeys = true }

    /** `McpWriteTools.CONTEXT_LIMIT_HELP` as the CLI's MCP server words it (infantry_spawn keeps its own copy in core). */
    const val CONTEXT_LIMIT_HELP =
        "Context limit for the session in tokens (200000, 200k, 1m; off = none, the default): a larger one for a hard, " +
            "long task, a smaller one for a small job. How it is honoured depends on the harness: infantry_list_harnesses " +
            "shows contextLimit per harness (supported / best_effort / unsupported; unsupported = recorded, not applied)."

    /** EFFORT-1: the effort argument as the CLI's MCP server words it. */
    const val EFFORT_HELP =
        "Reasoning effort for the ticket's worker: low, medium, high, xhigh or max (off = the harness default, the default); " +
            "a hard ticket gets a higher one. How it is honoured depends on the harness: infantry_list_harnesses shows effort per " +
            "harness (supported / best_effort / unsupported; unsupported = recorded, not applied)."

    const val WEIGHT_HELP =
        "Ticket weight 1-5 by how likely the ticket is to come back wrong, not its size: 4-5 wire/contract, daemon, auth or project scope, concurrency/SSR timing, Android build, or more than one module; 3 gate fixes, bisects, UI that depends on daemon behaviour; 1-2 copy, icons, renames, docs (mechanical, one owner file). 0 clears it on update. With no provider/model pin the ticket starts on the project's first matching routing rule (project_config routingRules), else the weight mapping (tickets.weightExecutors); an explicit pin always wins. Set a weight on every ticket before start."

    fun refused(text: String) = ToolResult(text, isError = true)

    /** The thrown refusal of one step: its [result] is the tool's answer. */
    class Refusal(val result: ToolResult) : RuntimeException(null, null, false, false)

    private fun fail(text: String): Nothing = throw Refusal(refused(text))

    /** `role` as the ticket's `agentId`: `role:<id>`; a value already written `role:<id>` is kept. */
    fun roleAgentId(role: String?): String? =
        role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("role:")) it else "role:$it" }

    fun weight(args: JsonObject): Int? = (args["weight"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    /** A text field kept exactly as given (newlines, indentation, trailing spaces); null only when absent. */
    fun verbatim(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `contextLimit` as [ContextLimits.parse] reads it; `off` / `0` / blank = clear ([ContextLimits.CLEAR]); absent = keep (null). */
    fun contextLimit(args: JsonObject, tool: String): Int? {
        val raw = (args["contextLimit"] as? JsonPrimitive)?.contentOrNull ?: return null
        val parsed = ContextLimits.parse(raw)
        parsed.error?.let { fail("$tool refused: $it") }
        return parsed.value ?: ContextLimits.CLEAR
    }

    /** `effort` as [EffortLevels.parse] reads it; `off` / blank = clear (""); absent = keep (null). */
    fun effort(args: JsonObject, tool: String): String? {
        val raw = (args["effort"] as? JsonPrimitive)?.contentOrNull ?: return null
        val parsed = EffortLevels.parse(raw)
        parsed.error?.let { fail("$tool refused: $it") }
        return parsed.value ?: ""
    }

    /**
     * IMG-3: an image argument, an array of paths (each as the client sent it, [LocalFiles]); null when absent (keep the stored
     * ones), `[]` clears. Anything but an array of paths is refused; WHERE each path points is the daemon's check (uploads/ticket).
     */
    fun images(args: JsonObject, tool: String, key: String = "images"): List<LocalFile>? {
        val value = args[key] ?: return null
        if (value is JsonNull) return null
        val array = value as? JsonArray ?: fail("$tool refused: $key must be an array of paths")
        return array.map { item -> LocalFiles.decode(item) ?: fail("$tool refused: $key must be an array of paths, got $item") }
    }

    /** IMG-AGENT-1: [files] as stored relPaths, each local file uploaded; a refusal uploads and saves nothing. */
    fun attach(call: ToolCall, projectId: String?, files: List<LocalFile>, tool: String): List<String> {
        if (files.isEmpty()) return emptyList()
        TicketImages.check(files)?.let { fail("$tool refused: $it") }
        if (projectId.isNullOrBlank()) fail("$tool refused: images need the ticket's project: pass a project id")
        return files.map { file ->
            val path = file.path.trim()
            if (TicketImages.isStored(path)) path else upload(call, projectId, file, tool)
        }.distinct()
    }

    private fun upload(call: ToolCall, projectId: String, file: LocalFile, tool: String): String {
        val payload = buildJsonObject {
            put("projectId", projectId)
            put("purpose", "ticket")
            put("fileName", file.path.trim().substringAfterLast('/').substringAfterLast('\\'))
            // No mimeType: the daemon takes the type from the bytes, which the check has already matched.
            put("base64", java.util.Base64.getEncoder().encodeToString(file.bytes ?: ByteArray(0)))
        }
        // Content-addressed: the same bytes land at the same path.
        val answer = WireTickets.request(call, IMAGE_UPLOAD, json.encodeToString(JsonObject.serializer(), payload), IMAGE)
            .getOrElse { fail("$tool refused: ${it.message}") }
        return runCatching { ((json.parseToJsonElement(answer.data) as JsonObject)["relPath"] as JsonPrimitive).content }
            .getOrElse { fail("$tool refused: malformed image answer from the daemon: ${it.message}") }
    }

    /** What a create / update set, as BUG-ASSIGN-1 reads it back from the stored ticket. */
    class Fields(
        val title: Boolean, val description: Boolean, val agentId: Boolean, val provider: Boolean, val model: Boolean,
        val weight: Boolean, val contextLimit: Boolean, val images: Boolean, val related: Boolean = false, val effort: Boolean = false,
        val epic: Boolean = false,
    )

    /**
     * BUG-ASSIGN-1: the answer SAYS what was saved, read from the daemon's answer (never echoed from the request): the fields this
     * call set that the stored ticket now holds, each with its stored value, and the next step.
     */
    fun saved(ticket: TicketInfo, set: Fields, created: Boolean, verbose: Boolean = false): ToolResult {
        val role = ticket.agentId?.takeIf { it.startsWith("role:") }?.removePrefix("role:")
        val fields = buildList {
            if (set.title) add("title" to "title")
            if (set.description) add("description" to "description")
            if (set.agentId && ticket.agentId != null) add("role" to "role ${role ?: ticket.agentId}")
            if (set.provider && ticket.provider != null) add("provider" to "provider ${ticket.provider}")
            if (set.model && ticket.model != null) add("model" to "model ${ticket.model}")
            if (set.weight) add("weight" to "weight ${ticket.weight ?: "cleared"}")
            if (set.contextLimit) add("contextLimit" to "context limit ${ticket.contextLimit?.let(ContextLimits::label) ?: "cleared"}")
            if (set.effort) add("effort" to "effort ${ticket.effort ?: "cleared"}")
            if (set.images) add("images" to "${ticket.images.size} image(s)")
            if (set.related) add("relatedTo" to "related ${ticket.links.count { it.type == RELATES_TO }} ticket(s)")
            if (set.epic) add("epic" to "epic ${ticket.epicId ?: "none"}")
        }
        val short = WireTickets.shortId(ticket.id)
        val what = fields.joinToString(", ") { it.second }.ifEmpty { "nothing new" }
        val next = if (ticket.status == IN_PROGRESS) {
            "It is in progress: its running worker keeps what it started with, and these apply to its next start."
        } else {
            "Nothing runs until it is started: call $START with ticketId $short."
        }
        val note = relatedNote(ticket.description)
        val message = (if (created) "Created ticket $short (${ticket.status}); saved $what. " else "Saved on ticket $short: $what. ") + next +
            (if (verbose) note.related.orEmpty() else "") + note.suggestion?.let { " $it" }.orEmpty()
        // TOKEN-1: the tiny ack; verbose restores the full object.
        if (!verbose) return ToolResult("ok $short ${ticket.status}" + note.suggestion?.let { " | $it" }.orEmpty())
        return ToolResult(json.encodeToString(JsonObject.serializer(), JsonObject(TicketGetTool.answer(ticket, verbose) + buildJsonObject {
            put("saved", buildJsonArray { fields.forEach { add(JsonPrimitive(it.first)) } })
            note.suggestion?.let { put("suggestion", it) }
            put("message", message)
        })))
    }

    /** KNOWLEDGE-PTR-1: the stored RELATED (auto) block's header and series suggestion, as the answer words them. */
    class RelatedNote(val related: String?, val suggestion: String?)

    private const val RELATED_BEGIN = "<!-- RELATED (auto) -->"
    private const val RELATES_TO = "relates_to"

    /** Reads the block the daemon wrote atop [description] (core's RelatedDescriptionBlock); none = nothing to say. */
    fun relatedNote(description: String): RelatedNote {
        if (!description.startsWith(RELATED_BEGIN)) return RelatedNote(null, null)
        val lines = description.lineSequence().drop(1).takeWhile { !it.startsWith("<!-- /RELATED") }.toList()
        val first = lines.firstOrNull().orEmpty()
        val head = first.takeIf { it.startsWith("RELATED (auto, ") }?.removePrefix("RELATED (auto, ")?.substringBefore(';')
        return RelatedNote(
            related = head?.let { " Related: $it, listed in the RELATED (auto) block atop the description; read those files first." },
            suggestion = lines.firstOrNull { it.startsWith("Series ") },
        )
    }

    /** KNOWLEDGE-PTR-1: `relatedTo`, ticket ids (full or 8-char) this ticket relates to; null when absent. */
    fun relatedTo(args: JsonObject, tool: String): List<String>? {
        val value = args["relatedTo"] ?: return null
        if (value is JsonNull) return null
        val array = value as? JsonArray ?: fail("$tool refused: relatedTo must be an array of ticket ids")
        return array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
                ?: fail("$tool refused: relatedTo must be an array of ticket ids")
        }.distinct()
    }

    /** Links [ticket] `relates_to` each of [ids] (the board refreshes its RELATED block); answers the ticket as stored after. */
    fun linkRelated(api: DaemonApi, call: ToolCall, ticket: TicketInfo, ids: List<String>, tool: String): TicketInfo {
        var current = ticket
        for (id in ids) {
            val other = WireTickets.find(api, call, id).getOrElse { fail("$tool: saved, but relatedTo '${id.take(80)}': ${it.message}") }
            current = result(api.tickets.link(current.id, other.id, RELATES_TO, WireTickets.by(call)), tool)
        }
        return current
    }

    fun JsonObjectBuilder.relatedToProp() = put("relatedTo", pathsProp(
        "Ticket ids (full or 8-char) this ticket relates to: stored as relates_to links, listed first in the RELATED (auto) " +
            "block the daemon writes atop the description and in the worker's start brief. On update, [] links nothing and " +
            "rebuilds that block (the `ticket related --suggest` of the CLI).",
    ))

    fun <T> result(r: BoardResult<T>, tool: String): T = when (r) {
        is BoardResult.Ok -> r.value
        is BoardResult.Refused -> fail("$tool refused: ${r.text}")
        is BoardResult.Unavailable -> fail("$tool refused: ${r.reason}")
    }

    fun prop(description: String) = ToolArgs.prop(description)

    fun pathsProp(description: String) = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", description)
    }

    fun schema(required: String, properties: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(properties))
        put("required", buildJsonArray { add(JsonPrimitive(required)) })
        put("additionalProperties", false)
    }

    fun JsonObjectBuilder.weightProp() = put("weight", JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(WEIGHT_HELP))))

    fun JsonObjectBuilder.contextLimitProp() = put("contextLimit", JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive("$CONTEXT_LIMIT_HELP Shown on the ticket as effectiveContextLimit (source ticket / default); off clears it on update."),
    )))

    fun JsonObjectBuilder.effortProp() = put("effort", JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive("$EFFORT_HELP Shown on the ticket as effectiveEffort (source ticket / rule / agent / role / default); off clears it on update."),
    )))

    fun JsonObjectBuilder.runnerProp() = put("runner", JsonObject(mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive("RUNNER-POOL-3: where the ticket starts: local, a registered runner name or any. A named runner that is unknown, down or full refuses the start; none clears the pin on update (then the project's routing rule / runners.default decides)."),
    )))

    /** Runs [body]; a [Refusal] thrown anywhere in it is the tool's answer. */
    inline fun guarded(body: () -> ToolResult): ToolResult = try { body() } catch (r: Refusal) { r.result }
}

class TicketCreateTool(private val api: DaemonApi, private val comments: TicketComments? = null) : DaemonTool {
    override val name = TicketSave.CREATE
    override val description =
        "Create a ticket on the Infantry AI board (it lands in todo, the current sprint, or in backlog with " +
            "status backlog; nothing runs until ${TicketSave.START} starts it, and a backlog ticket is never started). " +
            "The description is stored verbatim, multi-line included. role / provider / model pin who runs " +
            "it (infantry_list_harnesses lists the provider ids and their models); the daemon refuses an " +
            "unknown provider or a model that provider does not list. The answer says what was saved."
    override val inputSchema = with(TicketSave) {
        schema("title") {
            put("title", prop("Ticket title."))
            put("description", prop("Full brief, Markdown, any number of lines; stored exactly as given."))
            put("projectId", prop("Project the ticket belongs to (default: this session's project)."))
            put("role", prop("Role that runs it (stored as role:<id>), e.g. backend."))
            put("provider", prop("Harness (provider id) that runs it, e.g. claude or opencode: see infantry_list_harnesses."))
            put("model", prop("Model pin, one of that harness's models: see infantry_list_harnesses."))
            weightProp()
            contextLimitProp()
            effortProp()
            runnerProp()
            put("images", pathsProp("Images to attach: local image file paths (png, jpeg, gif or webp, at most 10 MB each; each is uploaded to the project's .infantry/uploads/ticket/), or paths already stored there. A missing, oversized or non-image file refuses the call and nothing is saved. The board shows them as attachments and the started worker's brief lists them."))
            put("status", prop("Column the new ticket enters: todo (default, the current sprint) or backlog (parked, never started until moved to todo)."))
            relatedToProp()
            CommentsWiring.notifyProps(this, update = false)
            put("verbose", ToolArgs.verboseProp())
        }
    }
    override val annotations = ToolArgs.annotations(readOnly = false)
    override val localFiles = listOf("images")

    override fun call(call: ToolCall): ToolResult = TicketSave.guarded {
        val args = call.arguments
        val tool = TicketSave.CREATE
        val title = ToolArgs.string(args, "title") ?: return TicketSave.refused("$tool needs a title")
        val explicit = ToolArgs.string(args, "projectId")
        val projectId = explicit ?: call.clientProjectId
        val by = WireTickets.by(call)
        if (explicit != null) TicketSave.result(api.tickets.mayFile(explicit, by), tool)
        // TICKET-COMMENTS-2: the notify list is checked before anything is created.
        val notify = comments?.let { c -> try { CommentsWiring.list(args, "notify")?.let(c::validateTargets) } catch (e: IllegalArgumentException) { return TicketSave.refused("$tool refused: ${e.message}") } }
        val images = TicketSave.images(args, tool)?.let { TicketSave.attach(call, projectId, it, tool) }
        val draft = TicketDraft(
            title = title,
            description = TicketReportLine.apply(TicketSave.verbatim(args, "description"), api.boardRuntime.setting(TicketReportLine.KEY)),
            projectId = projectId,
            agentId = TicketSave.roleAgentId(ToolArgs.string(args, "role")),
            provider = ToolArgs.string(args, "provider"),
            model = ToolArgs.string(args, "model"),
            weight = TicketSave.weight(args),
            contextLimit = TicketSave.contextLimit(args, tool),
            effort = TicketSave.effort(args, tool),
            runner = ToolArgs.string(args, "runner"),
            images = images,
            status = ToolArgs.string(args, "status")?.lowercase(),
        )
        val relatedTo = TicketSave.relatedTo(args, tool)
        val created = TicketSave.result(api.tickets.create(draft, by), tool)
        comments?.onCreated(created.id, created.projectId, CommentsWiring.author(api, call), notify)
        val ticket = relatedTo?.let { TicketSave.linkRelated(api, call, created, it, tool) } ?: created
        TicketSave.saved(ticket, TicketSave.Fields(
            title = true, description = draft.description != null, agentId = draft.agentId != null, provider = draft.provider != null,
            model = draft.model != null, weight = draft.weight != null, contextLimit = draft.contextLimit != null, images = draft.images != null,
            related = relatedTo != null, effort = draft.effort != null,
        ), created = true, verbose = ToolArgs.flag(args, "verbose"))
    }
}

class TicketUpdateTool(private val api: DaemonApi, private val comments: TicketComments? = null) : DaemonTool {
    override val name = TicketSave.UPDATE
    override val description =
        "Edit ONE ticket (full id or the 8-char id `tickets` prints). A field left out keeps its stored " +
            "value; the description, when given, replaces the stored one verbatim. Set role / provider / " +
            "model here BEFORE ${TicketSave.START}: a start runs what the ticket holds. The answer says what was saved."
    override val inputSchema = with(TicketSave) {
        schema("ticketId") {
            put("ticketId", prop("Full ticket id, or its 8-char suffix."))
            put("title", prop("New title."))
            put("description", prop("New full description, stored exactly as given."))
            put("role", prop("Role that runs it (stored as role:<id>)."))
            put("provider", prop("Harness (provider id) pin: see infantry_list_harnesses."))
            put("model", prop("Model pin, one of that harness's models: see infantry_list_harnesses."))
            weightProp()
            contextLimitProp()
            effortProp()
            runnerProp()
            put("images", pathsProp("The ticket's full image list, replacing the stored one ([] removes all): local image files (uploaded, as for ${TicketSave.CREATE}) or paths already under the project's .infantry/uploads/ticket/. Not with addImages / removeImages. A removed image's file is deleted once no ticket references it."))
            put("addImages", pathsProp("Images to ADD to the stored list: local image files (png, jpeg, gif or webp, at most 10 MB each) or stored paths."))
            relatedToProp()
            put("epic", prop("EPIC-MOVE-1: file the ticket under this epic (id or name; an unknown name creates it in the ticket's project), or none to clear. An in_progress ticket whose branch was cut from another base than the new epic's is refused unless force. Many at once: ${TicketEpicTool.NAME}."))
            put("force", ToolArgs.boolProp("With epic: move an in_progress ticket even when the new epic cuts from another base (its branch keeps its base)."))
            CommentsWiring.notifyProps(this, update = true)
            put("verbose", ToolArgs.verboseProp())
            put("removeImages", pathsProp("Images to REMOVE from the stored list, each named by its stored path (as infantry_ticket_get answers it), its file name, or the original local file. Naming an image the ticket does not have refuses the call."))
        }
    }
    override val annotations = ToolArgs.annotations(readOnly = false)
    override val localFiles = listOf("images", "addImages", "removeImages")

    override fun call(call: ToolCall): ToolResult = TicketSave.guarded {
        val args = call.arguments
        val tool = TicketSave.UPDATE
        val requested = ToolArgs.string(args, "ticketId") ?: return TicketSave.refused("$tool needs a ticketId")
        val replace = TicketSave.images(args, tool)
        val add = TicketSave.images(args, tool, "addImages")
        val remove = TicketSave.images(args, tool, "removeImages")
        if (replace != null && (add != null || remove != null)) {
            return TicketSave.refused("$tool refused: give images (the full list) or addImages / removeImages, not both")
        }
        val existing = WireTickets.find(api, call, requested).getOrElse { return TicketSave.refused(it.message ?: "no ticket '$requested'") }
        // IMG-AGENT-1: images replaces the list; addImages / removeImages edit the stored one. Every local file is checked, and every
        // removal must name an attached image, before anything is uploaded or saved.
        val images = when {
            replace != null -> TicketSave.attach(call, existing.projectId, replace, tool)
            add != null || remove != null -> {
                val unknown = remove.orEmpty().firstOrNull { given -> existing.images.none { TicketImages.matches(it, given) } }
                if (unknown != null) return TicketSave.refused("$tool refused: ticket ${WireTickets.shortId(existing.id)} has no image '${unknown.path.take(200)}'")
                val kept = existing.images.filterNot { stored -> remove.orEmpty().any { TicketImages.matches(stored, it) } }
                (kept + TicketSave.attach(call, existing.projectId, add.orEmpty(), tool)).distinct()
            }
            else -> null
        }
        val relatedTo = TicketSave.relatedTo(args, tool)
        // EPIC-MOVE-1: the epic move runs first, so its refusal (an in_progress ticket on another base) saves nothing else.
        val epicTarget = (args["epic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        var epicMoved = existing
        if (epicTarget != null) {
            val outcome = try {
                EpicMove.run(api.tickets, api.epics, WireTickets.by(call), listOf(existing.id), epicTarget, ToolArgs.flag(args, "force"))
            } catch (e: EpicMove.Refused) { return TicketSave.refused("$tool refused: ${e.text}") }
            outcome.refused.firstOrNull()?.let { return TicketSave.refused("$tool refused: epic not changed: ${it.reason}") }
            epicMoved = outcome.moved.firstOrNull() ?: existing
        }
        val patch = TicketPatch(
            // KNOWLEDGE-PTR-1: relatedTo (even []) re-saves the stored title, which makes the board rebuild the RELATED block.
            title = ToolArgs.string(args, "title") ?: relatedTo?.let { existing.title },
            description = TicketSave.verbatim(args, "description"),
            agentId = TicketSave.roleAgentId(ToolArgs.string(args, "role")),
            provider = ToolArgs.string(args, "provider"),
            model = ToolArgs.string(args, "model"),
            weight = TicketSave.weight(args),
            contextLimit = TicketSave.contextLimit(args, tool),
            effort = TicketSave.effort(args, tool),
            runner = ToolArgs.string(args, "runner"),
            images = images,
        )
        // TICKET-COMMENTS-2: notify set / add / remove (validated before the ticket is touched); a notify-only call changes nothing else.
        val notifyEdit = comments?.let { c ->
            try {
                val set = CommentsWiring.list(args, "notify"); val add = CommentsWiring.list(args, "addNotify").orEmpty(); val rem = CommentsWiring.list(args, "removeNotify").orEmpty()
                if (set == null && add.isEmpty() && rem.isEmpty()) null else { c.validateTargets(set.orEmpty() + add); Triple(set, add, rem) }
            } catch (e: IllegalArgumentException) { return TicketSave.refused("$tool refused: ${e.message}") }
        }
        val noFields = patch.title == null && patch.description == null && patch.agentId == null && patch.provider == null && patch.model == null &&
            patch.weight == null && patch.contextLimit == null && patch.effort == null && patch.runner == null && patch.images == null
        val updated = if ((notifyEdit != null || epicTarget != null) && noFields) epicMoved else TicketSave.result(api.tickets.update(existing.id, patch, WireTickets.by(call)), tool)
        notifyEdit?.let { (set, add, rem) -> comments?.editNotify(existing.id, set, add, rem) }
        val ticket = relatedTo?.let { TicketSave.linkRelated(api, call, updated, it, tool) } ?: updated
        TicketSave.saved(ticket, TicketSave.Fields(
            title = ToolArgs.string(args, "title") != null, description = patch.description != null, agentId = patch.agentId != null, provider = patch.provider != null,
            model = patch.model != null, weight = patch.weight != null, contextLimit = patch.contextLimit != null, images = patch.images != null,
            related = relatedTo != null, effort = patch.effort != null, epic = epicTarget != null,
        ), created = false, verbose = ToolArgs.flag(args, "verbose"))
    }
}
