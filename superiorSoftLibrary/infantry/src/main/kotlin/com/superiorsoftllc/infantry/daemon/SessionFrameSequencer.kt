// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Assign each live session-stream frame exactly one increasing sequence before publishing it.
 *
 * RUNNER-RESTART-FRAMES-1: a session's sequence survives a restart of this process. [history] keeps each session's highest
 * sequence ([SessionHistoryStore.highWater]); a session's stream continues after it instead of starting again at 1. A runner's
 * sessions need this: the orchestrator persists, per session, the last runner sequence it took and drops every frame at or
 * below it, so a runner that numbered from 1 again after a container restart had every new frame dropped as already taken.
 *
 * The numbers below a restored stream never held a frame in this process ([State.blank]). A client that replays from below
 * them gets a `replay_gap` whose [SessionReplayGapPayload.requestedAfterSequence] is raised past them, so the gap it is told
 * about counts only frames that really existed and were dropped (none: its cursor just moves up).
 */
internal class SessionFrameSequencer(
    private val history: SessionHistoryStore? = null,
    /** RUNNER-UNIFY-3: called after a batch put at least one numbered frame on a stream (wakes a held runner frame pull). */
    private val onPublished: () -> Unit = {},
) {
    private class State(start: Long) {
        var latestSequence = start
        var evictedCount = 0L
        /** The sequences `1..blank` were never used by this process (the stream continued an earlier one's numbering). */
        var blank = start
        /** A live frame was numbered: a later [seed] must not renumber the stream. */
        var live = false
        val frames = ArrayDeque<Envelope>()
    }

    private val sessions = ConcurrentHashMap<String, State>()

    private fun state(sessionId: String): State = sessions.computeIfAbsent(sessionId) { State(history?.highWater(it) ?: 0L) }

    fun publish(clients: List<Channel<Envelope>>, frames: List<Envelope>) {
        val sessionId = frames.firstNotNullOfOrNull { frame ->
            frame.sessionId?.takeIf { frame.type in SESSION_STREAM_FRAME_TYPES }
        }
        if (sessionId == null) {
            for (frame in frames) for (client in clients) client.trySend(frame)
            return
        }

        val state = state(sessionId)
        synchronized(state) {
            for (frame in frames) {
                val published = if (frame.sessionId == sessionId && frame.type in SESSION_STREAM_FRAME_TYPES) {
                    state.live = true
                    frame.copy(sequence = ++state.latestSequence).also { sequenced ->
                        if (state.frames.size >= SESSION_REPLAY_BUFFER_CAPACITY) {
                            state.frames.removeFirst()
                            state.evictedCount++
                        }
                        state.frames.addLast(sequenced)
                        history?.record(sequenced)
                    }
                } else {
                    frame
                }
                for (client in clients) client.trySend(published)
            }
        }
        runCatching(onPublished)
    }

    /**
     * CLI-PTY-5: put [loaded] (a session's kept history) on [sessionId]'s stream before anything live. The frames end at the
     * session's last sequence before the restart ([SessionHistoryStore.Loaded.highWater]), so the next live frame continues
     * that numbering (RUNNER-RESTART-FRAMES-1); a replay from 0 also answers a gap that says how many older items were dropped.
     */
    fun seed(sessionId: String, loaded: SessionHistoryStore.Loaded) {
        val state = state(sessionId)
        synchronized(state) {
            if (state.live || state.frames.isNotEmpty()) return
            val kept = loaded.frames.takeLast(SESSION_REPLAY_BUFFER_CAPACITY)
            val dropped = loaded.dropped + (loaded.frames.size - kept.size)
            val last = maxOf(loaded.highWater, state.latestSequence, dropped + kept.size)
            val start = last - kept.size
            state.evictedCount = dropped
            state.blank = start - dropped
            state.latestSequence = start
            for (frame in kept) state.frames.addLast(frame.copy(sequence = ++state.latestSequence))
        }
    }

    /** CLI-PTY-5: the session was deleted: its stream and its kept history go. */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
        history?.forget(sessionId)
    }

    fun replayAfter(sessionId: String, lastSequence: Long): ReplayBatch? {
        val state = sessions[sessionId] ?: return null
        return synchronized(state) {
            val oldestAvailableSequence = state.frames.firstOrNull()?.sequence
            val latestAvailableSequence = state.frames.lastOrNull()?.sequence
            val gap = if (
                oldestAvailableSequence != null && latestAvailableSequence != null &&
                lastSequence < oldestAvailableSequence - 1L
            ) {
                SessionReplayGapPayload(
                    requestedAfterSequence = maxOf(lastSequence, state.blank),
                    oldestAvailableSequence = oldestAvailableSequence,
                    latestAvailableSequence = latestAvailableSequence,
                    evictedCount = state.evictedCount,
                )
            } else {
                null
            }
            ReplayBatch(
                gap = gap,
                frames = state.frames.filter { (it.sequence ?: Long.MIN_VALUE) > lastSequence },
            )
        }
    }
}

@Serializable
internal data class SessionReplayRequestPayload(val lastSequence: Long)

@Serializable
internal data class SessionReplayGapPayload(
    val requestedAfterSequence: Long,
    val oldestAvailableSequence: Long,
    val latestAvailableSequence: Long,
    val evictedCount: Long,
)

internal data class ReplayBatch(
    val gap: SessionReplayGapPayload?,
    val frames: List<Envelope>,
)
