package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunnerFactory
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.net.CommandResult
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * OPT-IN live evidence for "talk to an agent": the REAL daemon, the REAL OpenCode CLI and the UI's own
 * [Controller], holding a two-turn conversation in one session. Turn two can only be answered from turn
 * one's context, which proves the follow-up continued the provider's conversation (`--session <id>`)
 * rather than starting a fresh one-shot run.
 *
 * Skipped unless `AGENTHQ_LIVE_OPENCODE` is set, because it spends real model calls (a free model by
 * default: `AGENTHQ_LIVE_OPENCODE_MODEL`, else `opencode/step-5-preview-free`). A free model whose quota is
 * exhausted presents as a silent hang, so each turn has a hard deadline and the failure says which turn.
 */
class LiveOpenCodeChatTest {
    @Test fun `a real OpenCode conversation keeps its context from one turn to the next`() {
        if (System.getenv("AGENTHQ_LIVE_OPENCODE").isNullOrBlank()) return
        val model = System.getenv("AGENTHQ_LIVE_OPENCODE_MODEL")?.takeIf { it.isNotBlank() } ?: "opencode/step-5-preview-free"
        val dir = Files.createTempDirectory("agenthq-live-chat").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The session's working directory is the JVM's user.dir when the manager is built: point it at the
        // empty temp dir so the agent answers the question instead of reading this repository.
        val userDir = System.getProperty("user.dir")
        System.setProperty("user.dir", dir.absolutePath)
        val daemon = try {
            AgentHqDaemon.build(
                memory = FixedMemory,
                port = 0, provider = ModelPinned(model), runner = RunnerFactory.fromFlag(),
                sessionStore = JsonSessionStore(File(dir, "sessions.json")),
                mailStore = JsonMailStore(File(dir, "mailbox.json")),
                store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
                storyStore = JsonStoryStore(File(dir, "stories.json")),
                mailBaseUrl = "http://127.0.0.1:1", token = null, repo = null, additionalProviders = emptyList(),
                // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
                modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
            )
        } finally {
            System.setProperty("user.dir", userDir)
        }
        daemon.server.start(wait = false)
        try {
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            val c = Controller(scope)
            c.connect("ws://127.0.0.1:$port/ws", null)
            until("connected", c, 20_000) { it.connection.isLive }

            assertIs<CommandResult.Sent>(runBlocking {
                c.spawn("Remember this code word for later: PINEAPPLE-42. Reply with only the word OK.")
            })
            val first = until("turn 1 answered by $model", c, 240_000) { s -> s.selected?.output?.any { it.kind == LineKind.FINISHED } == true }
            val id = first.selectedId!!
            until("turn 1 at rest with a provider session id", c, 30_000) { it.agents[id]?.session?.resumable == true }

            assertIs<CommandResult.Sent>(runBlocking { c.prompt(id, "What was the code word I asked you to remember? Reply with only the code word.") })
            val second = until("turn 2 answered by $model", c, 240_000) { s -> s.agents[id]!!.output.count { it.kind == LineKind.FINISHED } == 2 }

            val lines = second.agents.getValue(id).output
            println("[live-chat] model=$model session=$id psid=${second.agents.getValue(id).session.providerSessionId}")
            lines.forEach { println("[live-chat] ${it.kind}: ${it.text.replace('\n', ' ').take(200)}") }
            val afterFollowUp = lines.dropWhile { it.kind != LineKind.USER || !it.text.startsWith("What was the code word") }
            val answer = afterFollowUp.filter { it.kind == LineKind.TEXT }.joinToString(" ") { it.text }
            assertTrue(answer.contains("PINEAPPLE", ignoreCase = true), "turn 2 must recall turn 1's code word; got '$answer'")
            assertTrue(second.agents.size == 1 && second.agents.getValue(id).session.resumeCount >= 1, "one session, resumed")
        } finally {
            scope.cancel()
            daemon.server.stop(0, 500)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    /** OpenCode with this test's model as the default, so the spawn needs no model picker. */
    private class ModelPinned(private val model: String) : AgentProvider by OpenCodeProvider() {
        private val inner = OpenCodeProvider()
        override fun spec(request: RunRequest): ProcessSpec = inner.spec(request.copy(model = request.model ?: model))
        override fun defaultModel(): String = model
    }

    private fun until(what: String, c: Controller, timeoutMs: Long, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(100)
        }
        val s = c.state.value
        error("not met within ${timeoutMs}ms: $what; agents=${s.agents.values.map { it.session.state to it.output.map { l -> l.kind } }} notices=${s.notices.map { it.text }}")
    }
}
