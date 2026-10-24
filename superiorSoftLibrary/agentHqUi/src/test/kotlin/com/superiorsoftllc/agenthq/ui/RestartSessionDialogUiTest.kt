package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.protocol.BackgroundWorkView
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** RESTART-UI: the confirm before a restart says mid-turn, lists open background work, and only its button restarts. */
@OptIn(ExperimentalTestApi::class)
class RestartSessionDialogUiTest {
    private fun agent(state: SessionState, turn: Boolean?, bg: BackgroundWorkView? = null) = AgentView(
        session = SessionRecord(id = "a1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p", turnActive = turn, backgroundWork = bg),
        role = "qa",
    )

    @Test
    fun midTurnConfirmSaysSoAndListsBackgroundWork() {
        val a = agent(SessionState.BUSY, true, BackgroundWorkView(4242, "gradle daemon", 1))
        var restarted = 0
        runDesktopComposeUiTest(500, 600) {
            setContent { RestartSessionDialog(a, AppState(agents = mapOf(a.id to a)), onDismiss = {}, onRestart = { restarted++ }) }
            onNodeWithTag(RESTART_SESSION_MIDTURN_TAG).assertExists()
            onNodeWithTag(RESTART_SESSION_WORK_TAG).assertExists()
            assertEquals(0, restarted, "opening the dialog restarts nothing")
            onNodeWithTag(RESTART_SESSION_CONFIRM_TAG).performClick()
            assertEquals(1, restarted)
        }
    }

    @Test
    fun idleConfirmHasNoMidTurnWarningAndCancelDoesNotRestart() {
        val a = agent(SessionState.IDLE, false)
        var restarted = 0
        var dismissed = 0
        runDesktopComposeUiTest(500, 600) {
            setContent { RestartSessionDialog(a, AppState(agents = mapOf(a.id to a)), onDismiss = { dismissed++ }, onRestart = { restarted++ }) }
            onNodeWithTag(RESTART_SESSION_MIDTURN_TAG).assertDoesNotExist()
            onNodeWithTag(RESTART_SESSION_WORK_TAG).assertDoesNotExist()
            onNodeWithTag(RESTART_SESSION_CANCEL_TAG).performClick()
            assertEquals(0, restarted)
            assertEquals(1, dismissed)
        }
    }
}
