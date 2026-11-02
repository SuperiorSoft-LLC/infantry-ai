package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ROSTER-UI-REMOVE-1 (owner, 2026-10-21): the "Who's here" sidebar section is gone. It duplicated the agents list and
 * only appeared after a project switch. Neither the heading nor any `roster-row-*` tag may render after connect, nor
 * after a project switch with an in-session agent (which is exactly when the old section used to appear). REAL
 * desktop window against a REAL in-process daemon (StubProvider).
 */
@OptIn(ExperimentalTestApi::class)
class RosterRemovedUiTest {

    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-roster-removed").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
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

    private fun SemanticsNodeInteractionsProvider.assertNoRoster(ada: String?, phase: String) {
        assertEquals(0, onAllNodesWithText("WHO'S HERE", ignoreCase = true).fetchSemanticsNodes().size, "no Who's here heading $phase")
        if (ada != null) onNodeWithTag("roster-row-$ada").assertDoesNotExist()
    }

    @Test
    fun `no Who's here section after connect nor after a project switch`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope, agentConfirmMs = daemonWaitMs)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive && controller.state.value.roles.isNotEmpty() }
                waitForIdle()
                assertNoRoster(null, "after connect")

                assertSent(runBlocking { controller.createProject("Alpha") })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == "Alpha" } }
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == alpha }
                val role = controller.state.value.roles.first().id
                assertSent(runBlocking { controller.addAgent(role, null, "ada task", name = "Ada") })
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.size == 1 }
                val sid = controller.state.value.agents.keys.single()
                val ada = controller.state.value.profiles.first { it.name == "Ada" }.id!!

                // The project switch is what used to ask for (and render) the roster. Give its answer time to land,
                // checking on every frame so a roster that appears at any point fails the test.
                assertSent(runBlocking { controller.selectProject(alpha) })
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists("agent-row-$sid") }
                val until = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < until) {
                    waitForIdle()
                    assertNoRoster(ada, "after a project switch")
                    runBlocking { delay(50) }
                }
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    private fun SemanticsNodeInteractionsProvider.onAllNodesWithTagExists(tag: String): Boolean =
        runCatching { onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false)

    private fun assertSent(r: CommandResult) {
        check(r is CommandResult.Sent) { "expected the command to be sent, was $r" }
    }
}
