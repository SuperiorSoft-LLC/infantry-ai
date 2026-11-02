package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
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
 * CHAT-PANEL-1: two [ChatPanel]s bound to two different sessions live side by side in ONE window, against a real in-process
 * daemon, and keep their own draft: typing in one never shows in the other. Nothing here goes through [App].
 */
@OptIn(ExperimentalTestApi::class)
class ChatPanelUiTest {
    private fun withDaemon(body: ComposeUiTest.(Controller) -> Unit) {
        val dir = Files.createTempDirectory("agenthq-chatpanel-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "cp", stdoutLines = listOf("""{"type":"step_start","sessionID":"cp-1"}""", """{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
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
                controller.connect("ws://127.0.0.1:$port/ws", null)
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                body(controller)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun ComposeUiTest.draftOf(index: Int): String =
        onAllNodesWithTag(PROMPT_BOX_TAG)[index].fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun two_panels_on_two_sessions_keep_independent_drafts() = withDaemon { controller ->
        runBlocking { controller.spawn("task one"); controller.spawn("task two") }
        waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.size == 2 }
        val ids = controller.state.value.agents.keys.toList()
        setContent {
            AgentHqTheme {
                Row(Modifier.fillMaxHeight()) {
                    ChatPanel(ids[0], controller, Modifier.weight(1f))
                    ChatPanel(ids[1], controller, Modifier.weight(1f))
                }
            }
        }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(PROMPT_BOX_TAG).fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag(PROMPT_BOX_TAG)[0].performTextInput("left draft")
        waitForIdle()
        assertEquals("left draft", draftOf(0))
        assertEquals("", draftOf(1))
        onAllNodesWithTag(PROMPT_BOX_TAG)[1].performTextInput("right draft")
        waitForIdle()
        assertEquals("left draft", draftOf(0))
        assertEquals("right draft", draftOf(1))
    }
}
