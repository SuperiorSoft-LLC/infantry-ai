package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.RolePortrait
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-2: every agent-side chat line (status, log, thinking, errors, reply) carries the agent's portrait,
 * grouped like a chat -- the portrait opens each run of agent lines; a USER line ends the run.
 */
@OptIn(ExperimentalTestApi::class)
class ChatAgentPortraitUiTest {
    // 1c24fdc4: the Thinking row follows the session state, so a transcript-only test runs IDLE (no pulsing row).
    private fun pmAgent(vararg lines: Pair<LineKind, String>, state: SessionState = SessionState.IDLE) = AgentView(
        session = SessionRecord(id = "1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state, providerSessionId = "p"),
        role = "pm",
        entries = lines.mapIndexed { i, (kind, text) -> TranscriptEntry(i + 1L, kind, text, 1_000L + i) },
    )

    /** The agent's portraits in the chat (the user's disc carries no [PortraitKey]). */
    private fun SemanticsNodeInteractionsProvider.agentPortraits(within: SemanticsMatcher = hasTestTag(SPEAKER_ROW_TAG)): List<String> =
        onAllNodes(hasTestTag(AVATAR_TAG) and hasAnyAncestor(within))
            .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(PortraitKey) }

    @Test
    fun aRunOfAgentLinesShowsThePortraitOnceOnItsFirstLine() {
        val agent = pmAgent(
            LineKind.USER to "plan the sprint",
            LineKind.LOG to "[infantry] spawning opencode",
            LineKind.LOG to "[infantry] session ready",
            LineKind.STATUS to "connected",
            LineKind.TEXT to "Sprint plan drafted.",
        )
        runDesktopComposeUiTest(900, 600) {
            setContent { AgentChat(agent) }
            // One avatar for the user, one for the agent: the run LOG, LOG, STATUS, TEXT shares one portrait.
            onAllNodes(hasTestTag(AVATAR_TAG) and hasAnyAncestor(hasTestTag(SPEAKER_ROW_TAG))).assertCountEquals(2)
            assertEquals(listOf(RolePortrait.PM.key), agentPortraits(), "exactly one agent portrait in the chat")
            // ... and it sits on the FIRST line of the run, the first LOG.
            val firstLogRow = hasTestTag(SPEAKER_ROW_TAG) and hasAnyDescendant(hasText("[infantry] spawning opencode"))
            assertEquals(listOf("09_project_manager"), agentPortraits(firstLogRow), "the portrait must open the run on the first LOG line")
        }
    }

    @Test
    fun theThinkingRowShowsThePortraitWhenNoAgentLineIsThereYet() {
        val agent = pmAgent(LineKind.USER to "plan the sprint", state = SessionState.BUSY)
        runDesktopComposeUiTest(900, 600) {
            // The pulse never settles; hold the clock so the test can reach idle.
            mainClock.autoAdvance = false
            setContent { AgentChat(agent, nowProvider = { 2_000L }) }
            mainClock.advanceTimeByFrame()
            onNodeWithTag(CHAT_STATUS_TAG).assertExists()
            val statusRow = hasTestTag(SPEAKER_ROW_TAG) and hasAnyDescendant(hasTestTag(CHAT_STATUS_TAG))
            assertEquals(listOf(RolePortrait.PM.key), agentPortraits(statusRow), "the Thinking row must carry the agent's portrait")
        }
    }

    @Test
    fun theThinkingRowContinuesARunWithoutASecondPortrait() {
        val agent = pmAgent(LineKind.USER to "plan the sprint", LineKind.LOG to "[infantry] session ready", state = SessionState.BUSY)
        runDesktopComposeUiTest(900, 600) {
            mainClock.autoAdvance = false
            setContent { AgentChat(agent, nowProvider = { 2_000L }) }
            mainClock.advanceTimeByFrame()
            onNodeWithTag(CHAT_STATUS_TAG).assertExists()
            assertEquals(listOf(RolePortrait.PM.key), agentPortraits(), "the LOG opens the run; the Thinking row only keeps the indent")
        }
    }

    @Test
    fun aUserLineStartsANewRun() {
        val agent = pmAgent(
            LineKind.USER to "one",
            LineKind.STATUS to "connected",
            LineKind.TEXT to "first answer",
            LineKind.USER to "two",
            LineKind.ERROR to "provider failed",
        )
        runDesktopComposeUiTest(900, 600) {
            setContent { AgentChat(agent) }
            assertEquals(listOf(RolePortrait.PM.key, RolePortrait.PM.key), agentPortraits())
        }
    }

    /** Evidence for the DONE report: status lines, then the reply, written to build/reports/m2-2. */
    @Test
    fun captureChatWithStatusLinesAndAReply() {
        val agent = pmAgent(
            LineKind.USER to "plan the sprint",
            LineKind.LOG to "[infantry] spawning opencode (pm)",
            LineKind.LOG to "[infantry] session ready",
            LineKind.STATUS to "connected",
            LineKind.REASONING to "Reading the backlog first.",
            LineKind.TEXT to "Sprint plan drafted: **three tickets** on the board.",
            LineKind.FINISHED to "finished: ok",
            LineKind.USER to "thanks",
            LineKind.ERROR to "provider rate-limited, retrying",
            LineKind.TEXT to "Done -- the board is up to date.",
        )
        runDesktopComposeUiTest(700, 420) {
            setContent {
                Column(Modifier.width(700.dp).background(Palette.Bg).testTag("shot")) { AgentChat(agent) }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PM").fetchSemanticsNodes().isEmpty() }
            val out = File("build/reports/m2-2/chat-agent-portrait.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-2 screenshot: ${out.absolutePath}")
        }
    }
}
