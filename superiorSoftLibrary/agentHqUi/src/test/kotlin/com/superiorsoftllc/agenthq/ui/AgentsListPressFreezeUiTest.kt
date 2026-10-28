package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-UX-SHIFT-1: while a pointer is down on the agents list its rows stay where they were, and the fresh grouping
 * lands on release. These cover the two edge cases of that freeze: a drag-scroll (the pointer is down the whole time)
 * and a row removed while its "⋯" is held down.
 */
@OptIn(ExperimentalTestApi::class)
class AgentsListPressFreezeUiTest {
    private val now = System.currentTimeMillis()
    private fun agent(id: String, state: SessionState) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = now, state = state, providerSessionId = "p"),
        role = "qa",
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "working on $id", 1)),
    )

    private fun stateOf(agents: List<AgentView>) =
        AppState(agents = agents.associateBy { it.id }, roles = listOf(Role("qa", "QA Engineer", "#10B981")))

    @Test
    fun aDragScrollStillScrollsAndTheListRegroupsOnRelease() {
        val busy = (1..12).map { agent("busy%04d".format(it), SessionState.BUSY) }
        var state by mutableStateOf(stateOf(busy))
        runDesktopComposeUiTest(320, 300) {
            setContent { AgentsList(state, state.agents.values.toList(), onSelect = {}, onRemove = {}, modifier = Modifier.testTag("agents-list")) }
            val firstTop = onNodeWithTag("agent-row-${busy[0].id}").getBoundsInRoot().top

            onNodeWithTag("agents-list").performTouchInput { down(center); repeat(4) { moveBy(androidx.compose.ui.geometry.Offset(0f, -15f)) } }
            // Mid-drag, row 1's session settles: it would leave WORKING for INACTIVE at the bottom. The pointer is still
            // down, so the rows stay where they are.
            state = stateOf(busy.map { if (it.id == busy[0].id) agent(it.id, SessionState.IDLE) else it })
            waitForIdle()
            val draggedTop = top(busy[0].id)
            assertTrue(draggedTop != null && draggedTop < top(busy[1].id)!!, "frozen while the pointer is down: row 1 still above row 2")
            // The freeze does not block the drag: the list really scrolled under the pointer.
            assertTrue(draggedTop < firstTop - 20.dp, "the drag scrolled the list (row 1 top $firstTop -> $draggedTop)")

            onNodeWithTag("agents-list").performTouchInput { up() }
            waitForIdle()
            val releasedTop = top(busy[0].id)
            assertTrue(releasedTop == null || releasedTop > top(busy[1].id)!!, "regrouped on release: row 1 left the top of the list ($releasedTop)")
        }
    }

    /** A row's top in the root, or null when the lazy list has not composed it (scrolled away). */
    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.top(id: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag("agent-row-$id")).fetchSemanticsNodes().singleOrNull()
            ?.let { onNodeWithTag("agent-row-$id").getBoundsInRoot().top }

    @Test
    fun aRowRemovedWhileItsMenuIsPressedDoesNotCrashAndLeavesOnRelease() {
        val a = agent("gone0001", SessionState.IDLE)
        val b = agent("stay0001", SessionState.IDLE)
        var state by mutableStateOf(stateOf(listOf(a, b)))
        val removed = mutableListOf<String>()
        runDesktopComposeUiTest(400, 400) {
            setContent { AgentsList(state, state.agents.values.toList(), onSelect = {}, onRemove = { removed += it }) }
            onNodeWithTag("agent-row-${a.id}").performMouseInput { moveTo(center) }
            onNodeWithTag("session-menu-${a.id}").performMouseInput { moveTo(center); press() }
            state = stateOf(listOf(b))
            waitForIdle()
            onNodeWithTag("session-menu-${a.id}").assertExists() // kept in place while the button is held

            onNodeWithTag("session-menu-${a.id}").performMouseInput { release() }
            waitForIdle()
            onNodeWithTag("agent-row-${a.id}").assertDoesNotExist()
            onNodeWithTag("session-menu-popup-${a.id}").assertDoesNotExist()
            onNodeWithTag("session-menu-${b.id}").assertExists()
            assertEquals(emptyList(), removed, "nothing removed by the stale row")
        }
    }
}
