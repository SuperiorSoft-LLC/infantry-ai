// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals

/** SESSION-GHOST-1: `session_delete` of an id the daemon does not hold is a clean no-op that still pushes the fresh list. */
class SessionDeleteUnknownDaemonTest {
    @Test
    fun deletingAnUnknownSessionIsNotAnErrorAndRepushesTheList() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            val from = fixture.client.mark()
            fixture.client.send(Envelope.SESSION_DELETE, "ghost000")
            val answer = fixture.client.awaitAfter(from, "sessions or error") { it.type == Envelope.SESSIONS || it.type == Envelope.ERROR }
            assertEquals(Envelope.SESSIONS, answer.type, answer.data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
