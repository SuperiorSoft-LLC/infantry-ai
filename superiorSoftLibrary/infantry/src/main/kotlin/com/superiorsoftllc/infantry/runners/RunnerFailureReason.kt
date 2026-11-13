// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json

/**
 * RUNNER-UNIFY-5 (docs/RUNNER-UNIFY.md 5.4): why a runner session's turn FAILED, read from the frames the runner pushed, never from a
 * scrape of its transcript (GREEN-GAP-1's "turn failed" text match). The runner's own daemon already says why: a FAILED `finished`
 * frame carries the reason in its detail (FIRSTRUN-FAIL-1), and its `session` record carries `lastError`.
 */
object RunnerFailureReason {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The reason [fresh] (one batch of a session's pushed frames) says the session failed, or null when the batch reports no failure.
     * A failure is a FAILED `finished` frame, or a `session` frame after which [record] (the runner's record as the session directory
     * now holds it) is FAILED. The reason is, first found: the `finished` detail, the record's `lastError`, the first line of the batch's last `error` frame (the card shows one line, not a stack).
     */
    fun of(fresh: List<Envelope>, record: SessionRecord?): String? {
        val finished = fresh.lastOrNull { it.type == Envelope.FINISHED }
            ?.let { runCatching { json.decodeFromString(FinishedPayload.serializer(), it.data) }.getOrNull() }
            ?.takeIf { it.outcome == FinishedPayload.Outcome.FAILED }
        val recordFailed = record?.state == SessionState.FAILED && fresh.any { it.type == Envelope.SESSION }
        if (finished == null && !recordFailed) return null
        return finished?.detail?.trim()?.takeIf { it.isNotEmpty() }
            ?: record?.lastError?.trim()?.takeIf { it.isNotEmpty() }
            ?: fresh.lastOrNull { it.type == Envelope.ERROR }?.data?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "the runner reported the turn failed with no reason"
    }
}
