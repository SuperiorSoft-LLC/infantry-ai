package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.plugins.official.roles.daemon.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.RoleIcons
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
import kotlin.test.assertNotNull

/**
 * ROLE-ICON-1 in the REAL window against a REAL in-process daemon (contract 1.93.0): a role with no icon paints its
 * kind's default; picking an icon in Edit role and saving stores it in the daemon, and the role card, the role's detail
 * header and the New agent role picker all paint the saved icon. The icon a node paints is read from its
 * [RoleIconKey] semantics, so the assertion is about the glyph chosen, not about pixels.
 */
@OptIn(ExperimentalTestApi::class)
class RoleIconUiTest {
    /** FLAKE-7: the budget for a daemon round trip (agentHq's TestWait, AGENTHQ_TEST_WAIT_MS overrides it). */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun paints(key: String) = SemanticsMatcher.expectValue(RoleIconKey, key)

    @Test
    fun anIconPickedInEditRoleShowsOnTheRoleCardAndInTheNewAgentPicker() {
        val dir = Files.createTempDirectory("agenthq-role-icon-ui").toFile()
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
        var saved: AppState? = null
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
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive && controller.state.value.roles.any { it.id == "qa" } }
                assertEquals(true, controller.state.value.connection.sendsRoleIcon, "the in-process daemon speaks contract 1.93.0")

                onNodeWithText("Roles  ^5").performClick()
                waitForIdle()
                // No icon stored yet: the card paints the default for the qa kind.
                onNodeWithTag(roleIconTag(RoleIconSite.ROW, "qa"), useUnmergedTree = true).assert(paints(RoleIcons.defaultFor("qa")))

                onNodeWithTag(ROLE_ROW_MENU_TAG_PREFIX + "qa").performScrollTo().performClick()
                onNodeWithTag(ROLE_ROW_EDIT_TAG_PREFIX + "qa").performClick()
                waitForIdle()
                onNodeWithTag(ROLE_EDITOR_ICON_TAG_PREFIX + "shield").performScrollTo().performClick()
                onNodeWithTag(ROLE_EDITOR_SAVE_TAG).performScrollTo().performClick()

                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.role("qa")?.icon == "shield" }
                waitForIdle()
                assertEquals("shield", roles.role("qa")!!.icon, "the daemon's catalog stores the picked icon")
                onNodeWithTag(roleIconTag(RoleIconSite.ROW, "qa"), useUnmergedTree = true).assert(paints("shield"))
                onNodeWithTag(roleIconTag(RoleIconSite.DETAIL, "qa"), useUnmergedTree = true).assert(paints("shield"))
                // Another role is untouched and keeps its own default.
                onNodeWithTag(roleIconTag(RoleIconSite.ROW, "backend"), useUnmergedTree = true).assert(paints(RoleIcons.defaultFor("backend")))
                saved = controller.state.value
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }

        // The New agent picker, fed the catalog exactly as the daemon pushed it after the save.
        val state = assertNotNull(saved)
        runDesktopComposeUiTest(1200, 900) {
            setContent { NewAgentDialog(state, { _, _, _, _, _ -> null }, {}) }
            waitForIdle()
            onNodeWithTag(roleIconTag(RoleIconSite.PICKER, "qa"), useUnmergedTree = true).assert(paints("shield"))
            onNodeWithTag(roleIconTag(RoleIconSite.PICKER, "backend"), useUnmergedTree = true).assert(paints(RoleIcons.defaultFor("backend")))
        }
    }
}
