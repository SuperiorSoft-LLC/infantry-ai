package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.RolePortrait
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.model.rolePortraitFor
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * M2-1: an agent and its role are one representation. The agents list showed "PM" while the chat
 * showed "1" (the chat derived its initials from the agent's name); now both resolve the role portrait.
 */
@OptIn(ExperimentalTestApi::class)
class AgentPortraitUiTest {
    /** The owner's exact case: a PM agent whose name (branch/id) is "1". */
    private val pm = AgentView(
        session = SessionRecord(id = "1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p"),
        role = "pm",
        entries = listOf(
            TranscriptEntry(1, LineKind.USER, "plan the sprint", 1),
            TranscriptEntry(2, LineKind.TEXT, "Sprint plan drafted: three tickets on the board.", 2),
        ),
    )
    private val state = AppState(agents = mapOf(pm.session.id to pm))

    private fun SemanticsNodeInteraction.portrait(): String = fetchSemanticsNode().config[PortraitKey]

    @Test
    fun theListRowAndTheChatSpeakerRowResolveTheSamePortraitForTheSameAgent() {
        runDesktopComposeUiTest(900, 600) {
            setContent {
                Column {
                    AgentRow(pm, state, selected = true, onClick = {}, onRemove = {})
                    AgentChat(pm)
                }
            }
            val listKey = onNode(hasTestTag(AVATAR_TAG) and hasAnyAncestor(hasTestTag("agent-row-1")), useUnmergedTree = true).portrait()
            // The chat's two speaker rows: the user's own disc (no portrait) then the agent's message.
            val chatKeys = onAllNodes(hasTestTag(AVATAR_TAG) and hasAnyAncestor(hasTestTag(SPEAKER_ROW_TAG)))
                .fetchSemanticsNodes().map { it.config.getOrNull(PortraitKey) }
            assertEquals(RolePortrait.PM.key, listKey)
            assertEquals(listOf(null, listKey), chatKeys, "the chat speaker row must show the same portrait as the agents list")
        }
    }

    /** Every daemon catalog id has a portrait; only the user-defined personas fall back to custom_agent. */
    @Test
    fun everyDaemonCatalogRoleResolvesAPortrait() {
        RoleIds.CANONICAL.filterNot { it == "automation" }.forEach {
            assertNotEquals(RolePortrait.CUSTOM, rolePortraitFor(it), "catalog role '$it' has no portrait")
        }
        assertEquals(RolePortrait.CUSTOM, rolePortraitFor(RoleIds.CUSTOM))
    }

    /** Evidence for the DONE report: the agents list next to a chat, written to build/reports/m2-1. */
    @Test
    fun captureAgentsListAndChat() {
        val agents = listOf("architect", "frontend", "qa", "pm", "i18n", null).mapIndexed { i, role ->
            pm.copy(session = pm.session.copy(id = "a$i", worktreeBranch = role ?: "no-role"), role = role)
        }
        val all = AppState(agents = agents.associateBy { it.session.id })
        runDesktopComposeUiTest(1000, 420) {
            setContent {
                Row(Modifier.fillMaxSize().background(Palette.Bg).testTag("shot")) {
                    Column(Modifier.width(300.dp).background(Palette.Panel)) {
                        agents.forEach { AgentRow(it, all, selected = it.role == "pm", onClick = {}, onRemove = {}) }
                    }
                    Column(Modifier.width(700.dp)) {
                        CenterHeader(pm, spawnMode = false, state = state)
                        AgentChat(pm)
                    }
                }
            }
            // The portraits decode off the first frame; wait until no initials disc is left.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PM").fetchSemanticsNodes().isEmpty() }
            val out = File("build/reports/m2-1/agents-list-and-chat.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-1 screenshot: ${out.absolutePath}")
        }
    }
}
