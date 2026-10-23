package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * BUG-M2-15: an agent created while project A is selected is not listed (sidebar row, top-bar avatar) while
 * project B is selected, and is listed again under A. Real composed window over a real in-process daemon
 * (Ktor/WebSocket/SQLite, stub provider), with the window's DEFAULT flags: project scoping is the default.
 */
@OptIn(ExperimentalTestApi::class)
class AgentProjectScopeUiTest {
    /**
     * FLAKE-3 (the FLAKE-1 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait (60 s,
     * AGENTHQ_TEST_WAIT_MS overrides it). Each answer is daemon SQLite I/O, which a concurrent build stalled past 10 s.
     * A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-agent-proj").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            modelCatalogs = HarnessModelCatalogs.EMPTY, // hermetic, see BoardProjectScopeUiTest
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

    @Test fun `an agent created under one project is hidden under another and listed again under its own`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                // FLAKE-1: the daemon answers one connection's requests in order, so the first create below queued
                // behind the window's on-connect burst. Connected means that burst is answered: tool_approval and
                // provider_states answer the last of its two request groups.
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.toolApproval != null && s.providerStates.isNotEmpty()
                }

                fun create(name: String) {
                    onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                    onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput(name)
                    onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                    waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.projects.any { it.name == name } }
                }
                fun pick(id: String) {
                    onNodeWithTag(PROJECT_PICKER_TAG).performClick()
                    onNodeWithTag(PROJECT_OPTION_TAG_PREFIX + id).performClick()
                    waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId == id }
                    waitForIdle()
                }
                create("Alpha")
                create("Beta")
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                val beta = controller.state.value.projects.first { it.name == "Beta" }.id
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId == beta }
                pick(alpha)

                // Under Alpha: a new agent from the composer (no agent selected yet, so Spawn cuts a new session).
                onNodeWithTag(PROMPT_BOX_TAG).performTextInput("alpha task")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.isNotEmpty() }
                val id = controller.state.value.agents.keys.single()
                waitForIdle()
                onNodeWithTag("agent-row-$id").assertExists()
                onNodeWithTag(TITLE_BAR_AVATAR_TAG_PREFIX + id).assertExists()

                // Under Beta: Alpha's agent is neither in the sidebar list nor in the top-bar avatars.
                pick(beta)
                onNodeWithTag("agent-row-$id").assertDoesNotExist()
                onNodeWithTag(TITLE_BAR_AVATAR_TAG_PREFIX + id).assertDoesNotExist()
                assertEquals(alpha, controller.state.value.agents.getValue(id).session.projectId, "the session frame names the project it was created under")

                // Back under Alpha: listed again.
                pick(alpha)
                onNodeWithTag("agent-row-$id").assertExists()
                onNodeWithTag(TITLE_BAR_AVATAR_TAG_PREFIX + id).assertExists()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
