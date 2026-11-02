package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.roles.*
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
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
import kotlin.test.assertTrue

/**
 * UI-PROV-1: the profile form's model picker for a provider whose ONE flat list spans several
 * sub-providers (what `opencode models` returns: `opencode/...`, `opencode-go/...`, `deepseek/...`).
 *
 * The catalogue arrives the way it does in production -- `knownModels()` on the provider, served by the
 * daemon in the `providers` frame -- so nothing here pushes a hand-made providers frame: a picker that
 * passed this test against the wire could not be satisfied by a list baked into the UI.
 */
@OptIn(ExperimentalTestApi::class)
class ProviderPickerUiTest {

    /** The ids the test's opencode provider reports, deliberately in one flat, unsorted list. */
    private val models = listOf(
        "opencode-go/mimo-v2.6-pro",
        "opencode/nemotron-3.5-lightning-free",
        "opencode-go/gpt-6-luna",
    )

    /**
     * A daemon provider whose catalogue is shaped like opencode's: one list, several sub-providers. The
     * runs themselves are the stub's (nothing here starts a session), only the id and the catalogue differ.
     */
    private class OpenCodeSubProviders(private val models: List<String>) : AgentProvider {
        private val stub = StubProvider(StubScript.default(), delayMs = 0)
        override val id: String = "opencode"
        override fun spec(request: RunRequest): ProcessSpec = stub.spec(request)
        override fun transport(): ProcessRunner = stub.transport()
        override fun defaultModel(): String = models.first()
        override fun knownModels(): List<String> = models
    }

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-ui-prov1").toFile()
        val provider = OpenCodeSubProviders(models)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport(),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
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

    @Test
    fun `choosing a sub-provider narrows the model dropdown to that provider's ids`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1500, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive &&
                        controller.state.value.roles.isNotEmpty() &&
                        controller.state.value.providers.any { it.models?.isNotEmpty() == true }
                }

                // Profiles tab: a new profile on the only provider, whose catalogue spans sub-providers.
                onNodeWithText("Profiles", substring = true).performClick()
                onNodeWithText("+ New profile").performClick()

                // PICKER-ALL-2: the shared ExecutorPicker. The provider (vendor) list is offered above the model list.
                onNodeWithTag(MODEL_SUB_PROVIDER_TAG).assertExists()
                onNodeWithTag("$MODEL_SUB_PROVIDER_TAG-opencode-go").performClick()

                // The model list now offers ONLY opencode-go's ids.
                onNodeWithTag(MODEL_PICKER_TAG).assertExists()
                onNodeWithTag("model-option-opencode/nemotron-3.5-lightning-free").assertDoesNotExist()
                onNodeWithTag("model-option-opencode-go/mimo-v2.6-pro").assertExists()
                onNodeWithTag("model-option-opencode-go/gpt-6-luna").assertExists()

                // Choosing a model stores the FULL wire id (sub-provider and slug), and the profile saves.
                onNodeWithTag("model-option-opencode-go/gpt-6-luna").performClick()
                onNodeWithText("Name").performScrollTo().performTextInput("Luna")
                onNodeWithText("Save profile").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.profiles.size == 1 }
                val saved = controller.state.value.profiles.single()
                assertEquals("opencode-go/gpt-6-luna", saved.model, "the stored model is the full id, slash and all")
                // The sub-provider is NOT a provider: it never leaks into the profile's provider field. No
                // provider chip was clicked here, so that field stays on the daemon's default (null).
                assertTrue(
                    saved.provider == null || saved.provider == "opencode",
                    "the provider stays 'opencode'/'default', never 'opencode-go': ${saved.provider}",
                )

                // Requirement 2: editing that profile opens the picker on the sub-provider its stored model
                // belongs to, with the two-level list already narrowed.
                onNodeWithText("Edit").performClick()
                onNodeWithText("Model: opencode-go/gpt-6-luna").assertExists()
                onNodeWithTag("model-option-opencode-go/gpt-6-luna").assertExists()
                onNodeWithTag("model-option-opencode/nemotron-3.5-lightning-free").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    /**
     * Requirement 3, the OTHER form that picks the model of a NEW agent: the role-scoped profile editor
     * (Roles tab -> a role -> the "+" that opens the profile modal (FORM-MODAL-2)). Its profile is what that pane's "Start session"
     * spawns (`Controller.spawnAs`), and it carried a free-text model field, so it gets the same two-level
     * picker as the Profiles pane instead of staying the one place an opencode id had to be typed blind.
     */
    @Test
    fun `the role-scoped profile editor gets the same two-level picker`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1500, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive &&
                        controller.state.value.roles.isNotEmpty() &&
                        controller.state.value.providers.any { it.models?.isNotEmpty() == true }
                }

                // Roles tab -> the architect role -> a NEW profile inside it.
                onNodeWithText("Roles", substring = true).performClick()
                onNodeWithText("architect").performClick()
                onNodeWithTag(PROFILE_ADD_BUTTON_TAG).performScrollTo().performClick()

                // Level one is offered here too, and narrows level two to the chosen sub-provider.
                onNodeWithTag(MODEL_SUB_PROVIDER_TAG).assertExists() // in the modal now, which does not scroll
                // A semantics click: in this 1500x1000 test window a pointer click on this row does not reach it (see report).
                onNodeWithTag("$MODEL_SUB_PROVIDER_TAG-opencode-go").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                onNodeWithTag(MODEL_PICKER_TAG).assertExists()
                onNodeWithTag("model-option-opencode-go/gpt-6-luna").assertExists()
                onNodeWithTag("model-option-opencode-go/mimo-v2.6-pro").assertExists()
                onNodeWithTag("model-option-opencode/nemotron-3.5-lightning-free").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }
}
