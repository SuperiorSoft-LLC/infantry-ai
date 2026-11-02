package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.plugins.official.roles.daemon.RoleService
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
 * ROLE-EDIT-1 in the REAL window against a REAL in-process daemon: a role row's "⋯" menu opens "Edit role", both the
 * title and the instructions are changed and saved, the row shows the new title, and the daemon's catalog holds the
 * edit under the SAME role id.
 */
@OptIn(ExperimentalTestApi::class)
class RoleEditUiTest {
    /** FLAKE-7: the budget for a daemon round trip (agentHq's TestWait, AGENTHQ_TEST_WAIT_MS overrides it). */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    @Test
    fun editRoleFromTheRowMenuChangesTitleAndInstructionsAndTheRowShowsTheNewTitle() {
        val dir = Files.createTempDirectory("agenthq-role-edit-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript.default(), delayMs = 0)
        val roles = RoleService(File(dir, "roles"))
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            pluginEnv = { n -> if (n == "AGENTHQ_ROLES_DIR") File(dir, "roles").path else null },
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
        )
        try {
            daemon.server.start(wait = false)
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
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive && controller.state.value.roles.any { it.id == "backend" } }

                onNodeWithText("Roles  ^5").performClick()
                onNodeWithTag(ROLE_ROW_MENU_TAG_PREFIX + "backend").performScrollTo().performClick()
                onNodeWithTag(ROLE_ROW_EDIT_TAG_PREFIX + "backend").performClick()
                waitForIdle()

                onNodeWithTag(ROLE_EDITOR_NAME_TAG).performTextClearance()
                onNodeWithTag(ROLE_EDITOR_NAME_TAG).performTextInput("Backend Renamed")
                onNodeWithTag(ROLE_EDITOR_PERSONA_TAG).performTextClearance()
                onNodeWithTag(ROLE_EDITOR_PERSONA_TAG).performTextInput("EDITED-INSTRUCTIONS: ship small.")
                onNodeWithTag(ROLE_EDITOR_SAVE_TAG).performClick()

                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roles.any { it.id == "backend" && it.name == "Backend Renamed" } }
                waitForIdle()
                // The row is clickable, so its title Text is merged into the row node: read the unmerged one.
                onNodeWithTag(ROLE_ROW_NAME_TAG_PREFIX + "backend", useUnmergedTree = true).assertTextEquals("Backend Renamed")
                assertEquals(1, controller.state.value.roles.count { it.name == "Backend Renamed" }, "a rename adds no second role")

                val stored = roles.role("backend")!!
                assertEquals("Backend Renamed", stored.name, "the daemon's catalog holds the new title under the same id")
                assertEquals("EDITED-INSTRUCTIONS: ship small.", stored.persona, "the daemon's catalog holds the edited instructions")
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
