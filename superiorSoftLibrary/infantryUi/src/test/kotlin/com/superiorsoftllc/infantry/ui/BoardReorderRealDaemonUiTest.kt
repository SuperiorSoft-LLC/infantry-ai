// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * BUG-REORDER (ticket-63df1aba): a card dragged up or down inside a column must land there on the REAL daemon, read
 * back from the daemon's own board, the way the owner uses it: a project selected, and a ticket of no project sitting
 * above the project's tickets in the same column (so the project's view is a filtered slice of the daemon's column).
 */
@OptIn(ExperimentalTestApi::class)
class BoardReorderRealDaemonUiTest {
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("infantry-ui-reorder-daemon").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
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

    private fun until(what: String, c: Controller, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + daemonWaitMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    private fun todoTitles(s: AppState) = s.column(TicketColumns.TODO).map { it.title }

    /** A live controller on a fresh daemon, with one loose ticket on top of To do and [titles] filed in a selected project. */
    private fun seeded(titles: List<String>): Triple<Int, Controller, String> {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        // A ticket of no project first: it sits on top of the daemon's To do, outside the project's view.
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Loose", "", null, null) })
        until("loose ticket listed", c) { s -> s.tickets.any { it.title == "Loose" } }
        assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Reorder project") })
        val project = until("project listed", c) { s -> s.projects.any { it.name == "Reorder project" } }.projects.single { it.name == "Reorder project" }
        runBlocking { c.selectProject(project.id) }
        until("project selected", c) { it.activeProjectId == project.id }
        titles.forEach { title ->
            assertIs<CommandResult.Sent>(runBlocking { c.createTicket(title, "", null, null) })
            until("$title listed", c) { s -> s.tickets.any { it.title == title && it.projectId == project.id } }
        }
        assertEquals(titles, todoTitles(c.state.value))
        return Triple(port, c, project.id)
    }

    /** The order the daemon itself stored, read on a fresh connection rather than from the window's copy. */
    private fun assertStored(port: Int, projectId: String, order: List<String>) {
        val fresh = Controller(scope)
        fresh.connect("ws://127.0.0.1:$port/ws", null)
        until("fresh connection", fresh) { it.connection.state == ConnState.CONNECTED }
        runBlocking { fresh.selectProject(projectId) }
        until("stored order $order", fresh) { s -> s.activeProjectId == projectId && todoTitles(s) == order }
    }

    @Test fun `dragging a card up and then down inside To do reorders it on the real daemon`() {
        val (port, c, projectId) = seeded(listOf("A", "B", "C", "D"))
        runDesktopComposeUiTest(1200, 900) {
            setContent {
                val state by c.state.collectAsState()
                BoardPane(state, c, onOpenAgent = {})
            }
            waitForIdle()
            fun id(title: String) = c.state.value.tickets.single { it.title == title }.id
            fun drag(title: String, toAboveOf: String?, toBelowOf: String?) {
                val handle = onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + id(title), useUnmergedTree = true)
                val from = handle.fetchSemanticsNode().boundsInRoot.center
                val target = onNodeWithTag(TICKET_CARD_TAG_PREFIX + id(toAboveOf ?: toBelowOf!!)).fetchSemanticsNode().boundsInRoot
                // Below a card = past its whole card (title, chips and buttons), not just its title block.
                val y = if (toAboveOf != null) target.top - 4f else target.bottom + 80f
                handle.performMouseInput {
                    moveTo(center)
                    press()
                    repeat(10) { moveBy(Offset(0f, (y - from.y) / 10)) }
                    release()
                }
                waitForIdle()
            }
            // Waits pump frames (a blocking sleep would freeze the board on its old layout between drags).
            fun landed(order: List<String>) {
                waitUntil("To do reads $order", daemonWaitMs) { todoTitles(c.state.value) == order }
                waitForIdle()
            }

            drag("C", toAboveOf = "A", toBelowOf = null)
            landed(listOf("C", "A", "B", "D"))
            drag("A", toAboveOf = null, toBelowOf = "D")
            landed(listOf("C", "B", "D", "A"))
            drag("B", toAboveOf = "C", toBelowOf = null)
            landed(listOf("B", "C", "D", "A"))
        }
        assertStored(port, projectId, listOf("B", "C", "D", "A"))
    }
}
