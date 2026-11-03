// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Test-side copy of the frozen F2 wire envelope. `feature-agenthq-f2-server` adds [sequence] to
 * production `Envelope`; `feature-agenthq-f2-client` reads it and suppresses frames at or behind its cursor.
 */
@Serializable
internal data class SequencedSessionFrame(
    val type: String,
    val data: String = "",
    val sessionId: String? = null,
    val sequence: Long? = null,
)

/**
 * Payload for `replay_request`. `feature-agenthq-f2-client` sends it on reconnect;
 * `feature-agenthq-f2-server` handles it. The envelope's `sessionId` selects the session and
 * `lastSequence` is an exclusive cursor.
 */
@Serializable
internal data class SessionReplayRequest(
    val lastSequence: Long,
)

/**
 * Payload for `replay_gap`, emitted by `feature-agenthq-f2-server` before the retained replay frames
 * when the requested cursor predates the buffer. `evictedCount` counts every session frame evicted to date.
 */
@Serializable
internal data class SessionReplayGap(
    val requestedAfterSequence: Long,
    val oldestAvailableSequence: Long,
    val latestAvailableSequence: Long,
    val evictedCount: Long,
)

/** `feature-agenthq-f2-server` keeps no more than this many streamed frames per session. */
internal object SessionReplayContract {
    const val REQUEST_TYPE = "replay_request"
    const val GAP_TYPE = "replay_gap"
    const val SESSION_REPLAY_BUFFER_CAPACITY = 256

    /**
     * Frames emitted from live session events are sequenced and buffered. Initial snapshots and
     * non-session control/push frames are not part of the replay stream.
     */
    val STREAM_FRAME_TYPES = setOf(
        Envelope.SESSION,
        Envelope.STATUS,
        Envelope.STDOUT,
        Envelope.STDERR,
        Envelope.EXIT,
        Envelope.TEXT_DELTA,
        Envelope.ERROR,
        Envelope.FINISHED,
        Envelope.TURN,
        Envelope.USAGE,
    )
}
