package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.cli.BoardSummary
import com.superiorsoftllc.agenthq.cli.TicketPrefixMatch
import com.superiorsoftllc.agenthq.cli.matchTicketPrefix
import com.superiorsoftllc.agenthq.cli.mcp.ProjectScope
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.board.Board
import com.superiorsoftllc.agenthq.daemon.api.board.BoardColumn
import com.superiorsoftllc.agenthq.daemon.api.board.BoardResult
import com.superiorsoftllc.agenthq.daemon.api.board.EpicInfo
import com.superiorsoftllc.agenthq.daemon.api.board.Epics
import com.superiorsoftllc.agenthq.daemon.api.board.Page
import com.superiorsoftllc.agenthq.daemon.api.board.StartResult
import com.superiorsoftllc.agenthq.daemon.api.board.TicketBinding
import com.superiorsoftllc.agenthq.daemon.api.board.TicketDraft
import com.superiorsoftllc.agenthq.daemon.api.board.TicketInfo
import com.superiorsoftllc.agenthq.daemon.api.board.TicketLinkInfo
import com.superiorsoftllc.agenthq.daemon.api.board.TicketPatch
import com.superiorsoftllc.agenthq.daemon.api.board.TicketQuery
import com.superiorsoftllc.agenthq.daemon.api.board.Tickets
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.mvp.ProjectBaseCheck
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStart
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * DP-4a (docs/DAEMON-BOARD-API.md): the LOCAL binding of [Tickets], [Epics] and [Board] over the daemon's own [TicketBoard] and
 * [EpicStore]. The HOST applies, before the board is touched and the same way for every plugin:
 *  1. the active policy preset, as the equivalent frame is gated (the preset's neutral text, byte for byte): [gate];
 *  2. the project scope, with the very rules of the MCP tools' [ProjectScope] (one implementation: this class calls it), seeing
 *     the caller as the daemon's session record for [Caller.activeSession] (never a request argument). No active session on an
 *     owner connection is the owner / CLI path; an agent connection with no session, or a session the daemon does not know, is
 *     refused (fail closed).
 * Behaviour is the board's: the same calls the frame handlers make, the same texts (`<frame> refused: <why>`).
 */
class LocalBoardServices(
    private val board: () -> TicketBoard?,
    private val epicStore: () -> EpicStore?,
    private val sessions: () -> List<SessionRecord>,
    private val link: () -> OrchestratorLink?,
    /** The active preset's refusal of a frame of this type and payload, or null. */
    private val gate: (type: String, data: String) -> String?,
    private val baseCheck: ((Epic, String) -> Unit)? = null,
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

    private inline fun <T> op(frame: String, by: Caller, data: String = "", body: (TicketBoard, Who) -> BoardResult<T>): BoardResult<T> {
        val b = board() ?: return BoardResult.Unavailable("this daemon has no board")
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
    private fun resolve(b: TicketBoard, w: Who, ref: String): Pair<Ticket?, String?> =
        when (val match = matchTicketPrefix(ref, b.list())) {
            TicketPrefixMatch.None -> null to "no ticket '$ref' on this daemon"
            is TicketPrefixMatch.Ambiguous -> null to ("ticket id '$ref' is ambiguous; use a longer id or the full id: " +
                match.tickets.joinToString("; ") { "${it.id} (${BoardSummary.cutTitle(it.title)})" })
            is TicketPrefixMatch.Unique -> {
                val refusal = foreign(w, "ticket ${shortTicketId(match.ticket.id)}", match.ticket.projectId)
                if (refusal == null) match.ticket to null else null to refusal
            }
        }

    private inline fun <T> withTicket(frame: String, ref: String, by: Caller, data: String = "", body: (TicketBoard, Who, Ticket) -> BoardResult<T>): BoardResult<T> =
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
            )
            val created = b.save(save).ticket
            key?.let { createKeys[it] = created.id }
            BoardResult.Ok(info(created))
        }

        override fun update(id: String, patch: TicketPatch, by: Caller): BoardResult<TicketInfo> = withTicket(Envelope.TICKET_SAVE, id, by) { b, _, t ->
            val save = TicketSave(
                id = t.id, title = patch.title, description = patch.description, ticketType = patch.ticketType, epicId = patch.epicId,
                agentId = patch.agentId, provider = patch.provider, model = patch.model, weight = patch.weight, images = patch.images,
            )
            BoardResult.Ok(info(b.save(save).ticket))
        }

        override fun move(id: String, status: String?, position: Int?, by: Caller): BoardResult<TicketInfo> =
            withTicket(Envelope.TICKET_MOVE, id, by) { b, w, t ->
                val wanted = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                when {
                    wanted == null && position == null -> BoardResult.Refused("ticket_move refused: give a status or a position")
                    wanted == TicketStatus.IN_PROGRESS -> BoardResult.Refused("ticket_move refused: to start a ticket use start")
                    wanted == t.status && position == null -> BoardResult.Ok(info(t))
                    else -> BoardResult.Ok(info(b.move(TicketMove(id = t.id, status = wanted, position = position), startedBy = starterOf(w)).ticket))
                }
            }

        override fun delete(id: String, by: Caller): BoardResult<Unit> = withTicket(Envelope.TICKET_DELETE, id, by) { b, _, t ->
            b.delete(t.id)
            BoardResult.Ok(Unit)
        }

        override fun link(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo> = linkOp(Envelope.TICKET_LINK, from, to, kind, by) { b, r -> b.link(r) }
        override fun unlink(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo> = linkOp(Envelope.TICKET_UNLINK, from, to, kind, by) { b, r -> b.unlink(r) }

        private fun linkOp(frame: String, from: String, to: String, kind: String, by: Caller, call: (TicketBoard, TicketLinkRequest) -> com.superiorsoftllc.agenthq.backlog.TicketAnswer): BoardResult<TicketInfo> =
            op(frame, by) { b, w ->
                val (a, whyA) = resolve(b, w, from)
                val (z, whyZ) = resolve(b, w, to)
                when {
                    a == null -> BoardResult.Refused(whyA!!)
                    z == null -> BoardResult.Refused(whyZ!!)
                    else -> BoardResult.Ok(info(call(b, TicketLinkRequest(ticketId = a.id, relatedId = z.id, type = kind)).ticket))
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
                val answer = b.move(TicketMove(id = t.id, status = TicketStatus.IN_PROGRESS), startedBy = starterOf(w)).ticket
                val start = answer.start ?: TicketStart(ok = false, reason = "start_refused", detail = "the board gave no start answer")
                BoardResult.Ok(StartResult(info(answer), start.ok, start.reason, start.detail))
            }
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
        private inline fun <T> epicOp(frame: String, by: Caller, data: String = "", body: (EpicStore, Who) -> BoardResult<T>): BoardResult<T> {
            val store = epicStore() ?: return BoardResult.Unavailable("this daemon has no board")
            gate(frame, data)?.let { return BoardResult.Refused(it) }
            val (w, why) = who(by)
            if (w == null) return BoardResult.Refused("$frame refused: $why")
            return try {
                body(store, w)
            } catch (e: IllegalArgumentException) {
                BoardResult.Refused("$frame refused: ${e.message ?: "invalid request"}")
            }
        }

        private fun epicOf(store: EpicStore, w: Who, id: String): Pair<Epic?, String?> {
            val epic = store.get(id) ?: return null to "unknown epic '${id.trim().take(80)}'"
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
            BoardResult.Ok(s.list(project).map(::info))
        }

        override fun create(projectId: String, name: String, goal: String, by: Caller): BoardResult<EpicInfo> = epicOp(Envelope.EPIC_SAVE, by) { s, w ->
            foreign(w, "the target project", projectId)?.let { return@epicOp BoardResult.Refused("epic_save refused: $it") }
            BoardResult.Ok(info(s.save(projectId, name, goal)))
        }

        override fun setBase(id: String, branch: String?, clear: Boolean, by: Caller): BoardResult<EpicInfo> = epicOp(Envelope.EPIC_BASE, by) { s, w ->
            require(!(clear && branch != null)) { "pass a branch or clear, not both" }
            val (epic, why) = epicOf(s, w, id)
            if (epic == null) return@epicOp BoardResult.Refused(why!!)
            val result = when {
                clear -> s.setBaseBranch(epic.id, null)
                branch != null -> {
                    val name = ProjectBaseCheck.checkName(branch)
                    val check = baseCheck ?: throw IllegalArgumentException("this daemon has no repository to check a base branch against")
                    s.setBaseBranch(epic.id, name, check)
                }
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
            BoardResult.Ok(worker?.let { b.brief(it) })
        }
    }

    companion object {
        fun info(t: Ticket) = TicketInfo(
            id = t.id, title = t.title, description = t.description, ticketType = t.ticketType, projectId = t.projectId, epicId = t.epicId,
            status = t.status, position = t.position, wontFix = t.wontFix, links = t.links.map { TicketLinkInfo(it.type, it.ticketId) },
            heldBy = t.heldBy, holdReason = t.holdReason, createdAt = t.createdAt, updatedAt = t.updatedAt, agentId = t.agentId,
            provider = t.provider, model = t.model, weight = t.weight,
            effectiveProvider = t.effectiveExecutor?.provider?.value, effectiveProviderSource = t.effectiveExecutor?.provider?.source,
            effectiveModel = t.effectiveExecutor?.model?.value, effectiveModelSource = t.effectiveExecutor?.model?.source,
            effectiveRule = t.effectiveExecutor?.rule, sessionId = t.sessionId, branch = t.branch, base = t.base,
            images = t.images, skillIds = t.skillIds,
            contextLimit = t.contextLimit, effectiveContextLimit = t.effectiveContextLimit?.value,
            effectiveContextLimitSource = t.effectiveContextLimit?.source, contextLimitSupport = t.effectiveContextLimit?.support,
        )

        fun info(e: Epic) = EpicInfo(e.id, e.projectId, e.name, e.goal, e.storyIds, e.baseBranch)
    }
}
