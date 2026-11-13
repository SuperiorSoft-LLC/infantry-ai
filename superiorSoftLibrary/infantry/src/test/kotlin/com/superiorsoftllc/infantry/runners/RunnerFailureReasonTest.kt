// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RUNNER-UNIFY-5: a runner session's failure reason comes from its pushed frames, never from a transcript scrape. */
class RunnerFailureReasonTest {
    private fun finished(outcome: FinishedPayload.Outcome, detail: String = "") =
        Envelope(Envelope.FINISHED, Json.encodeToString(FinishedPayload.serializer(), FinishedPayload(1, outcome, detail)), "s1")

    private fun record(state: SessionState, lastError: String? = null) =
        SessionRecord(id = "s1", provider = "p", workingDir = "/work/x", spawnedAt = 0, lastHeartbeat = 0, state = state, lastError = lastError)

    @Test
    fun aFailedFinishedFrameCarriesTheModelsRealError() {
        assertEquals("401 invalid x-api-key", RunnerFailureReason.of(listOf(finished(FinishedPayload.Outcome.FAILED, "401 invalid x-api-key")), null))
    }

    @Test
    fun aFailedSessionRecordGivesItsLastErrorThenTheErrorFrame() {
        val session = Envelope(Envelope.SESSION, "{}", "s1")
        assertEquals("model not found", RunnerFailureReason.of(listOf(session), record(SessionState.FAILED, "model not found")))
        assertEquals("boom", RunnerFailureReason.of(listOf(Envelope(Envelope.ERROR, "boom", "s1"), session), record(SessionState.FAILED)))
    }

    @Test
    fun aBatchThatReportsNoFailureGivesNoReason() {
        assertNull(RunnerFailureReason.of(listOf(finished(FinishedPayload.Outcome.SUCCEEDED)), null))
        assertNull(RunnerFailureReason.of(listOf(Envelope(Envelope.ERROR, "transient", "s1")), record(SessionState.IDLE)))
        // A FAILED record with no new session frame in this batch was reported already.
        assertNull(RunnerFailureReason.of(listOf(Envelope(Envelope.STDOUT, "x", "s1")), record(SessionState.FAILED, "old")))
    }
}
