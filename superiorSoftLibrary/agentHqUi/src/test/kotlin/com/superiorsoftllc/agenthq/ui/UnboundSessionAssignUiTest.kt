package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
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
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
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
import kotlin.test.assertNull

/**
 * BUG-M2-15b: a session recorded before spawns carried a project (projectId = null on disk) is listed under EVERY
 * project, marked "no project", and its row menu offers "Assign to <active project>". Assigning binds it in the
 * daemon (`session_project`), after which it is listed only under that project. Real composed window over a real
 * in-process daemon (Ktor/WebSocket/SQLite, stub provider), the unbound record seeded into its sessions.json.
 */
@OptIn(ExperimentalTestApi::class)
class UnboundSessionAssignUiTest {
    private val legacyId = "legacy-unbound-0001"

    private fun bootDaemon(closers: MutableList<() -> Unit>): Pair<AgentHqDaemon, Int> {
        val dir = Files.createTempDirectory("agenthq-ui-unbound").toFile()
        val sessions = JsonSessionStore(File(dir, "sessions.json"))
        // The owner's case: an IDLE session written by a daemon before BUG-M2-15, with no project.
        val now = System.currentTimeMillis()
        sessions.saveAll(listOf(SessionRecord(id = legacyId, provider = "stub", workingDir = dir.absolutePath, spawnedAt = now - 60_000, lastHeartbeat = now - 60_000, state = SessionState.IDLE)))
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = sessions,
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            modelCatalogs = HarnessModelCatalogs.EMPTY, // hermetic, see BoardProjectScopeUiTest
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        val port = runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
        return daemon to port
    }

    @Test fun `an unbound session is listed under every project until it is assigned to one`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (daemon, port) = bootDaemon(closers)
            assertNull(daemon.manager.get(legacyId)?.projectId, "precondition: the seeded session is unbound in the daemon")
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                waitUntil(timeoutMillis = 10_000) { legacyId in controller.state.value.agents }

                fun create(name: String) {
                    onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                    onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput(name)
                    onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.any { it.name == name } }
                }
                fun pick(id: String) {
                    onNodeWithTag(PROJECT_PICKER_TAG).performClick()
                    onNodeWithTag(PROJECT_OPTION_TAG_PREFIX + id).performClick()
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == id }
                    waitForIdle()
                }
                create("Alpha")
                create("Beta")
                val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
                val beta = controller.state.value.projects.first { it.name == "Beta" }.id

                // Unbound: visible, and marked, under BOTH projects.
                pick(beta)
                onNodeWithTag("agent-row-$legacyId").assertExists()
                onNodeWithTag(SESSION_NO_PROJECT_TAG_PREFIX + legacyId, useUnmergedTree = true).assertExists()
                pick(alpha)
                onNodeWithTag("agent-row-$legacyId").assertExists()
                onNodeWithTag(SESSION_NO_PROJECT_TAG_PREFIX + legacyId, useUnmergedTree = true).assertExists()

                // Under Alpha, the row menu offers "Assign to Alpha"; pick it.
                onNodeWithTag("session-menu-$legacyId").performClick()
                onNodeWithText("Assign to Alpha").assertExists()
                onNodeWithTag(SESSION_ASSIGN_TAG_PREFIX + legacyId).performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.agents[legacyId]?.session?.projectId == alpha }
                waitForIdle()

                // The daemon holds the binding (the durable record, not just the window's view).
                assertEquals(alpha, daemon.manager.get(legacyId)?.projectId, "the daemon's session.projectId is the assigned project")
                // Under Alpha: still listed, no longer marked.
                onNodeWithTag("agent-row-$legacyId").assertExists()
                onNodeWithTag(SESSION_NO_PROJECT_TAG_PREFIX + legacyId, useUnmergedTree = true).assertDoesNotExist()
                // Under Beta: gone.
                pick(beta)
                onNodeWithTag("agent-row-$legacyId").assertDoesNotExist()
                onNodeWithTag(TITLE_BAR_AVATAR_TAG_PREFIX + legacyId).assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
