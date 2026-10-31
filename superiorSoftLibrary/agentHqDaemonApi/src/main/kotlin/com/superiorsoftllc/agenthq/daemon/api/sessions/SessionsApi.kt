package com.superiorsoftllc.agenthq.daemon.api.sessions

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.progress.SessionProgress

/**
 * RUNTIME-LOAD-5 (DaemonApi 1.6.0, additive): the READ model of the daemon's sessions (docs/RUNTIME-LOAD.md section 11).
 *
 * One session as a plugin may read it: what the session tools and the board print, nothing else. No provider session id, no
 * environment, no credentials, no prompt or persona, no transcript (that is [SessionReads.transcript], behind its own grant).
 */
data class SessionView(
    val id: String,
    val agentId: String?,
    val role: String?,
    val provider: String,
    val model: String?,
    val projectId: String?,
    /** The session state's name (`IDLE`, `BUSY`, `STARTING`, `WAITING_INPUT`, `EXITED`, `FAILED`, `ORPHANED`, ...). */
    val state: String,
    /** What the run is doing inside a turn (`compacting`), or null. */
    val runPhase: String? = null,
    /** A run is in flight (or its process is still alive). */
    val live: Boolean = false,
    val lastAction: String? = null,
    val lastOutputAt: Long? = null,
    val lastEventAt: Long? = null,
    /** The label of the background process the session is waiting on, or null. */
    val backgroundWork: String? = null,
    /** The agent's own progress report (contract 1.117.0), or null. */
    val progress: SessionProgress? = null,
    val runCount: Int = 0,
    val lastExitCode: Int? = null,
    val lastError: String? = null,
    val ticketId: String? = null,
    val workingDir: String? = null,
    val worktreePath: String? = null,
    val worktreeBranch: String? = null,
    val repoRoot: String? = null,
    /** The session that spawned this one (contract 1.114.0), or null. */
    val spawnerSessionId: String? = null,
    /** RUNTIME-LOAD-7 (1.9.0): the session's context limit in tokens and the harness's support for it, or null. */
    val contextLimit: Int? = null,
    val contextLimitSupport: String? = null,
    /** EFFORT-1: the session's reasoning effort level and how its harness honours it, or null. */
    val effort: String? = null,
    val effortSupport: String? = null,
    /** RUNTIME-LOAD-7 (1.9.0): how the last kill ended (`KillOutcome` name), or null. */
    val killOutcome: String? = null,
)

/** The project a read is scoped to ([projectId] null = every project) and why the call may not have what it asked for, if so. */
data class ReadScope(val projectId: String?, val refusal: String? = null) {
    /** `project:<id>` or `all`: what an answer says it covered. */
    val label: String get() = projectId?.let { "project:$it" } ?: ALL

    companion object {
        const val ALL = "all"
    }
}

/** A session's replayed output as readable lines (oldest first), and a note when the daemon holds none or only part of it. */
data class Transcript(val lines: List<String>, val note: String? = null)

/** The answer of a [SessionReads] call. */
sealed interface SessionsResult<out T> {
    data class Ok<T>(val value: T) : SessionsResult<T>
    /** Nothing matched (or more than one did): [text] is the whole answer, e.g. `no session 'x' on this daemon`. */
    data class Missing(val text: String) : SessionsResult<Nothing>
    /** The caller may not have it: [reason] is the bare why; a tool prefixes its own `<tool> refused: `. */
    data class Refused(val reason: String) : SessionsResult<Nothing>
    data class Unavailable(val reason: String) : SessionsResult<Nothing>
}

/** Read every session (`list`, `find`, `caller`, `scope`). */
const val GRANT_SESSIONS_READ = "sessions.read"
/** Read a session's replayed output ([SessionReads.transcript]). */
const val GRANT_SESSIONS_TRANSCRIPT = "sessions.transcript"

/**
 * The daemon's sessions, read through the HOST's project scope: the rule of the board API (docs/DAEMON-BOARD-API.md), seeing the
 * caller as the daemon's session record for [Caller.activeSession], never a request argument. No session on an owner connection is
 * the owner / CLI path (every project); an agent connection with no session, or a session the daemon does not know, is refused.
 * A session caller reaches its own project; pm / owner roles may name another project or `all`.
 *
 * Bundled / official plugins are trusted; a COMMUNITY plugin needs [GRANT_SESSIONS_READ], and [GRANT_SESSIONS_TRANSCRIPT] for
 * [transcript] (default deny: [SessionsResult.Refused] naming the grant).
 */
interface SessionReads {
    /** The calling session as the daemon holds it; Ok(null) for an owner / CLI caller. */
    fun caller(by: Caller): SessionsResult<SessionView?>

    /**
     * The project a read for [by] covers when it asks for [requested] (`all`, a project id, or null = the default: a session's own
     * project, an owner's [defaultProjectId], else every project). A refusal comes back IN the scope (with the caller's own project),
     * so a tool words it; the call itself is Refused only when the caller cannot be scoped at all.
     */
    fun scope(by: Caller, requested: String?, defaultProjectId: String? = null): SessionsResult<ReadScope>

    /** The sessions of [projectId] (null = every project), oldest first; Refused when that is out of the caller's reach. */
    fun list(by: Caller, projectId: String?): SessionsResult<List<SessionView>>

    /** The ONE session [ref] names (full id, else a unique prefix), across the daemon, then the caller's scope. */
    fun find(ref: String, by: Caller): SessionsResult<SessionView>

    /** Session [id]'s output as this daemon process still holds it (its replay buffer), rendered by the host. */
    fun transcript(id: String, by: Caller): SessionsResult<Transcript>
}

/** A daemon without sessions to read: every call Unavailable. */
object NoSessionReads : SessionReads {
    private val none = SessionsResult.Unavailable("this daemon has no session read model")
    override fun caller(by: Caller) = none
    override fun scope(by: Caller, requested: String?, defaultProjectId: String?) = none
    override fun list(by: Caller, projectId: String?) = none
    override fun find(ref: String, by: Caller) = none
    override fun transcript(id: String, by: Caller) = none
}
