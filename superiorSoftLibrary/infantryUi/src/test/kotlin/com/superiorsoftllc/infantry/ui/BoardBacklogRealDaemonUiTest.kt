package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.superiorsoftllc.infantry.ui.model.BoardDrop
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
 * BACKLOG (contract 1.131.0, ticket-39eab724): the board's Backlog lane on the REAL daemon. The lane is drawn first, a
 * card dragged from To do into Backlog and back moves there on the daemon (read back from the daemon's own board on a
 * fresh connection), a backlog card offers no Start, and a drop of a backlog card on In progress is refused in the UI
 * with a note and sends nothing (the ticket stays in Backlog on the daemon).
 */
@OptIn(ExperimentalTestApi::class)
class BoardBacklogRealDaemonUiTest {
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("infantry-ui-backlog-daemon").toFile()
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

    /** The column the daemon itself stored for [title], read on a fresh connection rather than from the window's copy. */
    private fun assertStored(port: Int, title: String, column: String) {
        val fresh = Controller(scope)
        fresh.connect("ws://127.0.0.1:$port/ws", null)
        until("fresh connection", fresh) { it.connection.state == ConnState.CONNECTED }
        until("$title stored in $column", fresh) { s -> s.tickets.any { it.title == title && it.status == column } }
    }

    @Test fun `a card moves between To do and Backlog by drag and a backlog card is never started`() {
        val port = boot()
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Maybe later", "", null, null) })
        until("ticket listed", c) { s -> s.tickets.any { it.title == "Maybe later" } }
        val id = c.state.value.tickets.single().id
        fun column() = c.state.value.tickets.single().status

        runDesktopComposeUiTest(1400, 900) {
            setContent {
                val state by c.state.collectAsState()
                BoardPane(state, c, onOpenAgent = {})
            }
            waitForIdle()
            // The lane is drawn, first (left of To do).
            val backlog = onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.BACKLOG).fetchSemanticsNode().boundsInRoot
            val todo = onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO).fetchSemanticsNode().boundsInRoot
            assert(backlog.right <= todo.left) { "Backlog is drawn before To do: $backlog vs $todo" }

            fun dragTo(column: String) {
                val handle = onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + id, useUnmergedTree = true)
                val from = handle.fetchSemanticsNode().boundsInRoot.center
                val target = onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + column).fetchSemanticsNode().boundsInRoot
                val by = Offset(target.center.x - from.x, target.top + 120f - from.y)
                handle.performMouseInput {
                    moveTo(center)
                    press()
                    repeat(10) { moveBy(by / 10f) }
                    release()
                }
                waitForIdle()
            }

            // Not vacuous: the same card in To do offers Start.
            val onCard = hasAnyAncestor(hasTestTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO))
            assertEquals(1, onAllNodes(onCard and hasText("Start"), useUnmergedTree = true).fetchSemanticsNodes().size, "a todo card offers Start")

            dragTo(TicketColumns.BACKLOG)
            waitUntil("the card is in Backlog", daemonWaitMs) { column() == TicketColumns.BACKLOG }
            waitForIdle()
            // A backlog card offers To do, never Start.
            val card = hasAnyAncestor(hasTestTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.BACKLOG))
            assertEquals(0, onAllNodes(card and hasText("Start"), useUnmergedTree = true).fetchSemanticsNodes().size, "no Start on a backlog card")
            assertEquals(1, onAllNodes(card and hasText("To do"), useUnmergedTree = true).fetchSemanticsNodes().size, "a backlog card offers To do")

            // A drop on In progress is refused in the UI, with a note; nothing reaches the daemon.
            dragTo(TicketColumns.IN_PROGRESS)
            onNodeWithTag(TICKET_DROP_NOTE_TAG_PREFIX + id, useUnmergedTree = true).assertTextContains(BoardDrop.BACKLOG_START_REFUSAL, substring = true)
            assertEquals(TicketColumns.BACKLOG, column())
        }
        assertStored(port, "Maybe later", TicketColumns.BACKLOG)

        runDesktopComposeUiTest(1400, 900) {
            setContent {
                val state by c.state.collectAsState()
                BoardPane(state, c, onOpenAgent = {})
            }
            waitForIdle()
            val handle = onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + id, useUnmergedTree = true)
            val from = handle.fetchSemanticsNode().boundsInRoot.center
            val target = onNodeWithTag(BOARD_COLUMN_TAG_PREFIX + TicketColumns.TODO).fetchSemanticsNode().boundsInRoot
            val by = Offset(target.center.x - from.x, target.top + 120f - from.y)
            handle.performMouseInput {
                moveTo(center)
                press()
                repeat(10) { moveBy(by / 10f) }
                release()
            }
            waitForIdle()
            waitUntil("the card is back in To do", daemonWaitMs) { column() == TicketColumns.TODO }
        }
        assertStored(port, "Maybe later", TicketColumns.TODO)
    }
}
