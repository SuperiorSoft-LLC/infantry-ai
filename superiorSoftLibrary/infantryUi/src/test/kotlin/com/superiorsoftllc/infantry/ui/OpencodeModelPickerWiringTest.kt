package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.net.Controller
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
 * WIRE-3: in the REAL window against a REAL in-process daemon, provider `opencode` lists MORE THAN ONE model in the
 * mounted picker (New agent -> the picker above the prompt) and the model the human clicks is the model the spawned
 * session runs on. Only the CLI is replaced: `opencode models` is a fixed list and the run is scripted.
 */
@OptIn(ExperimentalTestApi::class)
class OpencodeModelPickerWiringTest {
    private val ids = listOf("opencode/space-bunny-free", "opencode-go/gpt-6-luna", "deepseek/deepseek-chat")

    /** Provider id and model catalogue of opencode (its real lister, fed a fixed list); stub spec and transport. */
    private class OpencodeLike(lister: () -> List<String>, stub: StubProvider) : AgentProvider by stub {
        private val real = TestOpenCodeProvider(lister)
        override val id: String = "opencode"
        override fun models() = real.models()
    }

    @Test
    fun `opencode lists several models in the mounted picker and the pick becomes the session model`() {
        val dir = Files.createTempDirectory("infantry-wire3").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "w3", stdoutLines = listOf("""{"type":"step_start","sessionID":"w3-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val provider = OpencodeLike({ ids }, stub)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
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
                waitUntil(timeoutMillis = 20_000) {
                    // GATE-FLAKE-1: the harness chips render from `providers`, a frame separate from `models`; wait for both.
                    controller.state.value.connection.isLive && controller.state.value.modelCatalog.isNotEmpty() && controller.state.value.providers.isNotEmpty()
                }
                // Not shown until the human starts a new agent.
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()

                // RED anchor: the picker state for opencode must carry several models.
                val opencodeModels = controller.state.value.modelCatalog.filter { it.provider == "opencode" }.flatMap { it.models }
                assertEquals(ids.sorted(), opencodeModels.sorted(), "opencode catalogue in picker state")
                assertTrue(opencodeModels.size > 1, "opencode must offer more than one model")
                onNodeWithTag("executor-harness-opencode").performClick()
                waitForIdle()
                ids.forEach { onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + "opencode|" + it).assertExists() }

                // Selection changes what the next spawn runs on.
                onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + "opencode|opencode-go/gpt-6-luna").performClick()
                waitForIdle()
                assertEquals(PickedModel("opencode", "opencode-go/gpt-6-luna"), controller.state.value.pickedModel)

                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.isNotEmpty() }
                val session = controller.state.value.agents.values.single().session
                assertEquals("opencode", session.provider)
                assertEquals("opencode-go/gpt-6-luna", session.model, "the picked model must be the session's model")
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
