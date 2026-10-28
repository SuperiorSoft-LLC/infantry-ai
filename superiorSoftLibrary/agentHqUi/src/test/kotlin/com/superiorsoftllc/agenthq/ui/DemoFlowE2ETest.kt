package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Clock

/**
 * E2E-1: the whole Tuesday demo flow walked in the REAL desktop window against a REAL in-process
 * daemon. Nothing is mocked above the provider: the window composes [App], the daemon runs its real
 * Ktor/WebSocket/SQLite machinery, and the only scripted part is the provider's stdout (a stub that
 * speaks the real normaliser vocabulary, including contract 1.23.0 `reasoning`). Every step is a
 * click or typing on a visible control; no SendKeys, no OS-level input.
 *
 * Stub script, in order: a session id, one reasoning line (THINK-1), one markdown reply (heading +
 * bold + fenced code), then a clean finish -- so the window must show "Thinking" while the reply is
 * due, a collapsed reasoning block that expands, the formatted reply with its avatar, and the status
 * cleared once the reply starts.
 */
@OptIn(ExperimentalTestApi::class)
class DemoFlowE2ETest {

    /** The reasoning text the window must show only after the "Thinking" block is clicked. */
    private val reasoningText = "THINK-1: weigh the two designs before answering."
    private val reasoningFragment = "weigh the two designs"

    /** The markdown reply: heading, bold span, fenced code block. */
    private val replyJson =
        """{"type":"text","text":"# Demo reply\n\nHere is the **bold** answer.\n\n```kotlin\nval answer = 42\n```"}"""
    private val boldParagraph = "Here is the bold answer."
    private val codeBody = "val answer = 42"

    /**
     * FLAKE-3 (the FLAKE-1 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait (60 s,
     * AGENTHQ_TEST_WAIT_MS overrides it). A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    /** FLAKE-3: opened once the test has seen "Thinking" and the reasoning block; until then the reply line is held. */
    private val replyGate = CountDownLatch(1)

    /**
     * FLAKE-3: the stub's transport, except that [heldLine] waits for [gate]. "Thinking" lasts only until the first
     * reply token, and the timed stream sent that token ~4.5 s after the start whether or not the test had looked: a
     * test thread starved past that moment (a build running beside the suite) then polled for a status that had come
     * and gone, and no budget was long enough. Holding the reply until the test opens the gate makes the order the
     * test asserts the only order possible. The hold is bounded, so a test that fails first never parks the run.
     */
    private class GatedReply(
        private val inner: ProcessRunner,
        private val heldLine: String,
        private val gate: CountDownLatch,
        private val maxHoldMs: Long,
    ) : ProcessRunner by inner {
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            inner.start(spec, object : ProcessListener by listener {
                override fun onStdout(line: String) {
                    if (line == heldLine) gate.await(maxHoldMs, TimeUnit.MILLISECONDS)
                    listener.onStdout(line)
                }
            })
    }

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("agenthq-e2e-demo").toFile()
        // A positive delay so the "Thinking" window (turn -> first reply token) is observable in a
        // real window: the daemon delivers one provider line at a time, as a streaming CLI does.
        val script = StubScript(
            name = "demo",
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"demo-session-1"}""",
                """{"type":"reasoning","text":"$reasoningText"}""",
                replyJson,
                """{"type":"step_finish"}""",
            ),
            exitCode = 0,
        )
        val provider = StubProvider(script, delayMs = 1_500)
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider,
            runner = GatedReply(provider.transport()!!, replyJson, replyGate, daemonWaitMs),
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            // BUG-GATE19-1: a plain temp dir, not build()'s default user.dir (this git worktree): BUG-WT-1's handOut cuts a
            // worktree from a git project folder and refuses useWorktree=false over one, so the ticket never started.
            workingDir = File(dir, "workdir").apply { mkdirs() },
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { replyGate.countDown(); daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    @Test
    fun demoFlowPickProfileStartTicketSeeThinkingReasoningAndFormattedReply() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                // FLAKE-1: the daemon answers one connection's requests in order, so the first save below queued
                // behind the window's on-connect burst. Connected means that burst is answered: tool_approval and
                // provider_states answer the last of its two request groups.
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.roles.isNotEmpty() && s.providers.isNotEmpty() &&
                        s.toolApproval != null && s.providerStates.isNotEmpty()
                }

                // 1. Profiles (Ctrl+9): pick a role, a provider and a model; save; the profile appears.
                onNodeWithText("Profiles", substring = true).performClick()
                onNodeWithText("+ New profile").performClick()
                onNodeWithText("backend").performClick()          // role chip
                onNodeWithTag("model-harness-stub").performClick()   // harness chip (the only provider)
                onNodeWithText("Name").performTextInput("Demo Agent")
                // PICKER-ALL-2: the shared ExecutorPicker; the stub lists no models, so its free-text field takes the id.
                onNodeWithTag(PROFILE_EXECUTOR_TAGS.freeText).performScrollTo().performTextInput("stub-model")
                onNodeWithText("Save profile").performScrollTo().performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.profiles.size == 1 }
                onNodeWithText("Demo Agent").assertExists()

                // 2. Board (Ctrl+8): create a ticket assigned to that profile, then start it.
                onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick() // M2-8: the sidebar button opens the board modal
                onNodeWithText("+ New ticket").performClick()
                onNodeWithText("Title").performTextInput("Demo ticket")
                onNode(hasText("Demo Agent") and hasAnyAncestor(hasTestTag(BOARD_MODAL_TAG))).performClick() // assignee = the profile just saved (the Profiles pane still shows it under the modal)
                onNodeWithText("Create ticket").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.size == 1 }

                // 3. Start the ticket: a session appears and is selected (autoSelect on the session frame).
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.tickets.single().sessionId != null }
                val sessionId = controller.state.value.tickets.single().sessionId!!
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.selectedId == sessionId }

                // Open the chat: the ticket card's "Agent" control selects it and switches to Output.
                // The status row pulses forever, so from here the clock is manual (as ChatStatusUiTest):
                // the frames the auto-clock would have run must be advanced by hand before we read the tree.
                mainClock.autoAdvance = false
                repeat(5) { mainClock.advanceTimeByFrame() }
                onNodeWithText("Agent").performClick()
                repeat(5) { mainClock.advanceTimeByFrame() }

                // 4. Before the first reply token: the "Thinking" status is rendered (CHATSTATUS-5).
                pumpUntil { controller.state.value.selected?.chatStatus(Clock.System.now().toEpochMilliseconds()) != null }
                onNodeWithTag(CHAT_STATUS_TAG).assertExists()
                onNodeWithText("Thinking...").assertExists()

                // 5. The reasoning arrives and shows as ONE collapsed "Thinking" block (THINK-2).
                pumpUntil { controller.state.value.selected?.output?.any { it.kind == LineKind.REASONING } == true }
                repeat(2) { mainClock.advanceTimeByFrame() }
                onNodeWithTag(REASONING_HEADER_TAG).assertExists()
                onNodeWithText("Thinking (1)").assertExists()
                replyGate.countDown() // FLAKE-3: steps 4 and 5 are asserted; let the reply through.

                // 6. The reply arrives and the run ends (1c24fdc4: the indicator follows the session state): "Thinking" clears and the reply is formatted markdown.
                pumpUntil { controller.state.value.selected?.output?.any { it.kind == LineKind.TEXT } == true }
                pumpUntil { controller.state.value.selected?.chatStatus(Clock.System.now().toEpochMilliseconds()) == null }
                repeat(2) { mainClock.advanceTimeByFrame() }
                onNodeWithTag(CHAT_STATUS_TAG).assertDoesNotExist()

                // Formatted: the bold span is drawn without its markers, and the fence is a code block.
                onNodeWithText(boldParagraph, substring = true).assertExists()
                onNodeWithText("**", substring = true).assertDoesNotExist()
                onNodeWithText(codeBody).assertExists()
                onNodeWithText("kotlin").assertExists()
                // One avatar on the human's turn line and one on the agent's reply line. (M2-1: the list,
                // the top strip and the header now draw the same AgentAvatar, so count the chat's own.)
                onAllNodes(hasTestTag(AVATAR_TAG) and hasAnyAncestor(hasTestTag(SPEAKER_ROW_TAG))).assertCountEquals(2)

                // 7. The reasoning block was COLLAPSED -- its text is nowhere on screen, now that the
                //    activity summaries show the reply instead of the reasoning. Clicking reveals it.
                onNodeWithText(reasoningFragment, substring = true).assertDoesNotExist()
                onNodeWithTag(REASONING_HEADER_TAG).performClick()
                repeat(3) { mainClock.advanceTimeByFrame() }
                onNodeWithText(reasoningFragment, substring = true).assertExists()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    /**
     * Advance the manual clock one frame at a time until [condition] holds. Needed because the chat
     * status dot is an infinite pulse: with [androidx.compose.ui.test.MainTestClock.autoAdvance] off,
     * `waitUntil` would never move the clock, and any `waitForIdle` would never settle.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.pumpUntil(
        timeoutMillis: Long = daemonWaitMs,
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
