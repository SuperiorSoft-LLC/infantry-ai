package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.persistence.SettingRow
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * The three new screens driven the way a person drives them: a real composed window (Compose UI test), a
 * real daemon behind it (real Ktor/WebSocket/SQLite, stub provider). Every step is a click or typing on a
 * visible control; the assertions read what is on screen and what the daemon then holds.
 */
@OptIn(ExperimentalTestApi::class)
class BoardUiTest {
    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-window").toFile()
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
            // BUG-GATE19-1: a plain temp dir, not build()'s default user.dir (this git worktree): BUG-WT-1's handOut cuts a
            // worktree from a git project folder and refuses useWorktree=false over one, so the ticket never started.
            workingDir = File(dir, "workdir").apply { mkdirs() },
            modelCatalogs = HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        // tickets.exitBehavior defaults to leave; these flows wait for a clean exit to move the ticket to DONE.
        daemon.store.settings.put(SettingRow("tickets.exitBehavior", "\"done\"", System.currentTimeMillis()))
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    @Test fun `a person creates a profile, a ticket and a trigger by clicking, starts the ticket, and fires the trigger`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive && controller.state.value.roles.isNotEmpty() }

                // Profiles tab: a new profile
                onNodeWithText("Profiles", substring = true).performClick()
                onNodeWithText("+ New profile").performClick()
                onNodeWithText("Name").performTextInput("Builder")
                onNodeWithText("Save profile").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.profiles.size == 1 }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Builder")).fetchSemanticsNodes().isNotEmpty() }

                // Board tab: a ticket for it, then start it
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick() // M2-8: the sidebar button opens the board modal
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("Ship it from the window")
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.size == 1 }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Ship it from the window")).fetchSemanticsNodes().isNotEmpty() }
                // BUG-ROLE-3: the form preselects the profile's role lane and sends it; the daemon never picks one.
                val profileRole = controller.state.value.profiles.single().role
                assertEquals("role:$profileRole", controller.state.value.tickets.single().agentId, "the clicked ticket stores the preselected role")
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.single().let { it.status == TicketColumns.IN_PROGRESS && it.sessionId != null } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.single().lastRun?.outcome != null }
                // BUG-E2E-2: with exitBehavior=done a SUCCEEDED run moves the ticket to DONE on its own; no Done click.
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.single().status == TicketColumns.DONE }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Reopen")).fetchSemanticsNodes().isNotEmpty() }

                onNodeWithTag(BOARD_MODAL_CLOSE_TAG).performClick() // M2-8: back to the window
                // Triggers tab: a trigger, fired on a board with nothing left to start, then its events
                onNodeWithText("Triggers", substring = true).performClick()
                onNodeWithText("+ New trigger").performClick()
                onNodeWithText("Name").performTextInput("Pull next")
                onNodeWithText("Create trigger").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.triggers.size == 1 }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("armed")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Fire now").performClick()
                onNodeWithText("Events").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.triggerEvents.values.any { rows -> rows.any { it.disposition == "nothing_to_start" } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("nothing to start")).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
