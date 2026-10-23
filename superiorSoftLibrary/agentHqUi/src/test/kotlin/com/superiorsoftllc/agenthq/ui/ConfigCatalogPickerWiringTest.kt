package com.superiorsoftllc.agenthq.ui

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
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.ConfigFirstModels
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeConfigLocations
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeConfigReader
import com.superiorsoftllc.agenthq.provider.providerconfig.OpenCodeConfigLocations
import com.superiorsoftllc.agenthq.provider.providerconfig.OpenCodeConfigReader
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

/**
 * CP-1(d): in the REAL window against a REAL in-process daemon, the models the user CONFIGURED in opencode
 * (a signed-in opencode-go provider and a custom one in `opencode.jsonc`) and in Claude (`settings.json`)
 * are visible in the mounted picker, and a picked one becomes the spawned session's model.
 *
 * The providers read FIXTURE files under a temp directory (no real home, no CLI); only the run itself is
 * scripted, by delegating spec/transport to a stub.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigCatalogPickerWiringTest {
    private class Scripted(override val id: String, private val real: AgentProvider, stub: StubProvider) : AgentProvider by stub {
        override fun models() = real.models()
        override fun knownModels() = real.knownModels()
    }

    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `models from the opencode and claude config files are visible in the picker and the pick becomes the session model`() {
        val dir = Files.createTempDirectory("agenthq-cp1-ui").toFile()
        val ocConfig = File(dir, "oc/opencode.jsonc")
        val ocAuth = File(dir, "oc/auth.json")
        val ocModels = File(dir, "oc/models.json")
        val claudeSettings = File(dir, "claude/settings.json")
        write(ocModels, """{"opencode-go":{"id":"opencode-go","name":"OpenCode Go","env":["OPENCODE_API_KEY"],"models":{"kimi-go-1":{"id":"kimi-go-1"},"glm-go-2":{"id":"glm-go-2"}}}}""")
        write(ocAuth, """{"opencode-go":{"type":"api","key":"not-a-real-key"}}""")
        write(ocConfig, """{ // custom provider
            "provider": { "homebrew": { "name": "Home Brew", "models": { "brew-1": {}, } }, },
        }""")
        write(claudeSettings, """{"model":"claude-fixture-9"}""")

        val opencodeReal = OpenCodeProvider(
            ConfigFirstModels(
                OpenCodeConfigReader(OpenCodeConfigLocations(listOf(ocConfig), ocAuth, ocModels)),
                cli = { error("the opencode CLI must not be spawned") },
            ),
        )
        val claudeReal = ClaudeCodeProvider(ClaudeConfigReader(ClaudeConfigLocations(listOf(claudeSettings)))::read)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun stub() = StubProvider(StubScript(name = "cp1", stdoutLines = listOf("""{"type":"step_start","sessionID":"cp1-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val opencode = Scripted("opencode", opencodeReal, stub())
        val claude = Scripted("claude", claudeReal, stub())
        val daemon = AgentHqDaemon.build(
            port = 0, host = "127.0.0.1", provider = opencode, runner = opencode.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            additionalProviders = listOf(claude),
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
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive && controller.state.value.modelCatalog.isNotEmpty()
                }
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()

                // VISIBLE: every model the fixture configs name is a node in the mounted picker, per harness
                // (PICKER-ALL-2: the shared ExecutorPicker shows one harness at a time).
                onNodeWithTag("executor-harness-opencode").performClick()
                waitForIdle()
                listOf("opencode|opencode-go/kimi-go-1", "opencode|opencode-go/glm-go-2", "opencode|homebrew/brew-1")
                    .forEach { onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + it).assertExists() }
                onNodeWithTag("executor-harness-claude").performClick()
                waitForIdle()
                listOf("claude|claude-fixture-9", "claude|sonnet").forEach { onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + it).assertExists() }

                onNodeWithTag(CATALOG_MODEL_TAG_PREFIX + "claude|claude-fixture-9").performScrollTo().performClick()
                waitForIdle()
                assertEquals(PickedModel("claude", "claude-fixture-9"), controller.state.value.pickedModel)

                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.isNotEmpty() }
                val session = controller.state.value.agents.values.single().session
                assertEquals("claude", session.provider)
                assertEquals("claude-fixture-9", session.model, "the model read from the Claude settings file is the session's model")
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
