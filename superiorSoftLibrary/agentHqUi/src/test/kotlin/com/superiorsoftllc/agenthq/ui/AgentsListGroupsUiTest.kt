package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-4: the agents list is grouped by status (Waiting on you / Ready to check / Running / Quiet,
 * see AgentGroup) with a count, empty groups hidden; each agent a card with the portrait + role badge, the
 * role name in bold, the role and a snippet below, the elapsed time on the right; the selected card
 * has an orange border; no provider chip and no wide "Remove" text in the row.
 */
@OptIn(ExperimentalTestApi::class)
class AgentsListGroupsUiTest {
    private val now = System.currentTimeMillis()
    private fun agent(id: String, role: String, state: SessionState, line: String, agoMs: Long) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = now - agoMs, state = state, providerSessionId = "p"),
        role = role,
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, line, 1)),
    )

    private val pm = agent("pm000001", "pm", SessionState.WAITING_INPUT, "Which sprint goal should I pick?", 120_000)
    private val qa = agent("qa000001", "qa", SessionState.BUSY, "Running the regression suite on the cart flow", 40_000)
    private val fe = agent("fe000001", "frontend", SessionState.BUSY, "Styling the agents list cards", 8_000)
    private val be = agent("be000001", "backend", SessionState.IDLE, "Merged the session endpoint.", 3_600_000)
    private val agents = listOf(pm, qa, fe, be)
    private val state = AppState(
        agents = agents.associateBy { it.id },
        selectedId = qa.id,
        roles = listOf(
            Role("pm", "Project Manager", "#8B5CF6"), Role("qa", "QA Engineer", "#10B981"),
            Role("frontend", "Frontend Developer", "#EC4899"), Role("backend", "Backend Developer", "#3B82F6"),
        ),
    )

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun agentsAreGroupedByStatusWithACountAndEmptyGroupsHidden() {
        runDesktopComposeUiTest(400, 700) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = {}) }
            assertEquals("Waiting on you", onNodeWithTag("agent-group-NEEDS_YOU", useUnmergedTree = true).text())
            assertEquals("1", onNodeWithTag("agent-group-count-NEEDS_YOU", useUnmergedTree = true).text())
            assertEquals("Running", onNodeWithTag("agent-group-WORKING", useUnmergedTree = true).text())
            assertEquals("2", onNodeWithTag("agent-group-count-WORKING", useUnmergedTree = true).text())
            assertEquals("Quiet", onNodeWithTag("agent-group-INACTIVE", useUnmergedTree = true).text())
            assertEquals("1", onNodeWithTag("agent-group-count-INACTIVE", useUnmergedTree = true).text())
            onNodeWithTag("agent-group-TO_REVIEW", useUnmergedTree = true).assertDoesNotExist()
            // The old role grouping is gone: no "PM" role header.
            assertTrue(onAllNodesWithText("PM").fetchSemanticsNodes().isEmpty(), "no role-name group header")
        }
    }

    @Test
    fun eachCardShowsTitleRoleSnippetAndTimeWithoutProviderChipOrRemoveText() {
        runDesktopComposeUiTest(400, 700) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = {}) }
            assertEquals("QA Engineer", onNodeWithTag("agent-row-title-${qa.id}", useUnmergedTree = true).text())
            assertEquals("qa", onNodeWithTag("agent-row-role-${qa.id}", useUnmergedTree = true).text())
            assertEquals("Running the regression suite on the cart flow", onNodeWithTag("agent-row-snippet-${qa.id}", useUnmergedTree = true).text())
            assertEquals("waiting for you", onNodeWithTag("agent-row-snippet-${pm.id}", useUnmergedTree = true).text())
            onNodeWithTag("agent-row-time-${qa.id}", useUnmergedTree = true).assertExists()
            onNodeWithTag("agent-row-badge-${qa.id}", useUnmergedTree = true).assertExists()
            val inRow = hasAnyAncestor(hasTestTag("agent-row-${qa.id}"))
            onNode(hasText("stub") and inRow, useUnmergedTree = true).assertDoesNotExist()
            onNode(hasText("Remove") and inRow, useUnmergedTree = true).assertDoesNotExist()
            // Remove stays reachable, behind the small "⋯" row menu (M2-5).
            onNodeWithTag("session-menu-${qa.id}").assertExists()
        }
    }

    @Test
    fun theSelectedCardHasAnOrangeBorderAndTheOthersDoNot() {
        runDesktopComposeUiTest(400, 700) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = {}) }
            assertTrue(hasAccentEdge(onNodeWithTag("agent-row-${qa.id}")), "selected card's top edge is orange")
            assertTrue(!hasAccentEdge(onNodeWithTag("agent-row-${be.id}")), "an unselected card's top edge is not orange")
        }
    }

    /** True when a pixel of the card's top edge, mid-width, is the accent orange. */
    private fun hasAccentEdge(card: SemanticsNodeInteraction): Boolean {
        val img = card.captureToImage().toAwtImage()
        val accent = java.awt.Color(Palette.Accent.toArgb())
        return (0 until minOf(3, img.height)).any { y ->
            val c = java.awt.Color(img.getRGB(img.width / 2, y))
            abs(c.red - accent.red) < 40 && abs(c.green - accent.green) < 40 && abs(c.blue - accent.blue) < 40
        }
    }

    /** Evidence for the DONE report: agents in 3 groups, one selected, written to build/reports/m2-4. */
    @Test
    fun captureAgentsList() {
        runDesktopComposeUiTest(300, 420) {
            setContent {
                Box(Modifier.width(300.dp).fillMaxHeight().background(Palette.Panel).testTag("shot")) {
                    AgentsList(state, agents, onSelect = {}, onRemove = {})
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("QA").fetchSemanticsNodes().isEmpty() }
            val out = File("build/reports/m2-4/agents-list-groups.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-4 screenshot: ${out.absolutePath}")
        }
    }
}
