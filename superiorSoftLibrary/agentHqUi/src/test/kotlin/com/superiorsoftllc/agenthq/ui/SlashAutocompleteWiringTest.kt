package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
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
 * CP-4(c): in the REAL window against a REAL in-process daemon, typing "/" in a live agent's box shows the harness's
 * command catalog, typing more narrows it, and picking a command (then one of its values) fills the box. Nothing is
 * sent by picking: a "/" line is a command, never a prompt.
 */
@OptIn(ExperimentalTestApi::class)
class SlashAutocompleteWiringTest {
    /**
     * FLAKE-9 (the FLAKE-6/7/8 pattern): the budget for the waits on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Gates #39 and #49 timed out at the spawn wait (DaemonMain PROMPT after
     * NEW: SessionManager.startNew creates and stores the session before the first agent push) after the fixed 15 s. The connect, the first run's
     * end and the /model swap (SESSION_SWAP) are daemon answers too; the slash-list waits after a keystroke are local
     * recomposition and keep their 10 s. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    /** A real in-process daemon on the stub provider (it declares the standard catalog), a window on it, one spawned agent. */
    private fun withSpawnedAgent(
        modelIds: List<String> = emptyList(),
        body: androidx.compose.ui.test.ComposeUiTest.(Controller) -> Unit,
    ) {
        val dir = Files.createTempDirectory("agenthq-cp4c-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "cp4c", stdoutLines = listOf("""{"type":"step_start","sessionID":"cp4c-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0, modelIds = modelIds)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
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
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.connection.isLive }
                onNodeWithContentDescription("New agent").performClick()
                waitForIdle()
                onNodeWithText("Task for a NEW agent", substring = true).performTextInput("hello")
                onNodeWithContentDescription("Spawn").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.isNotEmpty() }
                // The scripted run ends at once; the box then reads "Message <agent>. Enter to send".
                waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
                body(controller)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    @Test
    fun `typing a slash in an agent box lists the daemon commands and typing more narrows them`() = withSpawnedAgent { controller ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/")
        // VISIBLE: the standard commands are rows of the mounted list.
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/effort ").assertExists() } }
        onNodeWithTag(SLASH_LIST_TAG).assertExists()
        onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/model ").assertExists()
        onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/compact").assertExists()
        assertEquals("stub", controller.state.value.slash?.answer?.provider, "the catalog is the agent's provider's, served by the daemon")

        onNode(hasSetTextAction() and hasText("/", substring = true)).performTextInput("m")
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/effort ").assertDoesNotExist() } }
        onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/model ").assertExists()

        onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/model ").performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText("/model ", substring = true)).assertExists()
    }

    @Test
    fun `picking a command value completes the line in the box`() = withSpawnedAgent { _ ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/effort ")
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/effort high").assertExists() } }
        onNodeWithTag(SLASH_ITEM_TAG_PREFIX + "/effort high").performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText("/effort high")).assertExists()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.box(text: String) = onNode(hasSetTextAction() and hasText(text, substring = true))
    private fun androidx.compose.ui.test.ComposeUiTest.press(boxText: String, key: Key) { box(boxText).performKeyInput { pressKey(key) }; waitForIdle() }
    private fun androidx.compose.ui.test.ComposeUiTest.row(replacement: String) = onNodeWithTag(SLASH_ITEM_TAG_PREFIX + replacement)

    @Test
    fun `arrow keys move the highlight, Enter and Tab pick without sending, Escape closes the list`() = withSpawnedAgent { controller ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/")
        waitUntil(timeoutMillis = 10_000) { exists { row("/effort ").assertExists() } }
        // The first row is highlighted; Down moves it to the second.
        row("/model ").assertIsSelected()
        row("/effort ").assertIsNotSelected()
        press("/", Key.DirectionDown)
        row("/effort ").assertIsSelected()
        row("/model ").assertIsNotSelected()
        // Up moves back.
        press("/", Key.DirectionUp)
        row("/model ").assertIsSelected()
        press("/", Key.DirectionDown)
        // Enter picks the highlighted command exactly as a click does: it fills the box and sends nothing.
        press("/", Key.Enter)
        box("/effort ").assertExists()
        waitUntil(timeoutMillis = 10_000) { exists { row("/effort high").assertExists() } }
        onNodeWithText("> /effort", substring = true).assertDoesNotExist()
        // Tab picks a highlighted value the same way.
        val values = controller.state.value.slash!!.answer.commands.flatMap { it.values }
        press("/effort ", Key.DirectionDown)
        press("/effort ", Key.Tab)
        box("/effort ${values[1]}").assertExists()
        onNodeWithText("> /effort", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Escape closes the slash list and typing again reopens it`() = withSpawnedAgent { _ ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/")
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithTag(SLASH_LIST_TAG).assertExists() } }
        press("/", Key.Escape)
        onNodeWithTag(SLASH_LIST_TAG).assertDoesNotExist()
        box("/").performTextInput("m")
        waitUntil(timeoutMillis = 10_000) { exists { row("/model ").assertExists() } }
    }

    @Test
    fun `the New agent box lists the provider's commands with no session and keys pick there too`() = withSpawnedAgent { controller ->
        // SWAP-AGENT-1 (d67adaac): in a session the composer icon is Switch agent and the sidebar link opens the New
        // agent dialog, so Ctrl+N (App.kt window key handler) is the way into the inline New agent box from a session.
        box("Enter to send").performClick()
        box("Enter to send").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.N) } }
        waitForIdle()
        onNodeWithText("Task for a NEW agent", substring = true).performTextInput("/")
        waitUntil(timeoutMillis = 10_000) { exists { row("/effort ").assertExists() } }
        assertEquals(null, controller.state.value.slash?.sessionId, "spawn mode asks with no session")
        assertEquals("stub", controller.state.value.slash?.answer?.provider)
        press("/", Key.DirectionDown)
        press("/", Key.Enter)
        box("/effort ").assertExists()
        // Nothing was spawned by picking.
        assertEquals(1, controller.state.value.agents.size)
    }

    // CMD-MODEL-1: after "/model " everything typed filters the model list, by any part of the id, ignoring case.
    private val models = listOf("opus", "sonnet", "haiku", "opus[1m]", "sonnet[1m]", "claude-sonnet-5-5", "claude-fable-5-1")

    @Test
    fun `slash model son shows exactly the sonnet entries under the harness header`() = withSpawnedAgent(models) { _ ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/model son")
        waitUntil(timeoutMillis = 10_000) { exists { row("/model claude-sonnet-5-5").assertExists() } }
        row("/model sonnet").assertExists()
        row("/model sonnet[1m]").assertExists()
        for (other in listOf("opus", "haiku", "opus[1m]", "claude-fable-5-1")) row("/model $other").assertDoesNotExist()
        onNodeWithTag(SLASH_NO_MATCH_TAG).assertDoesNotExist()
        onNodeWithTag(SLASH_HEADER_TAG).assertTextEquals("stub models")
    }

    @Test
    fun `slash model zzz shows the single no-match row`() = withSpawnedAgent(models) { _ ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/model zzz")
        waitUntil(timeoutMillis = 10_000) { exists { onNodeWithTag(SLASH_NO_MATCH_TAG).assertExists() } }
        onNodeWithText("No model matches 'zzz'").assertExists()
        for (m in models) row("/model $m").assertDoesNotExist()
    }

    @Test
    fun `Enter on the single match sends the model switch`() = withSpawnedAgent(models) { controller ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/model haik")
        waitUntil(timeoutMillis = 10_000) { exists { row("/model haiku").assertExists() } }
        press("/model haik", Key.Enter)
        // One Enter: the line was completed AND sent, so the daemon switched the session's model.
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.values.single().session.model == "haiku" }
        assertEquals("haiku", controller.state.value.agents.values.single().session.model)
    }
}
