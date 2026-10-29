package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RunUsage
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX-HEADER-WRAP-1: at a narrow window the session header's executor chips used to take every pixel, so the
 * weighted title column was squeezed to one character and "Full-Stack" wrapped one letter per line
 * (INSTALL-1 smoke-ticket.png). The title and the role chip must stay on one line, and the title keeps a
 * readable width; whatever does not fit is ellipsized or clipped, never wrapped.
 */
@OptIn(ExperimentalTestApi::class)
class HeaderWrapUiTest {
    private val a = AgentView(
        session = SessionRecord(
            id = "59e07ffb1234", provider = "opencode", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
            state = SessionState.IDLE, providerSessionId = "p", runCount = 1, model = "opencode/minimax-m3.1-flash-free",
        ),
        role = "fullstack",
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "finished", 1)),
        usage = listOf(RunUsage("r:1", "opencode", "opencode/minimax-m3.1-flash-free", 31_500, 1_300, 0, 0, 0)),
    )
    private val state = AppState(agents = mapOf(a.id to a), roles = listOf(Role("fullstack", "Full-Stack", "#22C55E")))

    @Test
    fun atANarrowWidthTheTitleAndRoleChipStayOnOneLine() = runDesktopComposeUiTest(560, 300) {
        setContent { CenterHeader(a, spawnMode = false, state = state, onOpenSwitch = {}) }
        waitForIdle()
        val title = onNodeWithTag(AGENT_HEADER_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // One line of 14sp text at density 1 is ~19px; two lines would be ~38px.
        assertTrue(title.height < 28f, "title is one line high, was ${title.height}px (w=${title.width})")
        assertTrue(title.width >= 60f, "title keeps a readable width, was ${title.width}px")
        val role = onAllNodesWithText("fullstack", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue(role.height < 20f, "role chip text is one line high, was ${role.height}px")
    }
}
