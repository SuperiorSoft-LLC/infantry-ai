package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.cli.BoardSummary
import com.superiorsoftllc.infantry.cli.TicketPrefixMatch
import com.superiorsoftllc.infantry.cli.matchTicketPrefix
import com.superiorsoftllc.infantry.cli.mcp.ProjectScope
import com.superiorsoftllc.infantry.cli.shortTicketId
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.Board
import com.superiorsoftllc.infantry.daemon.api.board.BoardColumn
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.PromptStart
import com.superiorsoftllc.infantry.daemon.api.board.EpicInfo
import com.superiorsoftllc.infantry.daemon.api.board.Epics
import com.superiorsoftllc.infantry.daemon.api.board.Page
import com.superiorsoftllc.infantry.daemon.api.board.StartResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketBinding
import com.superiorsoftllc.infantry.daemon.api.board.TicketDraft
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketLinkInfo
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import com.superiorsoftllc.infantry.daemon.api.board.TicketQuery
import com.superiorsoftllc.infantry.daemon.api.board.Tickets
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.epics.Epic
import com.superiorsoftllc.infantry.mvp.ProjectBaseCheck
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStart
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionRecord
import java.util.concurrent.ConcurrentHashMap
import com.superiorsoftllc.infantry.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.infantry.protocol.TicketListQuery
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * DP-4a (docs/DAEMON-BOARD-API.md): the LOCAL binding of [Tickets], [Epics] and [Board] over the board plugin's [BoardOps]. The HOST applies, before the board is touched and the same way for every plugin:
 *  1. the active policy preset, as the equivalent frame is gated (the preset's neutral text, byte for byte): [gate];
 *  2. the project scope, with the very rules of the MCP tools' [ProjectScope] (one implementation: this class calls it), seeing
 *     the caller as the daemon's session record for [Caller.activeSession] (never a request argument). No active session on an
 *     owner connection is the owner / CLI path; an agent connection with no session, or a session the daemon does not know, is
 *     refused (fail closed).
 * Behaviour is the board's: the same calls the frame handlers make, the same texts (`<frame> refused: <why>`).
 */
class LocalBoardServices(
    private val ops: () -> BoardOps?,
    private val sessions: () -> List<SessionRecord>,
    private val link: () -> OrchestratorLink?,
    /** The active preset's refusal of a frame of this type and payload, or null. */
    private val gate: (type: String, data: String) -> String?,
    /**
     * RUNTIME-LOAD-6: a start's move, charged to the starter's pod (`daemon.TicketStartCharge`); null = the board's move as is
     * (a test without a spawn guard).
     */
    private val chargedMove: ((TicketMove, String?) -> Ticket)? = null,
) {
    private val createKeys = ConcurrentHashMap<String, String>()

    // ---- the host's scope --------------------------------------------------------------------------------------------

    private class Who(val by: Caller, val record: SessionRecord?) {
        val getenv: (String) -> String? = { name -> if (name == AccessPolicy.SESSION_ID_ENV) by.activeSession?.takeIf { it.isNotBlank() } else null }
    }

    private fun who(by: Caller): Pair<Who?, String?> {
        val id = by.activeSession?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) return if (by.agent && !by.owner) null to "an agent connection needs a session to be scoped to a project" else Who(by, null) to null
        val record = sessions().firstOrNull { it.id == id } ?: return null to "session '${id.take(80)}' is unknown to this daemon; a board call cannot be scoped to it"
        return Who(by, record) to null
    }

    private fun foreign(w: Who, what: String, project: String?): String? = ProjectScope.foreignRefusal(what, project, w.record, w.getenv)

    private fun scopeOf(w: Who, requested: String?): ProjectScope = ProjectScope.resolve(requested, sessions(), w.getenv, null)

    // ---- plumbing -----------------------------------------------------------------------------------------------------

    private inline fun <T> op(frame: String, by: Caller, data: String = "", body: (BoardOps, Who) -> BoardResult<T>): BoardResult<T> {
        val b = ops() ?: return BoardResult.Unavailable("this daemon has no board")
        gate(frame, data)?.let { return BoardResult.Refused(it) }
        val (w, why) = who(by)
        if (w == null) return BoardResult.Refused("$frame refused: $why")
        return try {
            body(b, w)
        } catch (e: IllegalArgumentException) {
            BoardResult.Refused("$frame refused: ${e.message ?: "invalid request"}")
        }
    }

    /** The one ticket [ref] names (full id, head or tail), across the whole board, then the caller's scope. */
    private fun resolve(b: BoardOps, w: Who, ref: String): Pair<Ticket?, String?> =
        when (val match = matchTicketPrefix(ref, b.list(null, null))) {
            TicketPrefixMatch.None -> null to "no ticket '$ref' on this daemon"
            is TicketPrefixMatch.Ambiguous -> null to ("ticket id '$ref' is ambiguous; use a longer id or the full id: " +
                match.tickets.joinToString("; ") { "${it.id} (${BoardSummary.cutTitle(it.title)})" })
            is TicketPrefixMatch.Unique -> {
                val refusal = foreign(w, "ticket ${shortTicketId(match.ticket.id)}", match.ticket.projectId)
                if (refusal == null) match.ticket to null else null to refusal
            }
        }

    private inline fun <T> withTicket(frame: String, ref: String, by: Caller, data: String = "", body: (BoardOps, Who, Ticket) -> BoardResult<T>): BoardResult<T> =
        op(frame, by, data) { b, w ->
            val (ticket, why) = resolve(b, w, ref)
            if (ticket == null) BoardResult.Refused(why!!) else body(b, w, ticket)
        }

    // ---- tickets ------------------------------------------------------------------------------------------------------

    val tickets: Tickets = object : Tickets {
        override fun get(id: String, by: Caller): BoardResult<TicketInfo> =
            withTicket(Envelope.TICKET_GET, id, by) { _, _, t -> BoardResult.Ok(info(t)) }

        override fun list(by: Caller, query: TicketQuery): BoardResult<Page<TicketInfo>> = op(Envelope.TICKET_LIST, by) { b, w ->
            val scope = scopeOf(w, query.projectId)
            scope.refusal?.let { return@op BoardResult.Refused("ticket_list refused: $it") }
            val all = b.list(scope.projectId, query.epicId?.trim()?.takeIf { it.isNotEmpty() })
                .filter { query.status.isEmpty() || it.status in query.status }
            val from = query.cursor?.let { c ->
                val at = all.indexOfFirst { it.id == c }
                if (at < 0) return@op BoardResult.Refused("ticket_list refused: unknown cursor '${c.take(80)}'")
                at + 1
            } ?: 0
            val size = query.limit.coerceIn(1, TicketQuery.MAX_LIMIT)
            val page = all.drop(from).take(size)
            val more = from + page.size < all.size
            BoardResult.Ok(Page(page.map(::info), if (more) page.last().id else null))
        }

        override fun create(draft: TicketDraft, by: Caller): BoardResult<TicketInfo> = op(Envelope.TICKET_SAVE, by) { b, w ->
            val own = scopeOf(w, null)
            val projectId = draft.projectId?.trim()?.takeIf { it.isNotEmpty() } ?: own.projectId.takeIf { w.record != null }
            if (draft.projectId != null) foreign(w, "the target project", projectId)?.let { return@op BoardResult.Refused("ticket_save refused: $it") }
            val key = draft.clientKey?.trim()?.takeIf { it.isNotEmpty() }?.let { "${w.by.activeSession ?: "owner"}:$it" }
            key?.let { createKeys[it] }?.let { known -> runCatching { b.get(known) }.getOrNull()?.let { return@op BoardResult.Ok(info(it)) } }
            val save = TicketSave(
                title = draft.title, description = draft.description, projectId = projectId, epicId = draft.epicId, ticketType = draft.ticketType,
                agentId = draft.agentId, provider = draft.provider, model = draft.model, weight = draft.weight, images = draft.images, status = draft.status,
                contextLimit = draft.contextLimit, effort = draft.effort,
            )
            val created = b.save(save)
            key?.let { createKeys[it] = created.id }
            BoardResult.Ok(info(created))
        }

        override fun update(id: String, patch: TicketPatch, by: Caller): BoardResult<TicketInfo> = withTicket(Envelope.TICKET_SAVE, id, by) { b, _, t ->
            val save = TicketSave(
                id = t.id, title = patch.title, description = patch.description, ticketType = patch.ticketType, epicId = patch.epicId,
                agentId = patch.agentId, provider = patch.provider, model = patch.model, weight = patch.weight, images = patch.images,
                contextLimit = patch.contextLimit, effort = patch.effort,
            )
            BoardResult.Ok(info(b.save(save)))
        }

        override fun move(id: String, status: String?, position: Int?, by: Caller): BoardResult<TicketInfo> =
            withTicket(Envelope.TICKET_MOVE, id, by) { b, w, t ->
                val wanted = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                when {
                    wanted == null && position == null -> BoardResult.Refused("ticket_move refused: give a status or a position")
                    wanted == TicketStatus.IN_PROGRESS -> BoardResult.Refused("ticket_move refused: to start a ticket use start")
                    wanted == t.status && position == null -> BoardResult.Ok(info(t))
                    else -> BoardResult.Ok(info(b.move(TicketMove(id = t.id, status = wanted, position = position), starterOf(w))))
                }
            }

        override fun delete(id: String, by: Caller): BoardResult<Unit> = withTicket(Envelope.TICKET_DELETE, id, by) { b, _, t ->
            b.delete(t.id)
            BoardResult.Ok(Unit)
        }

        override fun link(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo> = linkOp(Envelope.TICKET_LINK, from, to, kind, by) { b, r -> b.link(r) }
        override fun unlink(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo> = linkOp(Envelope.TICKET_UNLINK, from, to, kind, by) { b, r -> b.unlink(r) }

        private fun linkOp(frame: String, from: String, to: String, kind: String, by: Caller, call: (BoardOps, TicketLinkRequest) -> Ticket): BoardResult<TicketInfo> =
            op(frame, by) { b, w ->
                val (a, whyA) = resolve(b, w, from)
                val (z, whyZ) = resolve(b, w, to)
                when {
                    a == null -> BoardResult.Refused(whyA!!)
                    z == null -> BoardResult.Refused(whyZ!!)
                    else -> BoardResult.Ok(info(call(b, TicketLinkRequest(ticketId = a.id, relatedId = z.id, type = kind))))
                }
            }

        override fun reorder(status: String, ids: List<String>, by: Caller): BoardResult<Unit> = op(Envelope.TICKET_REORDER, by) { b, w ->
            val full = ids.map { ref ->
                val (t, why) = resolve(b, w, ref)
                (t ?: return@op BoardResult.Refused(why!!)).id
            }
            b.reorder(TicketReorder(status = status, ids = full))
            BoardResult.Ok(Unit)
        }

        override fun start(id: String, by: Caller): BoardResult<StartResult> {
            // A start is a spawn: a preset that refuses `ticket_start_next` refuses it here too, besides the move's own gate.
            gate(Envelope.TICKET_START_NEXT, "")?.let { return BoardResult.Refused(it) }
            return withTicket(Envelope.TICKET_MOVE, id, by) { b, w, t ->
                val move = TicketMove(id = t.id, status = TicketStatus.IN_PROGRESS)
                val answer = chargedMove?.invoke(move, starterOf(w)) ?: b.move(move, starterOf(w))
                val start = answer.start ?: TicketStart(ok = false, reason = "start_refused", detail = "the board gave no start answer")
                BoardResult.Ok(StartResult(info(answer), start.ok, start.reason, start.detail))
            }
        }

        // DP-5b: the hand-outs a trigger makes. Daemon-internal, so ungated and unscoped exactly as the direct board call was.
        override fun startNext(agentFilter: String?, by: Caller): BoardResult<StartResult?> {
            val b = ops() ?: return BoardResult.Unavailable("this daemon has no board")
            val answer = b.startNext(agentFilter) ?: return BoardResult.Ok(null)
            val start = answer.start
            return BoardResult.Ok(StartResult(info(answer), start?.ok ?: false, start?.reason ?: "", start?.detail))
        }

        override fun startPrompt(agentId: String, prompt: String, projectId: String?, by: Caller): BoardResult<PromptStart> {
            val b = ops() ?: return BoardResult.Unavailable("this daemon has no board")
            return BoardResult.Ok(
                b.startPrompt(agentId, prompt, projectId),
            )
        }

        override fun starterOf(sessionId: String, by: Caller): BoardResult<String?> = readOp(sessionId, by) { l ->
            when (val r = l.starterOf(sessionId)) {
                is OrchestratorReply.Ok -> BoardResult.Ok(r.value)
                is OrchestratorReply.Unavailable -> BoardResult.Unavailable(r.reason)
            }
        }

        override fun ticketOf(sessionId: String, by: Caller): BoardResult<String?> = readOp(sessionId, by) {
            BoardResult.Ok(sessions().firstOrNull { it.id == sessionId }?.ticketId?.takeIf { it.isNotBlank() })
        }

        override fun workerOf(ticketId: String, by: Caller): BoardResult<TicketBinding?> {
            val l = link() ?: return BoardResult.Unavailable("this daemon has no orchestrator")
            gate(Envelope.TICKET_GET, "")?.let { return BoardResult.Refused(it) }
            val (w, why) = who(by)
            if (w == null) return BoardResult.Refused("ticket_get refused: $why")
            return when (val r = l.ticketOf(ticketId)) {
                is OrchestratorReply.Unavailable -> BoardResult.Unavailable(r.reason)
                is OrchestratorReply.Ok -> {
                    val ref = r.value
                    val refusal = ref?.let { foreign(w, "ticket ${shortTicketId(ticketId)}", it.projectId) }
                    if (refusal != null) BoardResult.Refused(refusal) else BoardResult.Ok(ref?.let { TicketBinding(it.sessionId, it.projectId) })
                }
            }
        }

        override fun mayFile(projectId: String, by: Caller): BoardResult<Unit> = op(Envelope.TICKET_SAVE, by) { _, w ->
            foreign(w, "the target project", projectId.trim())?.let { BoardResult.Refused(it) } ?: BoardResult.Ok(Unit)
        }

        /** A read about a session: the link must exist, the policy allows reads, and the session must be in the caller's scope. */
        private inline fun <T> readOp(sessionId: String, by: Caller, body: (OrchestratorLink) -> BoardResult<T>): BoardResult<T> {
            val l = link() ?: return BoardResult.Unavailable("this daemon has no orchestrator")
            gate(Envelope.TICKET_GET, "")?.let { return BoardResult.Refused(it) }
            val (w, why) = who(by)
            if (w == null) return BoardResult.Refused("ticket_get refused: $why")
            sessions().firstOrNull { it.id == sessionId }?.let { s -> foreign(w, "session ${sessionId.take(80)}", s.projectId)?.let { return BoardResult.Refused(it) } }
            return body(l)
        }
    }

    /** The calling session as a ticket's starter: only a session the daemon holds is recorded (a UI move records none). */
    private fun starterOf(w: Who): String? = w.record?.id

    // ---- epics --------------------------------------------------------------------------------------------------------

    val epics: Epics = object : Epics {
        private inline fun <T> epicOp(frame: String, by: Caller, data: String = "", body: (BoardOps, Who) -> BoardResult<T>): BoardResult<T> {
            val store = ops() ?: return BoardResult.Unavailable("this daemon has no board")
            gate(frame, data)?.let { return BoardResult.Refused(it) }
            val (w, why) = who(by)
            if (w == null) return BoardResult.Refused("$frame refused: $why")
            return try {
                body(store, w)
            } catch (e: IllegalArgumentException) {
                BoardResult.Refused("$frame refused: ${e.message ?: "invalid request"}")
            }
        }

        private fun epicOf(store: BoardOps, w: Who, id: String): Pair<Epic?, String?> {
            val epic = store.epicGet(id) ?: return null to "unknown epic '${id.trim().take(80)}'"
            val refusal = foreign(w, "epic ${epic.id}", epic.projectId)
            return if (refusal == null) epic to null else null to refusal
        }

        override fun get(id: String, by: Caller): BoardResult<EpicInfo> = epicOp(Envelope.EPIC_LIST, by) { s, w ->
            val (epic, why) = epicOf(s, w, id)
            if (epic == null) BoardResult.Refused(why!!) else BoardResult.Ok(info(epic))
        }

        override fun list(projectId: String?, by: Caller): BoardResult<List<EpicInfo>> = epicOp(Envelope.EPIC_LIST, by) { s, w ->
            val scope = scopeOf(w, projectId)
            scope.refusal?.let { return@epicOp BoardResult.Refused("epic_list refused: $it") }
            val project = scope.projectId ?: return@epicOp BoardResult.Refused("epic_list refused: name a project")
            BoardResult.Ok(s.epicList(project).map(::info))
        }

        override fun create(projectId: String, name: String, goal: String, by: Caller): BoardResult<EpicInfo> = epicOp(Envelope.EPIC_SAVE, by) { s, w ->
            foreign(w, "the target project", projectId)?.let { return@epicOp BoardResult.Refused("epic_save refused: $it") }
            BoardResult.Ok(info(s.epicSave(projectId, name, goal)))
        }

        override fun setBase(id: String, branch: String?, clear: Boolean, by: Caller): BoardResult<EpicInfo> = epicOp(Envelope.EPIC_BASE, by) { s, w ->
            require(!(clear && branch != null)) { "pass a branch or clear, not both" }
            val (epic, why) = epicOf(s, w, id)
            if (epic == null) return@epicOp BoardResult.Refused(why!!)
            val result = when {
                clear -> s.epicSetBase(epic.id, null)
                branch != null -> s.epicSetBase(epic.id, ProjectBaseCheck.checkName(branch))
                else -> epic
            }
            BoardResult.Ok(info(result))
        }
    }

    // ---- board --------------------------------------------------------------------------------------------------------

    val boardView: Board = object : Board {
        override fun columns(projectId: String?, by: Caller): BoardResult<List<BoardColumn>> = op(Envelope.TICKET_LIST, by) { b, w ->
            val scope = scopeOf(w, projectId)
            scope.refusal?.let { return@op BoardResult.Refused("ticket_list refused: $it") }
            val byStatus = b.list(scope.projectId, null).groupBy { it.status }
            BoardResult.Ok(TicketStatus.ALL.map { status -> BoardColumn(status, byStatus[status].orEmpty().sortedBy { it.position }.map(::info)) })
        }

        override fun brief(ticketId: String, by: Caller): BoardResult<String?> = withTicket(Envelope.TICKET_GET, ticketId, by) { b, _, t ->
            val worker = t.sessionId?.let { id -> sessions().firstOrNull { it.id == id } }
            BoardResult.Ok(worker?.let { b.brief(it.id) })
        }
    }

    companion object {
        fun info(t: Ticket) = TicketInfo(
            id = t.id, title = t.title, description = t.description, ticketType = t.ticketType, projectId = t.projectId, epicId = t.epicId,
            status = t.status, position = t.position, wontFix = t.wontFix, links = t.links.map { TicketLinkInfo(it.type, it.ticketId) },
            workTip = t.hasWork?.tip, workAhead = t.hasWork?.ahead, heldBy = t.heldBy, holdReason = t.holdReason, createdAt = t.createdAt, updatedAt = t.updatedAt, agentId = t.agentId,
            provider = t.provider, model = t.model, weight = t.weight,
            effectiveProvider = t.effectiveExecutor?.provider?.value, effectiveProviderSource = t.effectiveExecutor?.provider?.source,
            effectiveModel = t.effectiveExecutor?.model?.value, effectiveModelSource = t.effectiveExecutor?.model?.source,
            effectiveRule = t.effectiveExecutor?.rule, sessionId = t.sessionId, branch = t.branch, base = t.base,
            images = t.images, skillIds = t.skillIds,
            contextLimit = t.contextLimit, effectiveContextLimit = t.effectiveContextLimit?.value,
            effectiveContextLimitSource = t.effectiveContextLimit?.source, contextLimitSupport = t.effectiveContextLimit?.support,
            effort = t.effort, effectiveEffort = t.effectiveEffort?.value, effectiveEffortSource = t.effectiveEffort?.source,
            effortSupport = t.effectiveEffort?.support,
        )

        fun info(e: Epic) = EpicInfo(e.id, e.projectId, e.name, e.goal, e.storyIds, e.baseBranch)
    }
}

/**
 * DP-5h-3e-b: the board operations [LocalBoardServices] applies the host's policy and scope over, WITHOUT naming a board class: the plugin's
 * board ([PluginBoardOps], over [BoardCoreAccess.invoke], wire JSON). A refusal is an [IllegalArgumentException] with the board's own text on both.
 */
interface BoardOps {
    fun list(projectId: String?, epicId: String?): List<Ticket>
    fun get(id: String): Ticket
    fun save(request: TicketSave): Ticket
    fun move(request: TicketMove, startedBy: String?): Ticket
    fun delete(id: String)
    fun link(request: TicketLinkRequest): Ticket
    fun unlink(request: TicketLinkRequest): Ticket
    fun reorder(request: TicketReorder)
    fun startNext(agentFilter: String?): Ticket?
    fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptStart
    fun brief(sessionId: String): String?
    fun epicGet(id: String): Epic?
    fun epicList(projectId: String): List<Epic>
    fun epicSave(projectId: String, name: String, goal: String): Epic
    /** [branch] null clears the base; a name is checked against the epic's project (a refusal is an [IllegalArgumentException]). */
    fun epicSetBase(id: String, branch: String?): Epic
}

/** The plugin's board, reached through [BoardCoreAccess.invoke]; every value is the wire JSON. */
class PluginBoardOps(private val access: BoardCoreAccess) : BoardOps {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun ticket(s: String) = json.decodeFromString(Ticket.serializer(), s)
    private fun epic(s: String) = json.decodeFromString(Epic.serializer(), s)

    override fun list(projectId: String?, epicId: String?): List<Ticket> =
        json.decodeFromString(ListSerializer(Ticket.serializer()), access.invoke("list", json.encodeToString(TicketListQuery.serializer(), TicketListQuery(projectId = projectId, epicId = epicId)), null))
    override fun get(id: String) = ticket(access.invoke("get", id, null))
    override fun save(request: TicketSave) = ticket(access.invoke("save", json.encodeToString(TicketSave.serializer(), request), null))
    override fun move(request: TicketMove, startedBy: String?) = ticket(access.invoke("move", json.encodeToString(TicketMove.serializer(), request), startedBy))
    override fun delete(id: String) { access.invoke("delete", id, null) }
    override fun link(request: TicketLinkRequest) = ticket(access.invoke("link", json.encodeToString(TicketLinkRequest.serializer(), request), null))
    override fun unlink(request: TicketLinkRequest) = ticket(access.invoke("unlink", json.encodeToString(TicketLinkRequest.serializer(), request), null))
    override fun reorder(request: TicketReorder) { access.invoke("reorder", json.encodeToString(TicketReorder.serializer(), request), null) }
    override fun startNext(agentFilter: String?): Ticket? = access.invoke("startNext", agentFilter.orEmpty(), null).takeIf { it.isNotEmpty() }?.let(::ticket)
    override fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptStart {
        val payload = buildJsonArray { add(JsonPrimitive(agentId)); add(JsonPrimitive(prompt)); add(projectId?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()
        val answer = access.invoke("startPrompt", payload, null)
        return if (answer.startsWith("S:")) PromptStart.Started(answer.drop(2)) else PromptStart.Refused(answer.removePrefix("R:"))
    }
    override fun brief(sessionId: String) = access.brief(sessionId)
    override fun epicGet(id: String): Epic? = access.invoke("epicGet", id, null).takeIf { it.isNotEmpty() }?.let(::epic)
    override fun epicList(projectId: String): List<Epic> = json.decodeFromString(ListSerializer(Epic.serializer()), access.invoke("epicList", projectId, null))
    override fun epicSave(projectId: String, name: String, goal: String) =
        epic(access.invoke("epicSave", buildJsonArray { add(JsonPrimitive(projectId)); add(JsonPrimitive(name)); add(JsonPrimitive(goal)) }.toString(), null))
    override fun epicSetBase(id: String, branch: String?) =
        epic(access.invoke("epicSetBase", buildJsonArray { add(JsonPrimitive(id)); add(branch?.let { JsonPrimitive(it) } ?: JsonNull) }.toString(), null))
}
