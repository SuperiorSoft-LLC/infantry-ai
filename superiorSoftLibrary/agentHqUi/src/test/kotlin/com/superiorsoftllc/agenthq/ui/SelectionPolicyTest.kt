package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
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
        val app = File("../agenthqUiCore/src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui/App.kt").readText()
        assertTrue("\"Start a session\"" in app, "the empty output pane must offer a visible Start a session action")
    }
}
