package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * An agent that opts out of "Other" (question_ask allowOther:false) or never asks for it (prompt_ask, whose flag
 * defaulted to false) must still give the owner a free-text Other row, in the inline question panel AND the prompt
 * popup. The whole [App] against the REAL daemon; the agent side is a raw WebSocket, as the CLI/MCP speak it.
 */
@OptIn(ExperimentalTestApi::class)
class AskOtherRealDaemonUiTest {
    private val waitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("agenthq-ui-ask-daemon").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
            workingDir = dir,
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

    private fun agentSocket(port: Int): WebSocket {
        val ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port/ws"), object : WebSocket.Listener {
                override fun onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    w.request(1); return null
                }
            }).get()
        closers += { ws.abort() }
        ws.request(1)
        return ws
    }

    private fun frame(type: String, session: String, data: kotlinx.serialization.json.JsonObject) =
        buildJsonObject { put("type", type); put("data", data.toString()); put("sessionId", session) }.toString()

    private val options: JsonArray = buildJsonArray {
        for (l in listOf("Alpha", "Beta")) add(buildJsonObject { put("label", l); put("description", "d") })
    }

    @Test fun `an ask that opts out of Other still shows the Other row, in the panel and in the popup`() {
        val port = boot()
        val c = Controller(scope)
        runDesktopComposeUiTest(1400, 900) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = waitMs) { c.state.value.connection.state == ConnState.CONNECTED }
            runBlocking { c.spawn("hello") }
            val sessionId = run {
                val end = System.currentTimeMillis() + waitMs
                while (c.state.value.agents.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(25)
                c.state.value.agents.keys.single()
            }
            c.select(sessionId)
            val agent = agentSocket(port)

            // 1. agenthq_ask / `ask --recommend --no-other` (non-blocking): inline question panel.
            agent.sendText(frame("question_ask", sessionId, buildJsonObject {
                put("question", "Which db?"); put("options", options); put("recommended", "Alpha")
                put("reason", "r"); put("allowOther", false)
            }), true).get()
            waitUntil(timeoutMillis = waitMs) { onAllNodes(hasTestTag(QUESTION_OTHER_ROW_TAG)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(QUESTION_CANCEL_TAG).performClick()
            waitUntil(timeoutMillis = waitMs) { onAllNodes(hasTestTag(QUESTION_OTHER_ROW_TAG)).fetchSemanticsNodes().isEmpty() }

            // 2. plain `agenthq ask --option ...` (blocking prompt_ask, no --other): the prompt popup.
            agent.sendText(frame("prompt_ask", sessionId, buildJsonObject {
                put("question", "Which cache?"); put("options", options)
            }), true).get()
            waitUntil(timeoutMillis = waitMs) { onAllNodes(hasTestTag(PROMPT_OTHER_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
