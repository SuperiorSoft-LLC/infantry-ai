package com.superiorsoftllc.infantry.daemon.api.board

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView

/**
 * DP-5f-2 (BOARD_API_VERSION 1.7.0, docs/DAEMON-BOARD-API.md section 10): the members [BoardHost] still lacked for the board to
 * move off core classes. Same rules as DP-5c: plain data in and out, every member defaults to a no-board answer, nothing consumes
 * them yet.
 */

/** One agent profile (a named, role-bound worker identity), flat and plain. Null fields inherit (role binding, then daemon default). */
data class BoardAgent(
    val id: String? = null,
    val name: String,
    val role: String,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val instructions: String? = null,
    val projectId: String? = null,
    val traitIds: List<String>? = null,
    val skillIds: List<String>? = null,
)

/** A resolved provider / model / effort triple; null = the daemon default applies. */
data class BoardExecutor(val provider: String?, val model: String?, val effort: String?)

/** The agent profiles the board assigns tickets to. */
interface BoardAgents {
    fun get(id: String): BoardAgent? = null
    fun list(): List<BoardAgent> = emptyList()

    /** Creates (no id) or updates [agent]; a refusal throws [IllegalArgumentException]. */
    fun save(agent: BoardAgent): BoardAgent = throw UnsupportedOperationException(UNAVAILABLE)

    /** Deletes the profile; a refusal throws [IllegalArgumentException]. */
    fun delete(id: String) {}

    /** Whether a role of that id exists. */
    fun roleKnown(role: String): Boolean = false

    /** Per field, first non-null wins: the caller's choice, then [agent], then the role's binding, then the daemon default. */
    fun executorFor(agent: BoardAgent?, provider: String?, model: String?, effort: String?, role: String? = null): BoardExecutor =
        BoardExecutor(provider ?: agent?.provider, model ?: agent?.model, effort ?: agent?.effort)

    /** Checks a provider / model / effort choice and returns it as resolved; a problem throws [IllegalArgumentException]. */
    fun validateExecutor(provider: String?, model: String?, effort: String?): BoardExecutor = BoardExecutor(provider, model, effort)

    /** DP-5f-2b: the instance (persona + effective traits) a spawn for [profileId] and/or [role] would freeze now; null = anonymous. */
    fun instanceFor(profileId: String?, role: String?): BoardRoleInstance? = null

    /** DP-5f-2b: [instance] with the skill ids a spawn for its agent on [ticketId] would freeze now; unchanged when the host has no resolver. */
    fun withSkills(instance: BoardRoleInstance, ticketId: String?): BoardRoleInstance = instance

    companion object {
        const val UNAVAILABLE = "this daemon has no agent profiles"
    }
}

/** How a finished run ended. */
enum class BoardRunOutcome { SUCCEEDED, FAILED, KILLED_GRACEFUL, KILLED_FORCED, ORPHANED }

/** Reads of how a session's run ended, and of whether a ticket's worker can carry it. Session states travel by name. */
interface BoardRunHealth {
    /** Whether a session in state [state] (`IDLE`, `FAILED`, `EXITED`, `ORPHANED` ...) has finished its attempt. */
    fun isAttemptOver(state: String): Boolean = false

    /** How [sessionId]'s run ended; null for an unknown session. */
    fun outcomeOf(sessionId: String): BoardRunOutcome? = null

    /** Only a genuine failure is worth another attempt. */
    fun isRetryable(outcome: BoardRunOutcome): Boolean = outcome == BoardRunOutcome.FAILED

    /** Why the ticket's recorded worker [sessionId] (null = it names none) cannot carry it; null when it can. */
    fun workerProblem(sessionId: String?): String? = null

    /** A busy worker that opened no provider session for longer than [graceMs]: flagged, never ended here. */
    fun slowToBrief(sessionId: String, now: Long, graceMs: Long): Boolean = false
}

/** What the board hears of sessions. [Exit.session] is the record as it stood when the event arrived (null when unknown). */
sealed interface BoardSessionEvent {
    /** A session's state changed. */
    data class Status(val session: SessionView) : BoardSessionEvent

    /** A turn or process ended; [processExited] false = only the turn ended and the process lives on. */
    data class Exit(val sessionId: String, val code: Int?, val processExited: Boolean = true, val session: SessionView? = null) : BoardSessionEvent
}

/** Receives [BoardSessionEvent]s in arrival order, on the host's thread: hand work off rather than block. */
fun interface BoardSessionListener {
    fun onEvent(event: BoardSessionEvent)
}

/** The text a first turn carries from the host's knowledge base and skill library; null = no section. */
interface BoardContextSources {
    fun knowledgeSection(projectId: String?, role: String?): String? = null

    /** [skillIds] is the frozen list of the session's instance (null = no instance, no section); [include] overrides the project's switch. */
    fun skillsSection(skillIds: List<String>?, include: Boolean? = null): String? = null
}

/** A ticket's image files under its project. */
interface BoardImages {
    /** [requested] as stored relative paths, duplicates dropped; a path that is not an existing ticket upload throws [IllegalArgumentException]. */
    fun normalize(projectId: String?, requested: List<String>): List<String> = requested.distinct()

    /** The absolute path of [relPath] under the project, or null when the project has no directory. */
    fun absolute(projectId: String?, relPath: String): String? = null

    /** Deletes each of [dropped] that no ticket in [remaining] still references. Best effort, after the commit. */
    fun release(projectId: String?, dropped: Collection<String>, remaining: Collection<StoredTicket>) {}
}

/** DP-5f-2b: one trait frozen into an instance; the check is flattened (null [checkKind] = no check). */
data class BoardTrait(
    val id: String,
    val version: Int,
    val name: String,
    val body: String,
    val checkKind: String? = null,
    val checkReportTo: String? = null,
    val checkFields: List<String> = emptyList(),
    val checkReprompt: Boolean = true,
)

/** DP-5f-2b: the role instance a session is a clone of, frozen once at start (what `RoleInstance` is in core). */
data class BoardRoleInstance(
    val agentId: String?,
    val role: String?,
    val instructions: String?,
    val traits: List<BoardTrait> = emptyList(),
    val skillIds: List<String> = emptyList(),
)

/** DP-5f-2b: a provider a start may name. [defaultModel] null = the harness's own. */
data class BoardProviderInfo(
    val id: String,
    val defaultModel: String? = null,
    /** DP-5f-3 (1.9.0): how this harness honours a context limit / an effort: the wire word (`supported`, `best_effort`, `unsupported`) and the how-text; null = not asked. */
    val contextLimitSupport: String? = null,
    val contextLimitHow: String? = null,
    val effortSupport: String? = null,
    val effortHow: String? = null,
    /** The models the harness lists, or null when it cannot list them; read on demand (a harness may run a process to answer). */
    val knownModels: () -> List<String>? = { null },
)

/** DP-5f-2b: a session plus what the stall and busy checks read: the last observed activity and whether a run is in flight. */
data class BoardSessionDetail(
    val view: SessionView,
    val lastHeartbeat: Long,
    val busy: Boolean,
    /** DP-5f-3 (1.9.0): when the session was spawned, and the harness's own session id (null until it opened one). */
    val spawnedAt: Long = 0,
    val providerSessionId: String? = null,
)

/** DP-5f-2b: which thread a [BoardSessionListener] runs on. */
enum class SessionDelivery {
    /** The thread that emitted the event, possibly while the host holds its sessions lock (see [BoardSessions.holdsSessionsLock]). */
    EMITTING_THREAD,
}
