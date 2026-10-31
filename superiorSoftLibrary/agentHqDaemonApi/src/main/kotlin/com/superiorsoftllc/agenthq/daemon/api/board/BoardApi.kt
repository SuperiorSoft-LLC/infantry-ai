package com.superiorsoftllc.agenthq.daemon.api.board

import com.superiorsoftllc.agenthq.daemon.api.Caller
import kotlinx.serialization.Serializable

/**
 * DP-4a (docs/DAEMON-BOARD-API.md section 3): the tickets, epics and board a daemon plugin may read and write. Plain data in and
 * out; no daemon class crosses it (`TicketRow`, `SessionRecord`, `Ticket` stay in core). Core owns the LOCAL binding over its
 * `TicketBoard` / `EpicStore`; after DP-5 the board plugin supplies the implementation and core binds to it.
 *
 * Contract (version [BOARD_API_VERSION], its own semver inside `DaemonApi.VERSION`; adding a field with a default is minor,
 * changing the meaning of an operation is major):
 *  - SCOPE: every operation takes the [Caller]. The HOST applies the project scope (a worker sees its own project, `all` is for
 *    pm / owner / assistant callers, a non-owner explicit id must be its own); a plugin never resolves scope and cannot widen it.
 *    Who the caller is comes from [Caller.activeSession] (the session record the daemon holds), never from an argument. No active
 *    session = the owner / CLI path.
 *  - POLICY: each operation maps to the core action of the frame that does the same (`board.read`, `board.ticket.save`,
 *    `board.ticket.move`, `board.ticket.delete`, `board.ticket.start`, `epics.read`, `epics.save`); the host checks the active
 *    preset first and answers [BoardResult.Refused] with the preset's neutral text, byte for byte what the frame answers.
 *  - TEXTS: a refusal of the board is `<frame> refused: <why>` as the frame answers; an unknown / ambiguous ticket, epic or a scope refusal is the bare sentence the MCP tools word today.
 *  - ERRORS: an expected failure is a VALUE ([BoardResult.Refused] = the board said no, with its own text; [BoardResult.Unavailable]
 *    = no answer, e.g. no board in this daemon or the orchestrator is down). Only a bug throws.
 *  - IDEMPOTENCY: reads are reads; [Tickets.create] with a [TicketDraft.clientKey] returns the ticket the first call made;
 *    [Tickets.move] to the status the ticket already has, and [Tickets.start] of a ticket already in progress, are no-op answers.
 *  - PAGINATION: [Tickets.list] returns [Page] with an opaque cursor; ordering is board order (stable) then id.
 *  - GRANTS (the plugin trust model): bundled / official plugins are trusted. A community plugin needs the grant in modules.json
 *    for each operation: [GRANT_TICKETS_READ], [GRANT_TICKETS_WRITE], [GRANT_TICKETS_DELETE], [GRANT_TICKETS_START],
 *    [GRANT_EPICS_READ], [GRANT_EPICS_WRITE]. No grant = [BoardResult.Refused] ("missing grant ..."); the default is deny.
 */
/**
 * 1.2.0 (CONTEXT-LIMIT-1, additive): [TicketInfo] carries the context limit and the effective one.
 * 1.3.0 (RUNTIME-LOAD-4, additive): the shared pure rules [TicketRefs] (short ids), [TicketPlacement] (where a move puts a ticket),
 *   [TicketImages] and [ContextLimits]; [TicketDraft.contextLimit] / [TicketPatch.contextLimit]; [Tickets.mayFile].
 * 1.4.0 (EFFORT-1, additive): [TicketInfo.effort] / [TicketInfo.effectiveEffort] (+ source, support); [TicketDraft.effort] / [TicketPatch.effort];
 *   [EffortLevels] (the shared level list and parser). Blank / `off` clears a ticket's effort on a patch.
 */
const val BOARD_API_VERSION = "1.4.0"

const val GRANT_TICKETS_READ = "tickets.read"
const val GRANT_TICKETS_WRITE = "tickets.write"
const val GRANT_TICKETS_DELETE = "tickets.delete"
const val GRANT_TICKETS_START = "tickets.start"
const val GRANT_EPICS_READ = "epics.read"
const val GRANT_EPICS_WRITE = "epics.write"

/** The error model, as [com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorReply] plus the board's refusal. */
sealed interface BoardResult<out T> {
    data class Ok<T>(val value: T) : BoardResult<T>

    /** The board (or the scope, policy or grants in front of it) said no. [text] is the one the frame answer carries. */
    data class Refused(val text: String) : BoardResult<Nothing>

    /** No answer: no board in this daemon, or the orchestrator could not be reached. */
    data class Unavailable(val reason: String) : BoardResult<Nothing>
}

/** The value of an Ok result, else null. */
fun <T> BoardResult<T>.okOrNull(): T? = (this as? BoardResult.Ok)?.value

/** One page of a listing: [next] is the opaque cursor of the following page, null on the last. */
@Serializable
data class Page<T>(val items: List<T>, val next: String? = null)

@Serializable
data class TicketLinkInfo(val type: String, val ticketId: String)

/** A ticket as plugins see it: the fields a board reads and an agent sets, flat and plain. */
@Serializable
data class TicketInfo(
    val id: String,
    val title: String,
    val description: String = "",
    val ticketType: String? = null,
    val projectId: String? = null,
    val epicId: String? = null,
    val status: String,
    val position: Int,
    val wontFix: Boolean = false,
    val links: List<TicketLinkInfo> = emptyList(),
    val heldBy: List<String> = emptyList(),
    val holdReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val weight: Int? = null,
    val effectiveProvider: String? = null,
    val effectiveProviderSource: String? = null,
    val effectiveModel: String? = null,
    val effectiveModelSource: String? = null,
    val effectiveRule: String? = null,
    val sessionId: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val images: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
    /** CONTEXT-LIMIT-1 (1.2.0): the ticket's context limit in tokens (null = none) and the effective one, its source (`ticket` / `default`) and the harness's support. */
    val contextLimit: Int? = null,
    val effectiveContextLimit: Int? = null,
    val effectiveContextLimitSource: String? = null,
    val contextLimitSupport: String? = null,
    /** EFFORT-1 (1.4.0): the ticket's reasoning effort level (null = harness default) and the effective one with its source (`ticket` / `rule` / `agent` / `role` / `default`) and the harness's support. */
    val effort: String? = null,
    val effectiveEffort: String? = null,
    val effectiveEffortSource: String? = null,
    val effortSupport: String? = null,
)

/** A new ticket. [description] is kept verbatim. [clientKey] makes the create idempotent. */
@Serializable
data class TicketDraft(
    val title: String,
    val description: String? = null,
    val projectId: String? = null,
    val epicId: String? = null,
    val ticketType: String? = null,
    /** A profile id or `role:<role>`. */
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val weight: Int? = null,
    val images: List<String>? = null,
    /** The column the ticket enters (`todo` default, or `backlog`). */
    val status: String? = null,
    val clientKey: String? = null,
    /** 1.3.0: the context limit in tokens ([ContextLimits]); null = none. */
    val contextLimit: Int? = null,
    /** 1.4.0: the reasoning effort level ([EffortLevels]); null = the harness default. */
    val effort: String? = null,
)

/** An edit: a null field keeps the stored value. [description], when given, replaces the stored one verbatim. */
@Serializable
data class TicketPatch(
    val title: String? = null,
    val description: String? = null,
    val ticketType: String? = null,
    val epicId: String? = null,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    /** 1..5, or 0 to clear. */
    val weight: Int? = null,
    val images: List<String>? = null,
    /** 1.3.0: the context limit in tokens, or [ContextLimits.CLEAR] to remove it. */
    val contextLimit: Int? = null,
    /** 1.4.0: the reasoning effort level, or blank / `off` to remove it; null keeps it. */
    val effort: String? = null,
)

@Serializable
data class TicketQuery(
    /** `null` = the caller's own project (owner: every project); `all` = no filter (pm / owner callers only). */
    val projectId: String? = null,
    val epicId: String? = null,
    val status: Set<String> = emptySet(),
    val limit: Int = DEFAULT_LIMIT,
    val cursor: String? = null,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
    }
}

/** The answer to a start: the ticket, and what the board decided (`started`, `queued`, `held`, `start_refused`, `already_in_progress`). */
@Serializable
data class StartResult(val ticket: TicketInfo, val ok: Boolean, val reason: String, val detail: String? = null)

/** The session a ticket is bound to. */
@Serializable
data class TicketBinding(val sessionId: String?, val projectId: String?)

@Serializable
data class BoardColumn(val status: String, val tickets: List<TicketInfo>)

@Serializable
data class EpicInfo(
    val id: String,
    val projectId: String,
    val name: String,
    val goal: String = "",
    val storyIds: List<String> = emptyList(),
    /** "" = none set. */
    val baseBranch: String = "",
)

interface Tickets {
    /** [id] is a full ticket id or a unique prefix / suffix (what `tickets` prints). Unknown or ambiguous: [BoardResult.Refused]. */
    fun get(id: String, by: Caller): BoardResult<TicketInfo>
    fun list(by: Caller, query: TicketQuery = TicketQuery()): BoardResult<Page<TicketInfo>>
    fun create(draft: TicketDraft, by: Caller): BoardResult<TicketInfo>
    fun update(id: String, patch: TicketPatch, by: Caller): BoardResult<TicketInfo>

    /** Runs the status machine (it may refuse). [position] places the ticket in its column (0 = top). A move INTO in_progress is [start]. */
    fun move(id: String, status: String?, position: Int?, by: Caller): BoardResult<TicketInfo>
    fun delete(id: String, by: Caller): BoardResult<Unit>
    fun link(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo>
    fun unlink(from: String, to: String, kind: String, by: Caller): BoardResult<TicketInfo>

    /** Reorders the tickets of [status] (the order of [ids] is priority; unlisted tickets keep their relative place after them). */
    fun reorder(status: String, ids: List<String>, by: Caller): BoardResult<Unit>

    /** The board's own start (a card moving to In Progress): role, provider, model, branch, worker. The caller is recorded as starter. */
    fun start(id: String, by: Caller): BoardResult<StartResult>

    /** The agent that started the ticket worker session [sessionId] runs, null when none. Answered through the orchestrator link. */
    fun starterOf(sessionId: String, by: Caller): BoardResult<String?>

    /** The ticket id session [sessionId] works on, null for none. */
    fun ticketOf(sessionId: String, by: Caller): BoardResult<String?>

    /** The session and project ticket [ticketId] is bound to, null when unknown. Answered through the orchestrator link. */
    fun workerOf(ticketId: String, by: Caller): BoardResult<TicketBinding?>

    /**
     * 1.3.0: may [by] file a ticket into project [projectId]? Ok, or [BoardResult.Refused] with the scope's bare sentence (a worker
     * files into its own project; pm / owner reach any). A tool asks BEFORE it writes anything for that project (an upload).
     */
    fun mayFile(projectId: String, by: Caller): BoardResult<Unit> = BoardResult.Unavailable("this daemon has no board")
}

interface Epics {
    fun get(id: String, by: Caller): BoardResult<EpicInfo>
    fun list(projectId: String?, by: Caller): BoardResult<List<EpicInfo>>
    fun create(projectId: String, name: String, goal: String, by: Caller): BoardResult<EpicInfo>

    /** [branch] set: the epic's base branch (checked where tickets are cut); [clear]: remove it; neither: a read. */
    fun setBase(id: String, branch: String?, clear: Boolean, by: Caller): BoardResult<EpicInfo>
}

interface Board {
    /** The project's tickets by column in status order (`backlog`, `todo`, `in_progress`, `pending`, `done`). */
    fun columns(projectId: String?, by: Caller): BoardResult<List<BoardColumn>>

    /** The start brief of the ticket [ticketId]'s worker, null when it has none. */
    fun brief(ticketId: String, by: Caller): BoardResult<String?>
}

/**
 * DP-4b (BOARD-API-2): one typed ticket event. [seq] is global, monotonic and gap-free per daemon store (so also per ticket:
 * events of one ticket arrive in seq order); delivery is at-least-once, so a consumer dedupes on [seq]. [kind] is one of
 * [TicketEvent.CREATED], [MOVED], [STARTED] (moved into in_progress), [DONE] (moved into done), [UPDATED] (a change that kept
 * the column), [DELETED]. [to] is the ticket's column after the event (null for DELETED), [from] the one before (null for CREATED).
 */
@Serializable
data class TicketEvent(
    val seq: Long,
    val ticketId: String,
    val kind: String,
    val from: String? = null,
    val to: String? = null,
    val at: Long = 0,
) {
    /** The ticket entered a column (created, moved, started or done): what `transitionSink` used to announce. */
    val isTransition: Boolean get() = to != null && kind != UPDATED && kind != DELETED

    companion object {
        const val CREATED = "created"
        const val MOVED = "moved"
        const val STARTED = "started"
        const val DONE = "done"
        const val UPDATED = "updated"
        const val DELETED = "deleted"
    }
}

/**
 * DP-4b: the ticket event stream. [subscribe] registers [consumer] (a stable name; its cursor is kept across daemon restarts, so
 * events not yet handled when the daemon stopped are replayed on the next subscribe, in order, before any new one). A listener
 * that throws is retried on the next delivery (at-least-once), and skipped after a few failures so one bad event cannot wedge it.
 * A first-ever consumer starts at the head: it sees no history. Community plugins need the [GRANT_TICKETS_READ] grant.
 */
interface TicketEvents {
    fun subscribe(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable
}

/** A daemon without a board has no events. */
object NoTicketEvents : TicketEvents {
    override fun subscribe(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable = AutoCloseable {}
}

/** What a daemon without a board answers: every operation is [BoardResult.Unavailable]. */
object NoBoard {
    private val none = BoardResult.Unavailable("this daemon has no board")

    val tickets: Tickets = object : Tickets {
        override fun get(id: String, by: Caller) = none
        override fun list(by: Caller, query: TicketQuery) = none
        override fun create(draft: TicketDraft, by: Caller) = none
        override fun update(id: String, patch: TicketPatch, by: Caller) = none
        override fun move(id: String, status: String?, position: Int?, by: Caller) = none
        override fun delete(id: String, by: Caller) = none
        override fun link(from: String, to: String, kind: String, by: Caller) = none
        override fun unlink(from: String, to: String, kind: String, by: Caller) = none
        override fun reorder(status: String, ids: List<String>, by: Caller) = none
        override fun start(id: String, by: Caller) = none
        override fun starterOf(sessionId: String, by: Caller) = none
        override fun ticketOf(sessionId: String, by: Caller) = none
        override fun workerOf(ticketId: String, by: Caller) = none
    }
    val epics: Epics = object : Epics {
        override fun get(id: String, by: Caller) = none
        override fun list(projectId: String?, by: Caller) = none
        override fun create(projectId: String, name: String, goal: String, by: Caller) = none
        override fun setBase(id: String, branch: String?, clear: Boolean, by: Caller) = none
    }
    val board: Board = object : Board {
        override fun columns(projectId: String?, by: Caller) = none
        override fun brief(ticketId: String, by: Caller) = none
    }
}
