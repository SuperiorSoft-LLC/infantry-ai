package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * BRANCH-PER-TASK 2/3: a started ticket shows its branch and base on its card and in the agent header;
 * a ticket that never started shows nothing about a branch. Read-only: nothing here sends a request.
 */
@OptIn(ExperimentalTestApi::class)
class TicketBranchUiTest {
    private val branch = "ticket/40144c35-show-branch"
    private val base = "ff6eaab7c0ffee1234567890abcdef1234567890"

    private val started = Ticket(id = "t-started", title = "Started work", status = "in_progress", branch = branch, base = base, sessionId = "a")
    private val unstarted = Ticket(id = "t-unstarted", title = "Never started", status = "todo")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")

    private fun stateWith(tickets: List<Ticket>): AppState = reduce(
        AppState(),
        Envelope(Envelope.READY, "provider=stub contract=1.32.0 instance=A since=0"),
        0,
    ).copy(tickets = tickets).let { s ->
        reduce(s, Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), rec), rec.id), 0)
    }

    private val controller = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun `a started ticket shows its branch and base on the card`() {
        val state = stateWith(listOf(started))
        runDesktopComposeUiTest(900, 700) {
            setContent { BoardPane(state, controller, onOpenAgent = {}) }
            onNodeWithTag("ticket-branch-t-started", useUnmergedTree = true)
                .assertTextEquals("$branch · base ${base.take(8)}")
        }
    }

    @Test fun `a ticket that never started shows no branch at all`() {
        val state = stateWith(listOf(unstarted))
        runDesktopComposeUiTest(900, 700) {
            setContent { BoardPane(state, controller, onOpenAgent = {}) }
            onNodeWithText("Never started").assertExists()
            onAllNodesWithTag("ticket-branch-t-unstarted", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("base", substring = true).assertCountEquals(0)
        }
    }

    @Test fun `the agent header shows the branch of the started ticket its session runs`() {
        val state = stateWith(listOf(started))
        val agent = state.agents.getValue("a")
        runDesktopComposeUiTest(900, 300) {
            setContent { CenterHeader(agent, spawnMode = false, state = state) }
            onNodeWithTag("agent-ticket-branch", useUnmergedTree = true)
                .assertTextEquals("$branch · base ${base.take(8)}")
        }
    }

    @Test fun `the agent header shows no ticket branch when its ticket never started`() {
        val state = stateWith(listOf(unstarted))
        val agent = state.agents.getValue("a")
        runDesktopComposeUiTest(900, 300) {
            setContent { CenterHeader(agent, spawnMode = false, state = state) }
            onAllNodesWithTag("agent-ticket-branch", useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
