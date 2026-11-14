// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import com.superiorsoftllc.infantry.ui.model.*

/**
 * CONTEXT-LIMIT-2: the context limit in the ticket editor (field, presets, support line, save round trip over a real
 * in-process daemon) and the session chip as composed. The wording rules are in ContextLimitModelTest.
 */
@OptIn(ExperimentalTestApi::class)
class ContextLimitUiTest {
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

    private fun storedLimit(c: Controller, id: String): Int? = c.state.value.tickets.first { it.id == id }.contextLimit

    @Test fun `the chip renders its text and nothing is composed when no limit is set`() = runDesktopComposeUiTest {
        setContent {
            contextLimitChip(200_000, "unsupported", "not mapped")?.let { ContextLimitChipView(it) }
            contextLimitChip(null, null)?.let { ContextLimitChipView(it) }
        }
        onNodeWithText("limit 200k (not applied)").assertIsDisplayed()
        onAllNodes(hasTestTag(CONTEXT_LIMIT_CHIP_TAG), useUnmergedTree = true).assertCountEquals(1)
    }

    /** A real window on the Board of a fresh project; [block] gets the controller. */
    private fun withBoard(block: ComposeUiTest.(Controller) -> Unit) {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1300) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive }
                dismissSetupWizard(controller)
                onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput("Alpha")
                onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId != null }
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
                block(controller)
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    @Test fun `a new ticket created with the switch on and a preset stores it and the card names it`() = withBoard { controller ->
        onNodeWithText("+ New ticket").performClick()
        onNodeWithText("Title").performTextInput("big refactor")
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.switch).performClick()
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.note).assertExists()
        onNodeWithTag(CONTEXT_LIMIT_PRESET_TAG_PREFIX + "1m").performClick()
        onNodeWithText("Create ticket").performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "big refactor" } }
        val id = controller.state.value.tickets.first { it.title == "big refactor" }.id
        assertEquals(1_000_000, storedLimit(controller, id))
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("context limit 1M (ticket)", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `the editor sets a typed limit on a ticket that had none`() = withBoard { controller ->
        onNodeWithText("+ New ticket").performClick()
        onNodeWithText("Title").performTextInput("plain")
        onNodeWithText("Create ticket").performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "plain" } }
        val id = controller.state.value.tickets.first { it.title == "plain" }.id
        assertNull(storedLimit(controller, id), "switch off: no limit")
        onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.switch).performClick()
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.field).performTextInput("300k")
        onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { storedLimit(controller, id) == 300_000 }
    }

    @Test fun `an edit that leaves the switch alone keeps the stored limit`() = withBoard { controller ->
        assertIs<CommandResult.Sent>(runBlocking { controller.createTicket("kept", "", null, "", contextLimit = 200_000) })
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "kept" } }
        val id = controller.state.value.tickets.first { it.title == "kept" }.id
        assertEquals(200_000, storedLimit(controller, id))
        // Off = unchanged: a title edit leaves the limit as it was.
        onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
        onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextClearance()
        onNodeWithTag(TICKET_EDIT_TITLE_TAG).performTextInput("kept v2")
        onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.first { it.id == id }.title == "kept v2" }
        assertEquals(200_000, storedLimit(controller, id))
    }

    @Test fun `switching the editor off clears the stored limit`() = withBoard { controller ->
        assertIs<CommandResult.Sent>(runBlocking { controller.createTicket("limited", "", null, "", contextLimit = 200_000) })
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.any { it.title == "limited" } }
        val id = controller.state.value.tickets.first { it.title == "limited" }.id
        assertEquals(200_000, storedLimit(controller, id))
        onNodeWithTag(TICKET_CARD_TAG_PREFIX + id).performClick()
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.field).assertExists()
        onNodeWithTag(TICKET_CONTEXT_LIMIT_TAGS.switch).performClick()
        onNodeWithTag(TICKET_EDIT_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { storedLimit(controller, id) == null }
    }
}
