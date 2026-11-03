// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI-REAL console fix: the window must not land on a stale EXITED session (its output is never replayed),
 * and when nothing live is selected the centre pane offers a visible "Start a session" action.
 */
class SelectionPolicyTest {
    private fun session(id: String, spawnedAt: Long, state: String) =
        """{"id":"$id","provider":"opencode","workingDir":"/w","spawnedAt":$spawnedAt,"lastHeartbeat":$spawnedAt,"state":"$state"}"""

    private fun sessionsFrame(vararg records: String) =
        Envelope(Envelope.SESSIONS, records.joinToString(prefix = "[", postfix = "]"))

    @Test fun sessionsFrameDoesNotSelectTheNewestExitedSession() {
        val state = reduce(
            AppState(),
            sessionsFrame(session("old-live", 100, "IDLE"), session("new-exited", 200, "EXITED")),
            now = 1L,
        )
        assertEquals("old-live", state.selectedId)
    }

    @Test fun onlyExitedSessionsLeavesNothingSelected() {
        val state = reduce(AppState(), sessionsFrame(session("gone", 200, "EXITED")), now = 1L)
        assertNull(state.selectedId, "a stale EXITED session must not be auto-selected")
    }

    @Test fun centrePaneOffersAStartSessionAction() {
        val app = File("../../plugins/official/Infantry_ChatFeed/src/ChatFeedSurface.kt").readText() // S4d: the chat tree moved to the app UI
        assertTrue("\"Start a session\"" in app, "the empty output pane must offer a visible Start a session action")
    }
}
