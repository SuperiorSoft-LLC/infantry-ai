package com.superiorsoftllc.agenthq.daemon.api.board

import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionView

/**
 * DP-5c (BOARD_API_VERSION 1.6.0, docs/DAEMON-BOARD-API.md section 9): what the board PLUGIN needs from the HOST, the reverse of
 * [Tickets] / [Epics] / [Board] (those are what the host offers a caller of the board; this is what the board asks of core).
 * Plain data in and out, no daemon class crosses it (`AgentHqStore`, `SessionManager`, `TicketBranches`, `File` stay in core).
 * Nothing consumes it yet: DP-5 moves `TicketBoard` and its neighbours onto it, changing imports only.
 *
 * ONE port with narrow members, not several ports: the board lives in one process with the host, every member is cheap to hand
 * over together, and a transaction handle has to sit next to the clock and the event log it commits with. Each member is its own
 * small interface so a test fakes only what it uses.
 *
 * Every member defaults to the [NoBoardHost] answer: git and sessions refuse with a VALUE, settings answer null, the clock is the
 * system clock. Storage has no honest empty answer, so [BoardStorage] on a host without board storage throws
 * [UnsupportedOperationException] (calling it is a wiring bug, not an expected failure).
 */
interface BoardHost {
    val storage: BoardStorage get() = NoBoardHost.storage
    val git: BoardGit get() = NoBoardHost.git
    val sessions: BoardSessions get() = NoBoardHost.sessions
    val settings: BoardSettings get() = NoBoardHost.settings

    /** Epoch millis; the host's clock, so a test host can pin time. */
    fun now(): Long = System.currentTimeMillis()
}

/** A ticket as the tickets table stores it (the persistence row, flat and plain; the plugin builds its own views over it). */
data class StoredTicket(
    val id: String,
    val title: String,
    val description: String = "",
    val ticketType: String? = null,
    val status: String,
    val position: Int,
    val wontFix: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val useWorktree: Boolean? = null,
    val worktreeBranch: String? = null,
    val includeLinkedTasksContext: Boolean? = null,
    val queuedFor: String? = null,
    val queuedAt: Long? = null,
    val sessionId: String? = null,
    val projectId: String? = null,
    val epicId: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val skillIds: List<String> = emptyList(),
    val skillsMode: String = "merge",
    val includeSkillsContext: Boolean? = null,
    val startedBy: String? = null,
    val images: List<String> = emptyList(),
    val weight: Int? = null,
    val contextLimit: Int? = null,
    val effort: String? = null,
)

/** One stored link, read "[ticketId] [type] [relatedId]". */
data class StoredLink(val ticketId: String, val relatedId: String, val type: String, val createdAt: Long = 0)

/** One start of a ticket and, once its session ended, the outcome. */
data class StoredRun(
    val ticketId: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val detail: String? = null,
)

/**
 * Typed access to the board's tables (section 4.5: they stay in the host store for now). A plugin never sees SQL.
 * [transaction] runs [block] atomically and returns its value; a throw rolls back. [writeTransaction] is the same but takes the
 * write lock up front, for a read-then-write that must not interleave (what `AgentHqStore.writeTransaction` is). The event log
 * ([events]) is appended inside the caller's transaction, so a commit and its events land together or not at all.
 */
interface BoardStorage {
    fun <T> transaction(block: BoardTables.() -> T): T = throw UnsupportedOperationException(UNAVAILABLE)
    fun <T> writeTransaction(block: BoardTables.() -> T): T = throw UnsupportedOperationException(UNAVAILABLE)

    /** The transaction-free view, for single reads (each call is its own transaction). */
    val tables: BoardTables get() = EmptyBoardTables

    companion object {
        const val UNAVAILABLE = "this daemon has no board storage"
    }
}

/** The board's tables, as the repositories the board uses today (`TicketRepository`, `TicketRunRepository`) plus the event append. */
interface BoardTables {
    val tickets: TicketTable
    val runs: TicketRunTable
    val events: TicketEventAppender
}

interface TicketTable {
    /** Inserts a new ticket; a duplicate id throws. */
    fun create(ticket: StoredTicket)

    /** Overwrites everything but the id and createdAt of the ticket with [ticket]'s id. */
    fun update(ticket: StoredTicket)
    fun get(id: String): StoredTicket?

    /** Every ticket; order is not meaningful (the board orders by its own column order). */
    fun all(): List<StoredTicket>

    /** Deletes the ticket and every link naming it; false when there was none. */
    fun delete(id: String): Boolean

    /** Stores [link]; false when that exact link was already there. */
    fun link(link: StoredLink): Boolean
    fun unlink(link: StoredLink): Boolean

    /** Every link, oldest first. */
    fun links(): List<StoredLink>
}

interface TicketRunTable {
    fun insert(run: StoredRun)
    fun finish(ticketId: String, sessionId: String, endedAt: Long, outcome: String, detail: String?)
    fun byTicket(ticketId: String): List<StoredRun>
}

/**
 * Records the typed events of one commit inside the open transaction (before / after map a ticket id to its column; the host
 * derives created / moved / started / done / updated / deleted); the host delivers them to [TicketEvents] subscribers after the commit.
 */
interface TicketEventAppender {
    fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>)

    /** Hands subscribers the events they have not seen; the board calls it once the commit is through. */
    fun deliver() {}
}

/** Git work for tickets: cut or reattach a ticket's own branch and worktree, and remove them. Git runs in the host. */
interface BoardGit {
    /** Cuts (or, with [BranchRequest.recordedBranch], reattaches) the ticket's branch and worktree; a refusal is a value. */
    fun ensureBranch(request: BranchRequest): BranchOutcome = BranchOutcome.Refused(NO_GIT)

    /** The ref a start cuts from for a ticket of [projectId] / [epicId] (`HEAD` unless the host is configured otherwise), and the setting that chose it. */
    fun baseRef(projectId: String?, epicId: String? = null): BaseChoice = BaseChoice("HEAD")

    /** The commit [ref] resolves to in [repo], or null. */
    fun resolveBase(repo: String, ref: String): String? = null

    /** A project's own stored directory (blank / unknown = null). */
    fun projectDirectory(projectId: String?): String? = null

    /** The daemon's own checkout, null when there is none. */
    fun defaultRepo(): String? = null

    /** Whether the host cuts a worktree per project folder (the `projectFolders` mode). */
    fun projectFolders(): Boolean = false

    companion object {
        const val NO_GIT = "this daemon has no git access"
    }
}

/** The ref a start cuts from and the setting that named it (e.g. `epic epic-1a2b3c4d`; null = unlabelled). */
data class BaseChoice(val ref: String, val source: String? = null)

data class BranchRequest(
    val ticketId: String,
    val title: String,
    val repo: String,
    val baseBranch: String,
    val recordedBranch: String? = null,
    val recordedBase: String? = null,
)

sealed interface BranchOutcome {
    data class Ready(
        val branch: String,
        val base: String,
        val worktreePath: String,
        val repoRoot: String,
        val note: String? = null,
        val warning: String? = null,
    ) : BranchOutcome

    data class Refused(val reason: String) : BranchOutcome
}

/** Everything the board asks a session to start. The host adds the gates (spawn gate, caps, allowlists) and may refuse. */
data class SessionStart(
    val prompt: String,
    val workingDir: String? = null,
    /** A worktree the board already cut for the ticket: the session runs in it and none is provisioned. */
    val worktree: BranchOutcome.Ready? = null,
    val contextSources: List<ContextSourceInfo> = emptyList(),
    val branchHint: String? = null,
    val providerId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val agentId: String? = null,
    val role: String? = null,
    val ticketId: String? = null,
    val projectId: String? = null,
    val contextLimit: Int? = null,
    /** `human` or `unattended`: which launch gate applies. */
    val origin: String = "human",
)

/** One source of a first turn's context and its size, as the session record keeps it. */
data class ContextSourceInfo(val source: String, val chars: Int)

/** The launch gate's answer: [refusal] stops the start, [warning] only qualifies it; both null = allow. [disposition] names a refusal for the log. */
data class LaunchVerdict(val refusal: String? = null, val warning: String? = null, val disposition: String = "refused_launch_gate")

sealed interface SessionStartResult {
    data class Started(val session: SessionView) : SessionStartResult

    /** The host refused (spawn gate, model allowlist, caps, launch gate); [reason] is the text the board shows. */
    data class Refused(val reason: String) : SessionStartResult
}

/** Session operations the board needs; richer reads stay on `Sessions` / `SessionReads`. */
interface BoardSessions {
    /** Starts a session for the board (a ticket hand-out or an unattended prompt). */
    fun start(request: SessionStart): SessionStartResult = SessionStartResult.Refused(NO_SESSIONS)

    /** Checks provider and model before a start; a problem text, or null when the spawn would pass. */
    fun checkSpawn(providerId: String?, model: String?): String? = NO_SESSIONS

    /** The launch gate for a start (exhausted provider, spent budget refuse an unattended one; a human start is only warned). */
    fun launchGate(providerId: String?, origin: String, ticketId: String?): LaunchVerdict = LaunchVerdict()

    fun get(sessionId: String): SessionView? = null
    fun list(): List<SessionView> = emptyList()

    /** Kills a session; false when it is unknown. */
    fun kill(sessionId: String, force: Boolean = false, by: String = "board", reason: String? = null): Boolean = false

    /** Removes an ended session's record; a text when it cannot (still running, unknown), else null. */
    fun remove(sessionId: String): String? = NO_SESSIONS

    companion object {
        const val NO_SESSIONS = "this daemon has no session manager"
    }
}

/** Reads of the host's settings by key (the JSON text the settings table stores); null when unset. */
interface BoardSettings {
    fun get(key: String): String? = null
}

/** What a host without board support answers; each member's own defaults apply. */
object NoBoardHost : BoardHost {
    override val storage: BoardStorage = object : BoardStorage {}
    override val git: BoardGit = object : BoardGit {}
    override val sessions: BoardSessions = object : BoardSessions {}
    override val settings: BoardSettings = object : BoardSettings {}
}

private object EmptyBoardTables : BoardTables {
    override val tickets: TicketTable get() = throw UnsupportedOperationException(BoardStorage.UNAVAILABLE)
    override val runs: TicketRunTable get() = throw UnsupportedOperationException(BoardStorage.UNAVAILABLE)
    override val events: TicketEventAppender get() = throw UnsupportedOperationException(BoardStorage.UNAVAILABLE)
}
