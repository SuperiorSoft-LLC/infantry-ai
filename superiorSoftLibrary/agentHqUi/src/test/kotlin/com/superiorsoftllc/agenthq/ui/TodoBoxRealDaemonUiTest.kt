package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertTrue

/**
 * TODO-BOX-1 / TODO-PROGRESS-1 against a REAL in-process daemon (temp data dir, ephemeral port): a scripted run writes a
 * claude `TodoWrite` list three times; the chat shows the to-do toggle, its panel lists the items, and the session progress the
 * daemon pushes moves as items are checked (count 1 of 3, then 2 of 3, origin `todo`).
 */
@OptIn(ExperimentalTestApi::class)
class TodoBoxRealDaemonUiTest {
    private fun todoWrite(vararg items: Pair<String, String>): String =
        """{"type":"tool_use","name":"TodoWrite","input":{"todos":[""" +
            items.joinToString(",") { (t, s) -> """{"content":"$t","status":"$s","activeForm":"Doing $t"}""" } + "]}}"

    @Test
    fun the_list_shows_in_the_toggle_box_and_drives_the_progress_bar() {
        val dir = Files.createTempDirectory("agenthq-todobox-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val script = StubScript(
            name = "todo",
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"todo-1"}""",
                todoWrite("plan" to "in_progress", "build" to "pending", "test" to "pending"),
                todoWrite("plan" to "completed", "build" to "in_progress", "test" to "pending"),
                todoWrite("plan" to "completed", "build" to "completed", "test" to "in_progress"),
                """{"type":"text","text":"working"}""",
                """{"type":"step_finish"}""",
            ),
            exitCode = 0,
        )
        // Slow enough that each list is a separate push the test can observe.
        val stub = StubProvider(script, delayMs = 700)
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
            assertTrue(port != 8797)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1400, 900) {
                controller.connect("ws://127.0.0.1:$port/ws", null)
                waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
                runBlocking { controller.spawn("do the task") }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.agents.size == 1 }
                val id = controller.state.value.agents.keys.single()
                setContent { AgentHqTheme { ChatPanel(id, controller, Modifier.fillMaxSize()) } }
                fun progress() = controller.state.value.agents[id]?.session?.progress

                // First list: the toggle appears, the bar reads 0 of 3 on the first item.
                waitUntil(timeoutMillis = 15_000) { progress()?.total == 3L }
                assertEquals("todo", progress()?.origin)
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(TODO_BUTTON_TAG).fetchSemanticsNodes().isNotEmpty() }
                // An item is checked: the bar moves (1 of 3), then 2 of 3.
                waitUntil(timeoutMillis = 15_000) { progress()?.done == 1L }
                assertEquals("build", progress()?.step)
                waitUntil(timeoutMillis = 15_000) { progress()?.done == 2L }
                assertEquals("count", progress()?.mode)
                assertEquals("test", progress()?.step)

                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("☑ 2/3").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag(TODO_BUTTON_TAG).performClick()
                onNodeWithTag(TODO_PANEL_TAG).assertExists()
                assertEquals(3, onAllNodesWithTag(TODO_ITEM_TAG).fetchSemanticsNodes().size)
                onNodeWithText("Doing test").assertExists()
                onNodeWithTag(TODO_PANEL_CLOSE_TAG).performClick()
                onNodeWithTag(TODO_PANEL_TAG).assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
