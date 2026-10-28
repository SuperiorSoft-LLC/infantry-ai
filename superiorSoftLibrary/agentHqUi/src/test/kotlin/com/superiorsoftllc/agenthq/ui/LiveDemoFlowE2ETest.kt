package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunnerFactory
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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * E2E-2: OPT-IN proof that a REAL model reply reaches the window. Same demo flow as E2E-1
 * ([DemoFlowE2ETest]) -- Profiles -> Board -> Start -> Thinking -> a formatted reply -- but against
 * the REAL OpenCode CLI on a real model, driven through the window exactly like E2E-1.
 *
 * This is the one thing E2E-1 cannot show: the stub speaks the normaliser vocabulary, so it proves the
 * WINDOW works, never that a real `opencode run` reaches it. Turn the flow on a real model and the
 * whole chain (profile -> in-process daemon -> `opencode run` -> stdout -> normaliser -> WebSocket ->
 * reducer -> Compose) is exercised end to end.
 *
 * Skipped unless `AGENTHQ_LIVE_E2E` is set: it spends real model calls, so the default suite stays
 * green and OFFLINE. Model from `AGENTHQ_LIVE_E2E_MODEL`, else `opencode-go/gpt-6-luna`.
 *
 * The assertions are deliberately RELAXED (a live model is non-deterministic): the provider and model
 * picked, Thinking appearing, a NON-EMPTY reply within 180 s, Thinking clearing, the run reaching
 * FINISHED, and the durable record: sessions.json holds a resumable SessionRecord (session id, provider/model,
 * ticketId of the created ticket, exit 0).
 * The collapsed reasoning block is asserted only IF the model emitted a reasoning line, and the run
 * says so in its log otherwise -- reasoning on a given model is not guaranteed.
 */
@OptIn(ExperimentalTestApi::class)
class LiveDemoFlowE2ETest {

    @Test
    fun liveDemoFlowOnLuna() {
        if (System.getenv("AGENTHQ_LIVE_E2E")?.trim() != "1") {
            println("[live-e2e] skipped: set AGENTHQ_LIVE_E2E=1 to run this against a real model (real OpenCode)")
            return
        }
        // runDesktopComposeUiTest wraps runTest, whose default 60 s wall timeout would abort the 180 s reply wait.
        System.setProperty("kotlinx.coroutines.test.default_timeout", "10m")
        val model = System.getenv("AGENTHQ_LIVE_E2E_MODEL")?.takeIf { it.isNotBlank() } ?: "opencode-go/gpt-6-luna"

        val dir = Files.createTempDirectory("agenthq-live-e2e-demo").toFile()
        val sessionStore = JsonSessionStore(File(dir, "sessions.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The session's working directory is the JVM's user.dir when the manager is built: point it at
        // the empty temp dir so the agent answers the ticket instead of reading this repository.
        val savedUserDir = System.getProperty("user.dir")
        val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
        val daemon = try {
            System.setProperty("user.dir", dir.absolutePath)
            AgentHqDaemon.build(
                memory = FixedMemory,
                port = 0, host = "127.0.0.1",
                provider = ModelPinned(model), runner = RunnerFactory.fromFlag(),
                sessionStore = sessionStore,
                mailStore = JsonMailStore(File(dir, "mailbox.json")),
                store = store,
                mailBaseUrl = "http://127.0.0.1:1", token = null, repo = null, additionalProviders = emptyList(),
                storyStore = JsonStoryStore(File(dir, "stories.json")),
                roles = RoleService(File(dir, "roles")),
                // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
                modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
            )
        } finally {
            System.setProperty("user.dir", savedUserDir)
        }

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
            val startedAt = System.currentTimeMillis()
            var sessionId: String? = null
            var replyMs = -1L
            var replyFirstLine = ""
            var ticketId: String? = null

            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive &&
                        controller.state.value.roles.isNotEmpty() &&
                        controller.state.value.providers.isNotEmpty()
                }

                // 1. Profiles (Ctrl+9): a role, the REAL provider (opencode, the only one) and the model.
                onNodeWithText("Profiles", substring = true).performClick()
                onNodeWithText("+ New profile").performClick()
                onNodeWithText("backend").performClick()
                onNodeWithText("opencode (default)").performClick()
                onNodeWithText("Name").performTextInput("Luna Agent")
                pickModel(model)
                onNodeWithText("Save profile").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.profiles.size == 1 }
                val profile = controller.state.value.profiles.single()
                assertTrue(profile.provider == "opencode", "profile must use the OpenCode provider; got ${profile.provider}")
                assertTrue(profile.model == model, "profile must use model $model; got ${profile.model}")
                onNodeWithText("Luna Agent").assertExists()

                // 2. Board (Ctrl+8): a ticket assigned to that profile.
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick() // M2-8: the sidebar button opens the board modal
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("Demo ticket")
                onNode(hasText("Luna Agent") and hasAnyAncestor(hasTestTag(BOARD_MODAL_TAG))).performClick() // the Profiles pane still shows it under the modal
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.size == 1 }

                // 3. Start the ticket: a session appears and is selected (autoSelect on the session frame).
                // The status row pulses forever, so from here the clock is manual (as E2E-1 / ChatStatusUiTest):
                // the frames the auto-clock would have run must be advanced by hand before we read the tree.
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.tickets.single().sessionId != null }
                sessionId = controller.state.value.tickets.single().sessionId!!
                ticketId = controller.state.value.tickets.single().id
                waitUntil(timeoutMillis = 10_000) { controller.state.value.selectedId == sessionId }
                mainClock.autoAdvance = false
                repeat(5) { mainClock.advanceTimeByFrame() }
                onNodeWithText("Agent").performClick()
                repeat(5) { mainClock.advanceTimeByFrame() }

                // 4. Before any answer: the "Thinking" status is rendered (CHATSTATUS-5).
                pumpUntil(60_000) { controller.state.value.selected?.chatStatus(Clock.System.now().toEpochMilliseconds()) != null }
                onNodeWithTag(CHAT_STATUS_TAG).assertExists()
                onNodeWithText("Thinking...").assertExists()

                // 5. A NON-EMPTY reply from the real model, within 180 s (quota exhaustion presents as a silent hang).
                pumpUntil(180_000) { controller.state.value.selected?.output?.any { it.kind == LineKind.TEXT } == true }
                replyMs = System.currentTimeMillis() - startedAt
                val reply = controller.state.value.selected!!.output
                    .filter { it.kind == LineKind.TEXT }
                    .joinToString("\n") { it.text }
                assertTrue(reply.isNotBlank(), "the live model must return non-empty text; got '$reply'")
                replyFirstLine = reply.lineSequence().first().take(200)
                println("[live-e2e] model=$model session=$sessionId replyMs=$replyMs firstLine=$replyFirstLine")

                // 6. The reply arrives and the run ends: Thinking clears (it follows the session state, 1c24fdc4), its status node leaves the tree.
                pumpUntil(60_000) { controller.state.value.selected?.chatStatus(Clock.System.now().toEpochMilliseconds()) == null }
                repeat(2) { mainClock.advanceTimeByFrame() }
                onNodeWithTag(CHAT_STATUS_TAG).assertDoesNotExist()

                // 7. Reasoning: NO FAILURE when the model emits none (a live model may not reason at all).
                val reasoningLines = controller.state.value.selected!!.output.count { it.kind == LineKind.REASONING }
                if (reasoningLines > 0) {
                    println("[live-e2e] reasoning lines=$reasoningLines (collapsed block)")
                    onNodeWithTag(REASONING_HEADER_TAG).assertExists()
                    onNodeWithTag(REASONING_HEADER_TAG).performClick()
                    repeat(3) { mainClock.advanceTimeByFrame() }
                } else {
                    println("[live-e2e] no reasoning emitted by model")
                }

                // 8. The run ends and the session reaches FINISHED.
                pumpUntil(180_000) { controller.state.value.selected?.output?.any { it.kind == LineKind.FINISHED } == true }
            }

            // Durable evidence. sessions.json carries session lifecycle/resume metadata (not transcript
            // text), and the runs table carries the run's exit code and stdout volume. Together with the
            // reply observed above in the window, they prove the successful reply-producing run finished.
            val persisted = pollUntil(30_000) { sessionStore.load().firstOrNull { it.id == sessionId }?.takeIf { it.resumable } }
                ?: error("sessions.json must hold a resumable record for the session; got ${sessionStore.load()}")
            assertTrue(persisted.provider == "opencode", "sessions.json must persist the OpenCode provider; got ${persisted.provider}")
            assertTrue(persisted.model == model, "sessions.json must persist model $model; got ${persisted.model}")
            assertTrue(persisted.lastExitCode == 0, "sessions.json must persist a successful exit; got ${persisted.lastExitCode}")
            // The ticket that was started is linked on the durable record (revised E2E-2 criterion).
            assertTrue(persisted.ticketId == ticketId, "sessions.json must link the created ticket $ticketId; got ${persisted.ticketId}")
            println("[live-e2e] durable: state=${persisted.state} psid=${persisted.providerSessionId} runCount=${persisted.runCount} exit=${persisted.lastExitCode} ticketId=${persisted.ticketId} replyMs=$replyMs firstLine=$replyFirstLine")
        } finally {
            scope.cancel()
            daemon.server.stop(0, 500)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    /**
     * UI-PROV-1: pick [model] in a profile form's model control. A REAL `opencode models` catalogue spans
     * many sub-providers, so the control is the two-level picker: choose the model's own sub-provider
     * first, then the model. A provider with no catalogue (or a single one) keeps the plain text field,
     * which is what the offline suites see, so both shapes are handled here rather than assuming one.
     */
    private fun ComposeUiTest.pickModel(model: String) {
        // PICKER-ALL-2: the shared ExecutorPicker. No catalogue = its free-text field; else search, then pick the row.
        if (onAllNodesWithTag(MODEL_PICKER_TAG).fetchSemanticsNodes().isEmpty()) {
            onNodeWithTag(PROFILE_EXECUTOR_TAGS.freeText).performScrollTo().performTextInput(model)
            return
        }
        onNodeWithTag(MODEL_PICKER_TAG).performScrollTo().performTextInput(model)
        onNodeWithTag(MODEL_OPTION_TAG_PREFIX + model).performScrollTo().performClick()
    }

    /** OpenCode with this test's model as the default, so a profile that names none still uses Luna. */
    private class ModelPinned(private val model: String) : AgentProvider by OpenCodeProvider() {
        private val inner = OpenCodeProvider()
        // An EMPTY config dir keeps the user's global opencode config (MCP servers, plugins, instructions) out of
        // the child, so the run is a plain model call. Credentials live in the data dir, which is left alone.
        private val isolatedConfig = Files.createTempDirectory("agenthq-live-e2e-xdgcfg").toFile().apply { deleteOnExit() }
        override fun spec(request: RunRequest): ProcessSpec {
            val base = inner.spec(request.copy(model = request.model ?: model))
            return base.copy(env = base.env + mapOf("XDG_CONFIG_HOME" to isolatedConfig.absolutePath))
        }
        override fun defaultModel(): String = model
    }

    private fun <T : Any> pollUntil(timeoutMs: Long, cond: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            cond()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    /**
     * Advance the manual clock one frame at a time until [condition] holds. Needed because the chat
     * status dot is an infinite pulse: with [androidx.compose.ui.test.MainTestClock.autoAdvance] off,
     * `waitUntil` would never move the clock, and any `waitForIdle` would never settle.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.pumpUntil(
        timeoutMillis: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            mainClock.advanceTimeByFrame()
            if (System.nanoTime() > deadline) throw AssertionError("condition not met within ${timeoutMillis}ms")
            Thread.sleep(5)
        }
        mainClock.advanceTimeByFrame()
    }
}
