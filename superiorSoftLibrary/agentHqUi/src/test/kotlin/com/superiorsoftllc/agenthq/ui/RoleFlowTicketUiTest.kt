package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*
import com.superiorsoftllc.agenthq.plugins.official.agents.*

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.persistence.SettingRow
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HARD-4: a board ticket assigned to a ROLE lane starts a session on the role's binding, and rebinding the role
 * changes only the NEXT session, never one that already ran.
 */
@OptIn(ExperimentalTestApi::class)
class RoleFlowTicketUiTest {
    /**
     * FLAKE-9 (the FLAKE-6/7/8 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Gate #49 timed out at the role_binding_set wait (DaemonMain
     * ROLE_BINDING_SET: a SQL write, then the role_binding push) after the fixed 10 s; the role, profile and ticket
     * saves and the ticket start/run are the same kind of daemon write-then-push. A passing run waits milliseconds;
     * only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private class Second : AgentProvider by StubProvider(StubScript.default(), delayMs = 0) {
        override val id = "second"
        override fun models() = listOf(ProviderModels("second", "alpha", "Alpha", listOf("alpha/one", "alpha/two")))
    }

    @Test
    fun aBoardTicketAssignedToTheRoleRunsOnTheRoleBindingAndARebindOnlyAffectsTheNextTicket() {
        val dir = Files.createTempDirectory("agenthq-hard4-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            additionalProviders = listOf(Second()),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            // BUG-GATE19-1: a plain temp dir, not build()'s default user.dir (this git worktree): BUG-WT-1's handOut cuts a
            // worktree from a git project folder and refuses useWorktree=false over one, so the ticket never started.
            workingDir = File(dir, "workdir").apply { mkdirs() },
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
        )
        try {
            daemon.server.start(wait = false)
            // tickets.exitBehavior defaults to leave; these flows wait for a clean exit to move the ticket to DONE.
            daemon.store.settings.put(SettingRow("tickets.exitBehavior", "\"done\"", System.currentTimeMillis()))
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive && controller.state.value.modelCatalog.any { it.provider == "second" } }

                // Create + bind the role (alpha/one, effort high).
                onNodeWithText("Roles  ^5").performClick()
                onNodeWithText("+ New role").performScrollTo().performClick()
                onNodeWithTag(ROLE_EDITOR_ID_TAG).performTextInput("tester-x")
                onNodeWithTag(ROLE_EDITOR_NAME_TAG).performTextInput("Tester X")
                onNodeWithTag(ROLE_EDITOR_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roles.any { it.id == "tester-x" } }
                waitUntil(timeoutMillis = daemonWaitMs) { runCatching { onNodeWithTag(ROLE_BINDING_PROVIDER_TAG_PREFIX + "second").assertExists() }.isSuccess }
                onNodeWithTag(ROLE_BINDING_PROVIDER_TAG_PREFIX + "second").performClick()
                onNodeWithTag(ROLE_BINDING_MODEL_TAG_PREFIX + "second|alpha/one").performScrollTo().performClick()
                onNodeWithTag(ROLE_BINDING_EFFORT_TAG_PREFIX + "high").performScrollTo().performClick()
                onNodeWithTag(ROLE_BINDING_SAVE_TAG).performScrollTo().performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roleBindings["tester-x"]?.model == "alpha/one" }

                // A profile of the role with no pins of its own (a ticket's role lane needs one).
                onNodeWithText("Profiles  ^9").performClick()
                onNodeWithText("+ New profile").performClick()
                onNodeWithText("Name").performTextInput("Tex")
                onNodeWithText("tester-x").performClick()
                onNodeWithText("Save profile").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.profiles.any { it.role == "tester-x" } }

                // Ticket 1 on the role lane, started: runs on the binding.
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick() // M2-8: the sidebar button opens the board modal
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("First")
                onNodeWithText("role:tester-x").performClick()
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.size == 1 }
                assertEquals("role:tester-x", controller.state.value.tickets.single().agentId)
                waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasText("First")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.single().sessionId != null }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.single().lastRun?.outcome != null }
                val firstSid = controller.state.value.tickets.single().sessionId!!
                val first = controller.state.value.agents.getValue(firstSid).session
                assertEquals("second", first.provider)
                assertEquals("alpha/one", first.model)
                assertEquals("high", first.effort)
                // BUG-E2E-2: with exitBehavior=done a SUCCEEDED run moves the ticket to DONE on its own; no Done click.
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.single().status == TicketColumns.DONE }

                // Rebind: the finished session keeps its values.
                runBlocking { controller.saveRoleBinding("tester-x", "second", "alpha/two", "low") }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roleBindings["tester-x"]?.model == "alpha/two" }
                val firstAfter = controller.state.value.agents.getValue(firstSid).session
                assertEquals("alpha/one", firstAfter.model)
                assertEquals("high", firstAfter.effort)

                // Ticket 2: the NEXT session uses the new binding.
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("Second")
                onNode(hasText("role:tester-x") and !hasText("First")).performClick()
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.size == 2 }
                waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasText("Second")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.first { it.title == "Second" }.sessionId != null }
                val secondSid = controller.state.value.tickets.first { it.title == "Second" }.sessionId!!
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents[secondSid] != null }
                val second = controller.state.value.agents.getValue(secondSid).session
                assertEquals("alpha/two", second.model)
                assertEquals("low", second.effort)
                assertEquals("alpha/one", controller.state.value.agents.getValue(firstSid).session.model)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
