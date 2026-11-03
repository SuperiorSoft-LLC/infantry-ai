package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.plugins.official.profiles.PROFILE_FORM_ERROR_TAG
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
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
import kotlin.test.assertIs

/**
 * BUG-PROF-1: in the REAL window against a REAL in-process daemon (Ktor/WebSocket/SQLite, stub transport), an
 * existing profile on `opencode` is edited in the Profiles tab to the `claude` harness, then to `pi` (omp), each with
 * a model picked, and each Save changes the provider the DAEMON stores. As on the owner's daemon, only opencode is a
 * CONFIGURED provider: claude and pi are registry harnesses the session manager adopts on demand (WIRE-4).
 */
@OptIn(ExperimentalTestApi::class)
class ProfileHarnessChangeUiTest {
    /** The configured provider: opencode's id and a fixed catalogue over the stub's transport. */
    private class Opencode(private val ids: List<String>) : AgentProvider by StubProvider(StubScript.default(), delayMs = 0) {
        override val id = "opencode"
        override fun models() = listOf(ProviderModels(id, id, id, ids))
        override fun knownModels(): List<String> = ids
    }

    @Test
    fun `editing a profile from opencode to claude then pi stores each new harness`() {
        val dir = Files.createTempDirectory("infantry-prof1").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opencode = Opencode(listOf("opencode/space-bunny-free", "deepseek/deepseek-chat"))
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = opencode, runner = opencode.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            // No additional providers: claude and pi come from the default harness registry, as on the owner's daemon.
            additionalProviders = emptyList(),
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
                    val s = controller.state.value
                    s.connection.isLive && s.roles.isNotEmpty() &&
                        listOf("opencode", "claude", "pi").all { h -> s.providers.any { it.id == h } }
                }
                val role = controller.state.value.roles.first().id
                val seed = AgentProfile(
                    name = "Harness hopper", role = role, provider = "opencode", model = "deepseek/deepseek-chat",
                    projectId = controller.state.value.activeProjectId,
                )
                assertIs<CommandResult.Sent>(runBlocking { controller.saveProfile(seed) })
                waitUntil(timeoutMillis = 10_000) { controller.state.value.profiles.any { it.name == "Harness hopper" } }
                val id = controller.state.value.profiles.first { it.name == "Harness hopper" }.id!!
                assertEquals("opencode", daemon.store.agents.get(id)?.provider)

                onNodeWithText("Profiles  ^9").performClick()
                waitForIdle()

                for (harness in listOf("claude", "pi")) {
                    onNodeWithText("Edit").performScrollTo().performClick()
                    waitForIdle()
                    onNodeWithTag("model-harness-$harness").performScrollTo().performClick()
                    waitForIdle()
                    // Pick a model of the NEW harness, as the owner does: from its list, or typed when it lists none.
                    val listed = controller.state.value.modelCatalog.filter { it.provider == harness }.flatMap { it.models }
                    val model = listed.firstOrNull() ?: "$harness-model-x"
                    if (listed.isNotEmpty()) onNodeWithTag(MODEL_OPTION_TAG_PREFIX + model).performScrollTo().performClick()
                    else onNodeWithTag("model-freetext").performScrollTo().performTextInput(model)
                    waitForIdle()
                    onNodeWithText("Save changes").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        controller.state.value.agentError != null || controller.state.value.profiles.first { it.id == id }.provider == harness
                    }
                    val stored = daemon.store.agents.get(id)
                    assertEquals(harness, stored?.provider, "the daemon's stored provider after switching to $harness (refusal: ${controller.state.value.agentError})")
                    assertEquals(model, stored?.model, "the daemon's stored model after switching to $harness")
                    // The form closes only once the daemon answered with the stored profile.
                    waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithText("Save changes").assertDoesNotExist() }.isSuccess }
                }

                // A refused save keeps the form open and shows the daemon's own words, never a silent close.
                val before = daemon.store.agents.get(id)
                onNodeWithText("Edit").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("model-freetext").performScrollTo().performTextClearance()
                onNodeWithTag("model-freetext").performTextInput("bad model")
                onNodeWithText("Save changes").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag(PROFILE_FORM_ERROR_TAG).assertExists() }.isSuccess }
                onNodeWithTag(PROFILE_FORM_ERROR_TAG).assertTextContains("agent_save refused", substring = true)
                onNodeWithText("Save changes").assertExists()
                assertEquals(before, daemon.store.agents.get(id), "a refused save stores nothing")
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
