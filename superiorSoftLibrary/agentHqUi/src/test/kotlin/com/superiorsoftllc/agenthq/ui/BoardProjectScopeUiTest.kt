package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
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
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-BOARD-PROJ: the Board pane shows only the selected project's tickets, driven the way a person
 * drives it -- a real composed window (Compose UI test) over a real daemon (Ktor/WebSocket/SQLite,
 * stub provider). Projects are created in the sidebar, tickets in the Board; every step is a click
 * or typing, and the assertions read what is on screen.
 */
@OptIn(ExperimentalTestApi::class)
class BoardProjectScopeUiTest {
    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-board-proj").toFile()
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
            // Hermetic: build()'s default, HarnessModelCatalogs.builtIn(), runs this host's `pi --list-models`
            // (seconds, up to 20 s on a loaded machine) when the window's connect burst asks for `models`. The
            // daemon answers a connection's requests one at a time, so the test's first create queued behind
            // it and its 10 s wait timed out (BUG-BoardUiFlakes).
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

    @Test fun `the board shows only the selected project's tickets, and a new ticket joins the selected project`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }

                // Title bar (M2-7): two projects made through the "+" New Project modal; each is selected once made.
                fun create(name: String) {
                    onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                    onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput(name)
                    onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == name } }
                }
                // M2-8: the board is a modal over the window, so switching project closes it and opens it again.
                fun pick(id: String) {
                    val boardOpen = onAllNodes(hasTestTag(BOARD_MODAL_TAG)).fetchSemanticsNodes().isNotEmpty()
                    if (boardOpen) onNodeWithTag(BOARD_MODAL_CLOSE_TAG).performClick()
                    onNodeWithTag(PROJECT_PICKER_TAG).performClick()
                    onNodeWithTag(PROJECT_OPTION_TAG_PREFIX + id).performClick()
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == id }
                    if (boardOpen) onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
                }
                create("Alpha")
                create("Beta")
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                val beta = controller.state.value.projects.first { it.name == "Beta" }.id
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == beta }
                pick(alpha)

                // Board (sidebar button, M2-8 modal): a ticket for Alpha (active), then a ticket for Beta (selected first).
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick() // M2-8: the sidebar button opens the board modal
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("alpha ticket")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.any { it.title == "alpha ticket" } }
                assertEquals(alpha, controller.state.value.tickets.first { it.title == "alpha ticket" }.projectId, "a ticket created under Alpha belongs to Alpha")

                pick(beta)
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("beta ticket")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.any { it.title == "beta ticket" } }
                assertEquals(beta, controller.state.value.tickets.first { it.title == "beta ticket" }.projectId, "a ticket created under Beta belongs to Beta")

                // Under Alpha: Alpha's ticket, not Beta's.
                pick(alpha)
                onNodeWithText("alpha ticket").assertExists()
                onNodeWithText("beta ticket").assertDoesNotExist()

                // Under Beta: the reverse.
                pick(beta)
                onNodeWithText("beta ticket").assertExists()
                onNodeWithText("alpha ticket").assertDoesNotExist()

                // A ticket created while Beta is selected joins Beta only.
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("gamma ticket")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.any { it.title == "gamma ticket" } }
                assertEquals(beta, controller.state.value.tickets.first { it.title == "gamma ticket" }.projectId, "gamma was created under Beta")
                onNodeWithText("gamma ticket").assertExists()

                pick(alpha)
                onNodeWithText("alpha ticket").assertExists()
                onNodeWithText("gamma ticket").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
