package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-5: each agent card has a small "⋯" button that opens a drop-down of the row's actions, Remove included.
 * Remove never deletes straight away: it opens a confirm dialog ("Remove <role name>? This can't be undone.")
 * and only its destructive Remove button calls onRemove; Cancel and Esc leave the agent alone.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRowMenuUiTest {
    private val now = System.currentTimeMillis()
    private fun agent(id: String, role: String, state: SessionState, line: String) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = now - 60_000, state = state, providerSessionId = "p"),
        role = role,
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, line, 1)),
    )

    private val qa = agent("qa000001", "qa", SessionState.BUSY, "Running the regression suite")
    private val be = agent("be000001", "backend", SessionState.IDLE, "Merged the session endpoint.")
    private val agents = listOf(qa, be)
    private val state = AppState(
        agents = agents.associateBy { it.id },
        selectedId = qa.id,
        roles = listOf(Role("qa", "QA Engineer", "#10B981"), Role("backend", "Backend Developer", "#3B82F6")),
        connection = ConnectionInfo(state = ConnState.CONNECTED),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.openRemoveDialog(id: String) {
        onNodeWithTag("session-menu-$id").performClick()
        onNodeWithTag("session-remove-$id").performClick()
        waitForIdle()
    }

    @Test
    fun clickingRemoveInTheMenuOpensAConfirmAndDoesNotRemoveYet() {
        val removed = mutableListOf<String>()
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = { removed += it }) }
            openRemoveDialog(qa.id)
            onNodeWithTag("session-remove-dialog").assertExists()
            onNodeWithText("Remove QA Engineer? This can't be undone.").assertExists()
            assertEquals(emptyList(), removed, "Remove in the menu must not remove before confirming")
        }
    }

    @Test
    fun confirmingRemovesThatAgentOnly() {
        val removed = mutableListOf<String>()
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = { removed += it }) }
            openRemoveDialog(qa.id)
            onNodeWithTag("session-remove-confirm").performClick()
            waitForIdle()
            assertEquals(listOf(qa.id), removed, "confirming removes exactly that agent")
            onNodeWithTag("session-remove-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun cancelKeepsTheAgent() {
        val removed = mutableListOf<String>()
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = { removed += it }) }
            openRemoveDialog(qa.id)
            onNodeWithTag("session-remove-cancel").performClick()
            waitForIdle()
            assertEquals(emptyList(), removed, "Cancel keeps the agent")
            onNodeWithTag("session-remove-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun escapeKeepsTheAgent() {
        val removed = mutableListOf<String>()
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state, agents, onSelect = {}, onRemove = { removed += it }) }
            openRemoveDialog(qa.id)
            // Focus starts on Cancel (the safe action), so Esc lands inside the dialog.
            onNodeWithTag("session-remove-cancel").assertIsFocused()
            onNodeWithTag("session-remove-cancel").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(emptyList(), removed, "Esc keeps the agent")
            onNodeWithTag("session-remove-dialog").assertDoesNotExist()
        }
    }

    @Test
    fun theMenuListsTheRowsActionsAndOpenSelectsTheAgent() {
        val selected = mutableListOf<String>()
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state, agents, onSelect = { selected += it }, onRemove = {}) }
            onNodeWithTag("session-menu-${be.id}").performClick()
            onNodeWithTag("session-menu-open-${be.id}").assertExists()
            onNodeWithTag("session-remove-${be.id}").assertExists()
            onNodeWithTag("session-menu-open-${be.id}").performClick()
            waitForIdle()
            assertEquals(listOf(be.id), selected)
            onNodeWithTag("session-remove-${be.id}").assertDoesNotExist()
        }
    }

    @Test
    fun theMenuButtonIsDisabledWhileDisconnected() {
        runDesktopComposeUiTest(400, 600) {
            setContent { AgentsList(state.copy(connection = ConnectionInfo()), agents, onSelect = {}, onRemove = {}) }
            onNodeWithTag("session-menu-${qa.id}").performClick()
            waitForIdle()
            onNodeWithTag("session-remove-${qa.id}").assertDoesNotExist()
        }
    }

    /** Evidence for the DONE report: the open row menu and the confirm dialog, written to build/reports/m2-5. */
    @Test
    fun captureMenuAndDialog() {
        runDesktopComposeUiTest(320, 360) {
            setContent { Box(Modifier.fillMaxSize().background(Palette.Panel)) { AgentsList(state, agents, onSelect = {}, onRemove = {}) } }
            onNodeWithTag("session-menu-${qa.id}").performClick()
            waitForIdle()
            val menu = File("build/reports/m2-5/row-menu-open.png").apply { parentFile.mkdirs() }
            ImageIO.write(composite(), "png", menu)
            onNodeWithTag("session-remove-${qa.id}").performClick()
            waitForIdle()
            val dialog = File("build/reports/m2-5/remove-confirm-dialog.png")
            ImageIO.write(composite(), "png", dialog)
            assertTrue(menu.length() > 0 && dialog.length() > 0)
            println("M2-5 screenshots: ${menu.absolutePath} ; ${dialog.absolutePath}")
        }
    }

    /** Every root (the window content plus each popup/dialog layer) painted onto one image, in order. */
    private fun androidx.compose.ui.test.ComposeUiTest.composite(): BufferedImage {
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val base = roots.first()
        val out = BufferedImage(base.size.width, base.size.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        roots.indices.forEach { i ->
            val node = roots[i]
            val img = onAllNodes(isRoot())[i].captureToImage().toAwtImage()
            val pos = node.boundsInWindow
            g.drawImage(img, pos.left.toInt(), pos.top.toInt(), null)
        }
        g.dispose()
        return out
    }
}
