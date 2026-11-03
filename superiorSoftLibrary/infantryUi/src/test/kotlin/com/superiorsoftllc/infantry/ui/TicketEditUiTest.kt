package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import com.superiorsoftllc.infantry.plugins.official.header.*

import com.superiorsoftllc.infantry.plugins.official.board.*

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
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
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
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
 * M2-17: edit an existing task from its board card. A real composed window over a real in-process daemon
 * (Ktor/WebSocket/SQLite, stub provider): a ticket created with no epic is moved to an epic through the card's
 * edit form, then back to None, and each time the daemon's stored ticket and the card chip agree.
 */
@OptIn(ExperimentalTestApi::class)
class TicketEditUiTest {
    /**
     * FLAKE-8 (the FLAKE-1/FLAKE-6 pattern): the budget for every wait on a daemon round trip here, infantry's TestWait
     * (60 s, INFANTRY_TEST_WAIT_MS overrides it). An edit's ticket_save is a SQLite write transaction, then the `ticket`
     * push this window reads, so under suite load it landed after the old 10 s read-back, whose timeout was swallowed
     * into a read of the pre-edit epic. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("infantry-ui-ticket-edit").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
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

    private fun storedEpicOf(controller: Controller, id: String): String? =
        controller.state.value.tickets.first { it.id == id }.epicId

    /**
     * FLAKE-8: wait, within [daemonWaitMs], until the stored ticket [id] (the daemon's `ticket` push, as the controller
     * holds it) carries [expected]. A miss fails as the timeout it is, saying what was read and whether the form was
     * still saving, so a slow save, a refused one and a save that never carried the epic each read differently.
     */
    private fun ComposeUiTest.awaitStoredEpic(controller: Controller, id: String, expected: String?, what: String) {
        val start = System.nanoTime()
        runCatching { waitUntil(timeoutMillis = daemonWaitMs) { storedEpicOf(controller, id) == expected } }.onFailure {
            val waitedMs = (System.nanoTime() - start) / 1_000_000
            val s = controller.state.value
            val stored = s.tickets.firstOrNull { t -> t.id == id }
            val saving = onAllNodes(hasTestTag(TICKET_EDIT_SAVE_TAG) and hasText("Saving…")).fetchSemanticsNodes().isNotEmpty()
            throw AssertionError(
                "$what: expected epic $expected, read ${stored?.let { t -> "epic ${t.epicId}" } ?: "no ticket"} after $waitedMs ms " +
                    "(budget $daemonWaitMs ms; form saving=$saving, ticketError=${s.ticketError}, live=${s.connection.isLive})",
                it,
            )
        }
    }

    @Test fun `editing a card files the ticket under an epic, then None clears it`() {
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
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId == alpha }

                assertIs<CommandResult.Sent>(runBlocking { controller.saveEpic(alpha, "Checkout") })
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.epics[alpha].orEmpty().any { it.name == "Checkout" } }
                val epicA = controller.state.value.epics.getValue(alpha).first { it.name == "Checkout" }.id

                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()

                // A ticket created with no epic.
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("pay with card")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "pay with card" } }
                val id = controller.state.value.tickets.first { it.title == "pay with card" }.id
                assertNull(storedEpicOf(controller, id), "created on None: no epic yet")
                val chip = hasTestTag("ticket-epic-$id")

                // Edit 1: click the card, rename it, pick epic A, Save.
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextClearance()
                onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextInput("pay with card or wallet")
                onNodeWithTag(TICKET_EDIT_EPIC_PICKER_TAG).performClick()
                onNodeWithTag(TICKET_EDIT_EPIC_OPTION_TAG_PREFIX + epicA).performClick()
                onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
                awaitStoredEpic(controller, id, epicA, "the edit's ticket_save stored epic A")
                assertEquals("pay with card or wallet", controller.state.value.tickets.first { it.id == id }.title)
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(TICKET_EDIT_SAVE_TAG)).fetchSemanticsNodes().isEmpty() }
                onNode(chip and hasAnyDescendant(hasText("Checkout")), useUnmergedTree = true).assertExists()

                val out = File("build/reports/m2-17/ticket-edit.png").apply { parentFile.mkdirs() }
                ImageIO.write(onNodeWithTag(BOARD_MODAL_TAG).captureToImage().toAwtImage(), "png", out)
                println("M2-17 screenshot: ${out.absolutePath}")

                // Edit 2: back to None clears the epic, on the daemon and on the card.
                onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
                onNodeWithTag(TICKET_EDIT_EPIC_PICKER_TAG).performClick()
                onNodeWithTag(TICKET_EDIT_EPIC_OPTION_TAG_PREFIX + "none").performClick()
                onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
                awaitStoredEpic(controller, id, null, "None cleared the stored epic")
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(TICKET_EDIT_SAVE_TAG)).fetchSemanticsNodes().isEmpty() }
                onNode(chip, useUnmergedTree = true).assertDoesNotExist()
                assertEquals("pay with card or wallet", controller.state.value.tickets.first { it.id == id }.title, "clearing the epic kept the title")
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
