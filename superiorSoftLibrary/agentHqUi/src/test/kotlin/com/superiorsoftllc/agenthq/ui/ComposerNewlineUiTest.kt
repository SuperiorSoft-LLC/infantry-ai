package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * COMPOSER-NEWLINE-1: in the REAL window against a REAL in-process daemon, Shift+Enter puts a new line at the caret
 * in both the New agent box and a session's composer, Enter alone still sends, and a multi-line prompt reaches the
 * daemon with its newline intact.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerNewlineUiTest {
    /** The stub provider, recording every prompt the daemon hands it (spawn AND follow-up turns). */
    private class RecordingProvider(private val inner: StubProvider) : AgentProvider by inner {
        val prompts = CopyOnWriteArrayList<String>()
        override fun spec(request: RunRequest): ProcessSpec { prompts += request.prompt; return inner.spec(request) }
    }

    private fun withApp(body: ComposeUiTest.(Controller, RecordingProvider) -> Unit) {
        val dir = Files.createTempDirectory("agenthq-newline-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "nl", stdoutLines = listOf("""{"type":"step_start","sessionID":"nl-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val provider = RecordingProvider(stub)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
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
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                body(controller, provider)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    private fun ComposeUiTest.box(): SemanticsNodeInteraction = onNodeWithTag(PROMPT_BOX_TAG)

    /** What is typed in the box: its editable text only, never the placeholder the field merges into its semantics. */
    private fun ComposeUiTest.boxText(): String = box().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun SemanticsNodeInteraction.shiftEnter() = performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }

    private fun ComposeUiTest.openNewAgentBox() {
        onNodeWithContentDescription("New agent").performClick()
        waitForIdle()
        onNodeWithText("Task for a NEW agent", substring = true).assertExists()
    }

    /** Spawns one agent from the New agent box with [task] and waits for its composer. */
    private fun ComposeUiTest.spawn(controller: Controller, task: String) {
        openNewAgentBox()
        box().performTextInput(task)
        onNodeWithContentDescription("Spawn").performClick()
        waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.isNotEmpty() }
        waitUntil(timeoutMillis = 15_000) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
        waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.values.single().entries.any { it.kind == LineKind.USER } }
    }

    private fun said(controller: Controller): List<String> =
        controller.state.value.agents.values.single().entries.filter { it.kind == LineKind.USER }.map { it.text }

    @Test
    fun `Shift+Enter in the New agent box adds a line at the caret and spawns nothing`() = withApp { controller, _ ->
        openNewAgentBox()
        box().performTextInput("a")
        box().shiftEnter()
        box().performTextInput("b")
        waitForIdle()
        assertEquals("a\nb", boxText())
        assertTrue(controller.state.value.agents.isEmpty(), "Shift+Enter must not spawn")
    }

    @Test
    fun `Shift+Enter in a session composer adds a line at the caret and sends nothing`() = withApp { controller, provider ->
        spawn(controller, "hello")
        val before = provider.prompts.size
        box().performTextInput("a")
        box().shiftEnter()
        box().performTextInput("b")
        waitForIdle()
        assertEquals("a\nb", boxText())
        assertEquals(listOf("hello"), said(controller), "Shift+Enter must not send")
        assertEquals(before, provider.prompts.size)
    }

    @Test
    fun `Enter alone still sends a single-line prompt`() = withApp { controller, provider ->
        spawn(controller, "hello")
        box().performTextInput("one line")
        box().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { "one line" in provider.prompts }
        waitUntil(timeoutMillis = 10_000) { said(controller) == listOf("hello", "one line") }
        assertEquals("", boxText())
    }

    @Test
    fun `a multi-line prompt reaches the daemon and the transcript with its newline`() = withApp { controller, provider ->
        spawn(controller, "hello")
        box().performTextInput("first")
        box().shiftEnter()
        box().performTextInput("second")
        box().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { "first\nsecond" in provider.prompts }
        waitUntil(timeoutMillis = 10_000) { said(controller) == listOf("hello", "first\nsecond") }
        // The transcript renders both lines of the one message.
        onNodeWithText("first\nsecond", substring = true).assertExists()
    }
}
