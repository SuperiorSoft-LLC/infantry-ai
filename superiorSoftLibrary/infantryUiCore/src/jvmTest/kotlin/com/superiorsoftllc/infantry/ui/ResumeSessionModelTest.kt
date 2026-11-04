// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-PTY-5: "Resume session" shows for a dormant session with a conversation, never while a turn runs; the wire field decodes. */
class ResumeSessionModelTest {
    private fun rec(state: SessionState, dormant: Boolean, provider: String? = "p1") = SessionRecord(
        id = "s1", provider = "claude", workingDir = ".", spawnedAt = 1, lastHeartbeat = 1, state = state,
        providerSessionId = provider, dormant = dormant,
    )

    @Test
    fun showsOnlyForADormantSessionAtRest() {
        assertTrue(showsResumeSession(rec(SessionState.IDLE, dormant = true)))
        assertTrue(showsResumeSession(rec(SessionState.EXITED, dormant = true)))
        assertFalse(showsResumeSession(rec(SessionState.IDLE, dormant = false)), "an attached session has nothing to resume")
        assertFalse(showsResumeSession(rec(SessionState.BUSY, dormant = true)), "a running turn already resumed it")
        assertFalse(showsResumeSession(rec(SessionState.IDLE, dormant = true, provider = null)), "no conversation to resume")
    }

    @Test
    fun theDaemonsDormantFieldDecodes_andAnOlderDaemonReadsNotDormant() {
        val json = Json { ignoreUnknownKeys = true }
        val base = """{"id":"s1","provider":"claude","workingDir":".","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE","providerSessionId":"p1""""
        assertTrue(json.decodeFromString(SessionRecord.serializer(), "$base,\"dormant\":true}").dormant)
        assertFalse(json.decodeFromString(SessionRecord.serializer(), "$base}").dormant)
    }
}
