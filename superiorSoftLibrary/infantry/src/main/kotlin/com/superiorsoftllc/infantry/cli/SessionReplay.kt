package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-session delivery cursors used by the CLI to resume a stream after a socket reconnect. */
internal class SessionReplayCursor {
    private val lastSeenBySession = linkedMapOf<String, Long>()

    /**
     * The daemon PROCESS that issued the cursors above (contract 1.11.0). Sequence numbers restart with the
     * process, so a cursor is only valid against the instance that produced it. Null until a `ready` names one.
     */
    private var instance: String? = null

    /** Where [instance]'s replay history begins (contract 1.11.0); null when the daemon did not say. */
    private var historySince: Long? = null

    /** Register a session before its first event so a disconnect can replay from the start. */
    fun track(sessionId: String) {
        if (sessionId.isNotBlank()) lastSeenBySession.putIfAbsent(sessionId, 0L)
    }

    /**
     * Fold a `ready` frame's identity in. A `ready` that names a DIFFERENT process than the one the cursors
     * were built against means the numbering restarted, so every cursor is reset to 0: the next
     * [replayRequests] asks for each tracked session's history from the start instead of resuming the dead
     * process's sequences, and the new process's frames are no longer dropped as duplicates.
     *
     * A `ready` that names no instance (a pre-1.11 daemon) cannot be told apart from a reconnect, so the
     * cursors are kept -- exactly the behaviour a daemon that never replays by instance expects.
     *
     * @return true when the instance changed and every cursor was reset.
     */
    fun observeReady(instance: String?, since: Long?): Boolean {
        val changed = instance != null && instance != this.instance
        if (instance != null) this.instance = instance
        if (since != null) this.historySince = since
        if (changed) lastSeenBySession.keys.toList().forEach { lastSeenBySession[it] = 0L }
        return changed
    }

    /** The daemon process the cursors belong to, or null when no `ready` has named one. */
    fun instance(): String? = instance

    /** Where the current instance's replay history begins, or null when the daemon did not say. */
    fun historySince(): Long? = historySince

    /** Accept only session-stream frames newer than the last frame already delivered for that session. */
    fun shouldDeliver(frame: Envelope): Boolean {
        if (frame.type !in SEQUENCED_STREAM_TYPES) return true
        val sessionId = frame.sessionId ?: return true
        val sequence = frame.sequence ?: return true
        val lastSeen = lastSeenBySession[sessionId]
        if (lastSeen != null && sequence <= lastSeen) return false
        lastSeenBySession[sessionId] = sequence
        return true
    }

    /** One exclusive-cursor replay request for every session whose sequence the client has seen. */
    fun replayRequests(): List<Envelope> = lastSeenBySession.map { (sessionId, lastSequence) ->
        Envelope(
            type = REQUEST_TYPE,
            data = json.encodeToString(ReplayRequestPayload.serializer(), ReplayRequestPayload(lastSequence)),
            sessionId = sessionId,
        )
    }

    fun lastSequence(sessionId: String): Long? = lastSeenBySession[sessionId]

    /** Render the server's explicit retention-gap report instead of silently dropping it. */
    fun gapMessage(frame: Envelope): String {
        val label = frame.sessionId?.take(8) ?: "unknown"
        val gap = runCatching {
            json.decodeFromString(ReplayGapPayload.serializer(), frame.data)
        }.getOrNull()
        return if (gap == null) {
            "warning: replay gap for session $label; could not parse gap details: ${frame.data}"
        } else {
            "warning: replay gap for session $label (requested after sequence ${gap.requestedAfterSequence}; " +
                "oldest retained ${gap.oldestAvailableSequence}, latest retained ${gap.latestAvailableSequence}; " +
                "${gap.evictedCount} frames evicted). Earlier output may be missing."
        }
    }

    companion object {
        const val GAP_TYPE = "replay_gap"

        private const val REQUEST_TYPE = "replay_request"

        private val SEQUENCED_STREAM_TYPES = setOf(
            Envelope.SESSION,
            Envelope.STATUS,
            Envelope.STDOUT,
            Envelope.STDERR,
            Envelope.EXIT,
            Envelope.TEXT_DELTA,
            Envelope.REASONING,
            Envelope.ERROR,
            Envelope.FINISHED,
            Envelope.TURN,
            Envelope.USAGE,
        )

        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ReplayRequestPayload(val lastSequence: Long)

@Serializable
private data class ReplayGapPayload(
    val requestedAfterSequence: Long,
    val oldestAvailableSequence: Long,
    val latestAvailableSequence: Long,
    val evictedCount: Long,
)
