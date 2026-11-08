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
        return SessionRecord(
            id = id, provider = REMOTE_PROVIDER, workingDir = "", spawnedAt = firstSeen.computeIfAbsent(id) { now },
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
