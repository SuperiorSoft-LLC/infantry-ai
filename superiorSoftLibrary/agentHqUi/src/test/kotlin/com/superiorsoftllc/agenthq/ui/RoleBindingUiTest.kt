package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.PickedModel
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
import kotlin.test.assertTrue

/**
 * ROLE-1c in the REAL window against a REAL in-process daemon: create a role, pick provider + model + effort FOR THE
 * ROLE, see it persisted in the daemon's role-binding store, see it as the default in the New agent box, override it
 * for one spawn, and switch the role's model later without touching the session that already started.
 */
@OptIn(ExperimentalTestApi::class)
class RoleBindingUiTest {
    /**
     * FLAKE-7 (the FLAKE-4/FLAKE-6 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Under suite load the fixed 10-20 s waits timed out on a daemon that
     * answered late (the role_binding_set push, a SQL write on the daemon, came after the 10 s wait). A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private class Second : AgentProvider by StubProvider(StubScript.default(), delayMs = 0) {
        override val id = "second"
        override fun models() = listOf(ProviderModels("second", "alpha", "Alpha", listOf("alpha/one", "alpha/two")))
    }

    @Test
    fun aRoleIsCreatedAndBoundInTheUiTheBindingIsStoredAndIsTheNewAgentDefault() {
        val dir = Files.createTempDirectory("agenthq-role1c-ui").toFile()
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
            roles = RoleService(File(dir, "roles")),
            additionalProviders = listOf(Second()),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
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
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive && controller.state.value.modelCatalog.any { it.provider == "second" } }

                // 1. Create a role in the Roles tab.
                onNodeWithText("Roles  ^5").performClick()
                onNodeWithText("+ New role").performScrollTo().performClick()
                onNodeWithTag(ROLE_EDITOR_ID_TAG).performTextInput("tester-x")
                onNodeWithTag(ROLE_EDITOR_NAME_TAG).performTextInput("Tester X")
                onNodeWithTag(ROLE_EDITOR_SAVE_TAG).performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roles.any { it.id == "tester-x" } }

                // 2. Pick provider + model + effort FOR THE ROLE and save: the daemon's role-binding store has it.
                waitUntil(timeoutMillis = daemonWaitMs) { runCatching { onNodeWithTag(ROLE_BINDING_PROVIDER_TAG_PREFIX + "second").assertExists() }.isSuccess }
                onNodeWithTag(ROLE_BINDING_PROVIDER_TAG_PREFIX + "second").performClick()
                onNodeWithTag(ROLE_BINDING_MODEL_TAG_PREFIX + "second|alpha/two").performScrollTo().performClick()
                onNodeWithTag(ROLE_BINDING_EFFORT_TAG_PREFIX + "high").performScrollTo().performClick()
                onNodeWithTag(ROLE_BINDING_SAVE_TAG).performScrollTo().performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roleBindings["tester-x"] != null }
                val stored = daemon.store.roleBindings.get("tester-x")
                assertEquals("second", stored?.provider)
                assertEquals("alpha/two", stored?.model)
                assertEquals("high", stored?.effort)
                assertTrue(daemon.store.agents.all().none { it.role == "tester-x" }, "binding the role must not create or edit a profile")
                onNodeWithText("Currently: second / alpha/two / effort high").assertExists()

                // 3. New agent box: the role's binding is the default selection.
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()
                onNodeWithTag(SPAWN_ROLE_TAG_PREFIX + "tester-x").performClick()
                waitForIdle()
                assertEquals(PickedModel("second", "alpha/two"), controller.state.value.pickedModel)
                assertEquals("high", controller.state.value.pickedEffort)
                onNodeWithText("Default for tester-x: second / alpha/two / effort high").assertExists()

                // 4. A per-spawn override still works and is shown as an override.
                onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + "second|alpha/one").performScrollTo().performClick()
                waitForIdle()
                assertEquals(PickedModel("second", "alpha/one"), controller.state.value.pickedModel)
                onNodeWithText("overridden for this spawn", substring = true).assertExists()
                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.isNotEmpty() }
                val first = controller.state.value.agents.values.single().session
                assertEquals("second", first.provider)
                assertEquals("alpha/one", first.model, "the override, not the binding, is what this session runs")

                // 5. Switching the role's model later changes the binding only, never the running session.
                runBlocking { controller.saveRoleBinding("tester-x", "second", "alpha/two", "low") }
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.roleBindings["tester-x"]?.effort == "low" }
                assertEquals("low", daemon.store.roleBindings.get("tester-x")?.effort)
                assertEquals("alpha/one", controller.state.value.agents.values.single().session.model)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
