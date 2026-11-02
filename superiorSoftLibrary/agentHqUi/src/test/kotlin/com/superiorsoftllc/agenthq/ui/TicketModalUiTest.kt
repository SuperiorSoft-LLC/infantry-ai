package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*

import com.superiorsoftllc.agenthq.plugins.official.header.*

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class TicketModalUiTest {
    /**
     * FLAKE-8 (the FLAKE-1/FLAKE-6 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). An edit's ticket_save is a SQLite write transaction, then the `ticket`
     * push this window reads, so under suite load it landed after the old 10 s read-back, whose timeout was swallowed
     * into a read of the pre-edit epic. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-ticket-modal").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    @Test fun `Open shows the ticket modal over the board, Back and Esc close it, the inline editor still works`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive }
                onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput("Alpha")
                onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.projects.any { it.name == "Alpha" } }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId != null }
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("modal me")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "modal me" } }
                val id = controller.state.value.tickets.first { it.title == "modal me" }.id

                // Open -> modal over the board (the board stays); Back closes it.
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_OPEN_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_MODAL_TAG).assertExists()
                onNodeWithTag(BOARD_MODAL_TAG).assertExists()
                onNodeWithTag(TICKET_MODAL_COMMENTS_TAG).assertExists()
                onNodeWithTag(TICKET_MODAL_BACK_TAG).performClick()
                onAllNodes(hasTestTag(TICKET_MODAL_TAG)).assertCountEquals(0)
                onNodeWithTag(BOARD_MODAL_TAG).assertExists()

                // Esc closes it too.
                onNodeWithTag(TICKET_OPEN_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_MODAL_TAG).assertExists()
                onNodeWithTag(TICKET_MODAL_TAG).performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
                onAllNodes(hasTestTag(TICKET_MODAL_TAG)).assertCountEquals(0)

                // The inline editor still edits.
                onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextClearance()
                onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextInput("modal me inline")
                onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.id == id && it.title == "modal me inline" } }
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
