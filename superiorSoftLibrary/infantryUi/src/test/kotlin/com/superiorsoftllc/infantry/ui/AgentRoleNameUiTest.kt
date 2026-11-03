package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import com.superiorsoftllc.infantry.plugins.official.agents.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-3: an agent is shown by its role's display name, not its session id. The id stays as small
 * secondary text; an agent with no role keeps showing its id. Also: the "Output" tab reads "CLI".
 */
@OptIn(ExperimentalTestApi::class)
class AgentRoleNameUiTest {
    private fun agent(id: String, role: String?) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        role = role,
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "Sprint plan drafted.", 1)),
    )

    private val pm = agent("19451abf0c2e", "pm")
    private val bare = agent("7c0ffee12345", null)
    private val state = AppState(
        agents = listOf(pm, bare).associateBy { it.id },
        roles = listOf(Role("pm", "Project Manager", "#8B5CF6"), Role("qa", "QA Engineer", "#10B981")),
    )

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun theListRowAndTheHeaderShowTheRoleDisplayNameWithTheIdAsSecondaryText() {
        runDesktopComposeUiTest(900, 400) {
            setContent {
                Column {
                    AgentRow(pm, state, selected = true, onClick = {}, onRemove = {})
                    CenterHeader(pm, spawnMode = false, state = state)
                }
            }
            assertEquals("Project Manager", onNodeWithTag("agent-row-title-${pm.id}", useUnmergedTree = true).text(), "agents list row title")
            assertEquals("Project Manager", onNodeWithTag(AGENT_HEADER_TITLE_TAG, useUnmergedTree = true).text(), "session header title")
            assertEquals("19451abf", onNodeWithTag("agent-row-id-${pm.id}", useUnmergedTree = true).text(), "the id stays as secondary text in the row")
            assertEquals("19451abf", onNodeWithTag(AGENT_HEADER_ID_TAG, useUnmergedTree = true).text(), "the id stays as secondary text in the header")
        }
    }

    @Test
    fun anAgentWithNoRoleStillShowsItsId() {
        runDesktopComposeUiTest(900, 400) {
            setContent {
                Column {
                    AgentRow(bare, state, selected = true, onClick = {}, onRemove = {})
                    CenterHeader(bare, spawnMode = false, state = state)
                }
            }
            assertEquals("7c0ffee1", onNodeWithTag("agent-row-title-${bare.id}", useUnmergedTree = true).text())
            assertEquals("7c0ffee1", onNodeWithTag(AGENT_HEADER_TITLE_TAG, useUnmergedTree = true).text())
            // No duplicate: the id is the title, so no secondary id line repeats it.
            onNodeWithTag("agent-row-id-${bare.id}", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AGENT_HEADER_ID_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun theOutputTabReadsCli() {
        runDesktopComposeUiTest(900, 200) {
            setContent { CenterTabBar(state, "infantry.cli") {} }
            assertEquals("CLI  ^1", onNodeWithTag("center-tab-OUTPUT").text())
        }
    }

    /** Evidence for the DONE report: the agents list + session header, written to build/reports/m2-3. */
    @Test
    fun captureAgentsListAndHeader() {
        val agents = listOf(pm, agent("a1b2c3d4e5", "qa"), bare)
        val all = state.copy(agents = agents.associateBy { it.id })
        runDesktopComposeUiTest(1000, 260) {
            setContent {
                Row(Modifier.fillMaxSize().background(Palette.Bg).testTag("shot")) {
                    Column(Modifier.width(300.dp).background(Palette.Panel)) {
                        agents.forEach { AgentRow(it, all, selected = it == pm, onClick = {}, onRemove = {}) }
                    }
                    Column(Modifier.width(700.dp)) {
                        CenterHeader(pm, spawnMode = false, state = all)
                        CenterTabBar(all, "infantry.cli") {}
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PM").fetchSemanticsNodes().isEmpty() }
            val out = File("build/reports/m2-3/agents-list-and-header.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-3 screenshot: ${out.absolutePath}")
        }
    }
}
