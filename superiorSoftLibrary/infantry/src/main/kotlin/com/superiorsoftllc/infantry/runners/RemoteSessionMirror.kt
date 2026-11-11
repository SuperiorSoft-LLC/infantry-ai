// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-E2E-2: the orchestrator's read-only view of the sessions it started on remote runners. The runner owns the process; the
 * pool indexes which session sits on which runner ([sessionsOn]); the runner's status events ([status]) say what state each is in.
 * A session with no event yet reads as [SessionState.STARTING]. The session list, the board's worker check and the log replay read
 * this instead of finding nothing (which the board reported as "worker gone"). Generic: it names no harness.
 *
 * [sessionIds] says which session ids sit on remote runners now; [clock] is the epoch millis source.
 */
class RemoteSessionMirror(
    private val sessionIds: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Seen(val state: SessionState, val at: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val placedAs = ConcurrentHashMap<String, Placed>()

    /**
     * RUNNER-TICKET-ROW-1: what the start pinned for [sessionId]: the harness, the effective [model], the orchestrator's checkout
     * [repoRoot] (where the runner's pushed ticket branch is fetched to, and where the board row reads its commit tip) and the
     * ticket [branch]. Without it the board row showed no commit, an empty worktree and the harness's default model.
     * RUNNER-WORKTREE-PATH-1: [runnerWorktree] is the branch's worktree ON THE RUNNER (from the runner's start reply), the record's
     * worktreePath; readers label it with the record's runner. Null (an older runner) leaves worktreePath unset, as before.
     * RUNNER-DONE-1: [projectId], [agentId] and [role] are the start's, so the project roster (`agents --live`) lists the worker.
     */
    class Placed(
        val provider: String,
        val model: String?,
        val repoRoot: String?,
        val branch: String?,
        val ticketId: String? = null,
        val runnerWorktree: String? = null,
        val projectId: String? = null,
        val agentId: String? = null,
        val role: String? = null,
    )

    fun placed(sessionId: String, info: Placed) {
        placedAs[sessionId] = info
    }

    fun placement(sessionId: String): Placed? = placedAs[sessionId]

    /** RUNNER-SESSION-GHOST-1: [sessionId] was deleted: what this mirror kept about it goes too. */
    fun forget(sessionId: String) {
        placedAs.remove(sessionId); seen.remove(sessionId); firstSeen.remove(sessionId)
    }

    /** A runner event moved [sessionId] to [state] (a SessionState name; an unknown word is ignored). */
    fun status(sessionId: String, state: String) {
        val s = runCatching { SessionState.valueOf(state.trim().uppercase()) }.getOrNull() ?: return
        seen[sessionId] = Seen(s, clock())
    }

    /** A turn of [sessionId] ended: the session rests until its next status says otherwise. */
    fun finished(sessionId: String) {
        val cur = seen[sessionId]?.state
        if (cur == null || cur == SessionState.BUSY || cur == SessionState.STARTING) seen[sessionId] = Seen(SessionState.IDLE, clock())
    }

    fun get(sessionId: String): SessionRecord? = if (sessionId in sessionIds()) record(sessionId) else null

    fun list(): List<SessionRecord> = sessionIds().map(::record)

    private fun record(id: String): SessionRecord {
        val now = clock()
        val s = seen[id]
        val p = placedAs[id]
        return SessionRecord(
            id = id, provider = p?.provider ?: REMOTE_PROVIDER, model = p?.model, workingDir = p?.repoRoot.orEmpty(), repoRoot = p?.repoRoot,
            worktreePath = p?.runnerWorktree, worktreeBranch = p?.branch, ticketId = p?.ticketId,
            projectId = p?.projectId, agentId = p?.agentId, role = p?.role, spawnedAt = firstSeen.computeIfAbsent(id) { now },
            lastHeartbeat = s?.at ?: now, state = s?.state ?: SessionState.STARTING,
            // The brief went over the link with the start; the runner resumes its own conversation, so the board treats it as briefed.
            providerSessionId = REMOTE_PROVIDER, runCount = 1,
        )
    }

    companion object {
        /** The provider word of a mirrored record: the runner's harness is not known here. */
        const val REMOTE_PROVIDER = "remote"
    }
}
