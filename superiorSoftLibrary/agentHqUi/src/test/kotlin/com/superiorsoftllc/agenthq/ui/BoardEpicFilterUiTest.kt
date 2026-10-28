package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.header.*

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
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

/**
 * M2-14: a task filed under an epic from the New ticket form, its epic chip on the card, and the board's Epic
 * filter. A real composed window over a real in-process daemon (Ktor/WebSocket/SQLite, stub provider); the
 * epic is made through the controller (the Epics pane's request), everything else is clicks and typing.
 */
@OptIn(ExperimentalTestApi::class)
class BoardEpicFilterUiTest {
    /**
     * FLAKE-1: the budget for every wait on a daemon round trip here, the same as agentHq's TestWait (60 s,
     * AGENTHQ_TEST_WAIT_MS overrides it). The work under each wait is daemon file I/O: a fresh SQLite connection
     * for each request thread, and its WAL. Under load, a Windows host stalled one file open for ~10 s, so 10 s
     * was not enough. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-board-epic").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
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

    @Test fun `a task filed under an epic shows its chip, and the Epic filter hides the others until All epics`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                // FLAKE-1: the daemon answers one connection's requests in order, so the first create below queued
                // behind the window's whole on-connect burst, and its 10 s ran out. "Connected" now means that
                // burst is answered. On `ready` the window sends two request groups: tool_approval answers the
                // last of one, provider_states the last of the other.
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.toolApproval != null && s.providerStates.isNotEmpty()
                }

                onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput("Alpha")
                onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.projects.any { it.name == "Alpha" } }
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId == alpha }

                assertIs<CommandResult.Sent>(runBlocking { controller.saveEpic(alpha, "Checkout") })
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.epics[alpha].orEmpty().any { it.name == "Checkout" } }
                val epic = controller.state.value.epics.getValue(alpha).first { it.name == "Checkout" }.id

                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()

                // (a) A task filed under the epic through the form's Epic drop-down.
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("pay with card")
                onNodeWithTag(TICKET_EPIC_PICKER_TAG).performClick()
                onNodeWithTag(TICKET_EPIC_OPTION_TAG_PREFIX + epic).performClick()
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "pay with card" } }
                val filed = controller.state.value.tickets.first { it.title == "pay with card" }
                assertEquals(epic, filed.epicId, "the ticket_save carried the picked epic and the daemon stored it")
                onNode(hasTestTag("ticket-epic-${filed.id}") and hasAnyDescendant(hasText("Checkout")), useUnmergedTree = true).assertExists()

                // A task left on "None".
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("fix the logo")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "fix the logo" } }
                val loose = controller.state.value.tickets.first { it.title == "fix the logo" }
                assertNull(loose.epicId, "a ticket left on None has no epic")
                onNodeWithText("fix the logo").assertExists()

                // (b) Filter on the epic: the loose ticket is gone, the filed one stays.
                onNodeWithTag(BOARD_EPIC_FILTER_TAG).performClick()
                onNodeWithTag(BOARD_EPIC_FILTER_OPTION_TAG_PREFIX + epic).performClick()
                waitForIdle()
                onNodeWithText("fix the logo").assertDoesNotExist()
                onNodeWithText("pay with card").assertExists()

                val out = File("build/reports/m2-14/board-epic-filter.png").apply { parentFile.mkdirs() }
                ImageIO.write(onNodeWithTag(BOARD_MODAL_TAG).captureToImage().toAwtImage(), "png", out)
                println("M2-14 screenshot: ${out.absolutePath}")

                // "No epic" shows only the loose one; "All epics" shows both again.
                onNodeWithTag(BOARD_EPIC_FILTER_TAG).performClick()
                onNodeWithTag(BOARD_EPIC_FILTER_OPTION_TAG_PREFIX + "none").performClick()
                waitForIdle()
                onNodeWithText("fix the logo").assertExists()
                onNodeWithText("pay with card").assertDoesNotExist()

                onNodeWithTag(BOARD_EPIC_FILTER_TAG).performClick()
                onNodeWithTag(BOARD_EPIC_FILTER_OPTION_TAG_PREFIX + "all").performClick()
                waitForIdle()
                onNodeWithText("fix the logo").assertExists()
                onNodeWithText("pay with card").assertExists()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
