package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.HarnessSwitch
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.isBusy
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SWAP-AGENT-1: `/harness <name>` and the header chip now open the ONE Switch agent picker (the harness-only popover is
 * retired); Apply sends the same swap. These keep CMD-HARNESS-1 / UX-HARNESS-2's behavioural assertions.
 * CMD-HARNESS-1: in the REAL window against a REAL in-process daemon serving two harnesses (`stub`, the default, and
 * `second`), a saved agent's harness is switched by `/harness` and by the header chip's ExecutorPicker, both through
 * the daemon's existing `agent_swap` (session auto). HARNESS-CARRY-1: the agent keeps its session, which moves onto the
 * new harness with its conversation, so the chat stays put and `/model` lists the new harness's models; a refusal is
 * shown with the daemon's own reason.
 */
@OptIn(ExperimentalTestApi::class)
class HarnessSwitchUiTest {
    /**
     * FLAKE-6 (the FLAKE-1/FLAKE-4 pattern): the budget for every wait on a daemon round trip here, agentHq's TestWait
     * (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Under suite load the fixed 10-20 s waits timed out on a swap the daemon
     * answered late (its session-store write precedes the status frame). A passing run waits milliseconds; only a
     * missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private val script = StubScript(name = "harness", stdoutLines = listOf("""{"type":"step_start","sessionID":"h-1"}""", """{"type":"step_finish"}"""), exitCode = 0)

    /** The second harness: the stub's catalog, its own id and its own models, and a default model it names. */
    private inner class Second : AgentProvider by StubProvider(script, delayMs = 0, modelIds = listOf("second-alpha", "second-beta")) {
        override val id = "second"
        override fun defaultModel(): String = "second-alpha"
    }

    /** UX-HARNESS-2: a harness that names NO default model, so `/harness third` cannot pick one by itself. */
    private inner class Third : AgentProvider by StubProvider(script, delayMs = 0, modelIds = listOf("third-x", "third-y")) {
        override val id = "third"
    }

    private class Rig(val controller: Controller, val agentId: String, val sessionId: String)

    private fun withAgent(body: ComposeUiTest.(Rig) -> Unit) = withSession(saved = true, body)

    /** UX-HARNESS-2: [saved] false starts an ad-hoc session (no agent profile), as a quick chat does. */
    private fun withSession(saved: Boolean, body: ComposeUiTest.(Rig) -> Unit) {
        val dir = Files.createTempDirectory("agenthq-cmd-harness-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(script, delayMs = 0, modelIds = listOf("stub-one", "stub-two"))
        val daemon = AgentHqDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            additionalProviders = listOf(Second(), Third()),
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
            val controller = Controller(scope, agentConfirmMs = daemonWaitMs)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.roles.isNotEmpty() && s.providers.any { it.id == "second" } && s.providers.any { it.id == "third" }
                }
                assertTrue(runBlocking { controller.createProject("Alpha") } is CommandResult.Sent)
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId != null }
                val agentId = if (saved) {
                    val role = controller.state.value.roles.first().id
                    assertTrue(runBlocking { controller.addAgent(role, null, "first task", name = "Ada") } is CommandResult.Sent)
                    waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.size == 1 }
                    controller.state.value.profiles.first { it.name == "Ada" }.id!!
                } else {
                    assertTrue(runBlocking { controller.spawn("first task") } is CommandResult.Sent)
                    waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.size == 1 }
                    ""
                }
                val sid = controller.state.value.agents.keys.single()
                controller.select(sid)
                // BUG-FLAKE-HSWITCH: hand over only once the first turn has come to rest, as SwitchAgentUiTest does. A saved
                // agent's session is bound at `new` (IDLE, runCount 0), and that never-run session already reads "Enter to send"
                // before its first turn starts: a body acting then switches mid-turn, and is refused (SWITCH_WAIT_FOR_TURN) or
                // answered session `none`.
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents[sid]?.let { it.session.runCount >= 1 && !isBusy(it) } == true }
                // The scripted run has ended; the box then reads "Message <agent>. Enter to send".
                waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
                body(Rig(controller, agentId, sid))
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    private fun ComposeUiTest.row(replacement: String) = onNodeWithTag(SLASH_ITEM_TAG_PREFIX + replacement)

    private fun ComposeUiTest.box(text: String) = onNode(hasSetTextAction() and hasText(text, substring = true))

    /** The daemon confirmed the swap: the agent's profile is on [harness], and its session (still selected) moved there. */
    private fun ComposeUiTest.awaitSwitched(rig: Rig, harness: String) {
        runCatching { waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agentSwaps[rig.agentId]?.agent?.provider == harness } }
            .onFailure { val s = rig.controller.state.value; throw AssertionError("no swap to $harness; notices=${s.notices.map { it.text }} error=${s.agentError}", it) }
        waitUntil(timeoutMillis = daemonWaitMs) {
            val s = rig.controller.state.value
            s.selectedId == rig.sessionId && s.agents[s.selectedId]?.session?.provider == harness
        }
    }

    @Test
    fun `slash harness lists the harnesses and picking one sends agent_swap with that provider and session auto`() = withAgent { rig ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/harness ")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { row("/harness second").assertExists() } }
        row("/harness stub").assertExists()
        onNodeWithTag(SLASH_HEADER_TAG).assertTextEquals(HarnessSwitch.HEADER)
        // Type-to-filter, as /model: "sec" leaves only the second harness.
        box("/harness ").performTextInput("sec")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { row("/harness stub").assertDoesNotExist() } }
        row("/harness second").assertExists()
        // One match left: Enter picks it and sends the line (CMD-MODEL-1's rule); SWAP-AGENT-1: that opens the picker
        // with the harness pre-selected, and nothing is switched until Apply.
        box("/harness sec").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
        assertTrue(rig.controller.state.value.agentSwaps.isEmpty(), "/harness alone switches nothing")
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Model: second-alpha (default)")
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).performClick()

        awaitSwitched(rig, "second")
        val swap = rig.controller.state.value.agentSwaps.getValue(rig.agentId)
        assertEquals("stub", swap.from.provider, "the swap left the harness the agent was on")
        // HARNESS-CARRY-1: session auto across harnesses with an idle session keeps it and carries the conversation.
        assertEquals("kept", swap.session.outcome)
        val notices = rig.controller.state.value.notices.map { it.text }
        assertTrue(notices.any { it == "Switched to Ada · second · second-alpha" }, "$notices")
        assertTrue(notices.none { it.contains("new conversation") }, "no notice speaks of a new conversation: $notices")
        assertFalse(HarnessSwitch.commandRow().detail.contains("new conversation"), HarnessSwitch.commandRow().detail)
    }

    @Test
    fun `the header chip opens the picker and Apply sends the swap`() = withAgent { rig ->
        onNodeWithTag(EXECUTOR_SWITCH_CHIP_TAG).assertExists().performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
        onNodeWithTag("executor-harness-second").performClick()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).performClick()

        awaitSwitched(rig, "second")
        assertEquals("stub", rig.controller.state.value.agentSwaps.getValue(rig.agentId).from.provider)
        // The popover closed once the daemon confirmed, and the chip now names the new harness.
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertDoesNotExist() } }
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("second · ", substring = true).assertExists() } }
    }

    @Test
    fun `after the swap slash model lists the new harness's models`() = withAgent { rig ->
        val role = rig.controller.state.value.agents.getValue(rig.sessionId).session.role
        runBlocking { rig.controller.switchAgent(rig.sessionId, role, PickedModel("second", null)).let { r -> assertTrue(r is CommandResult.Sent, "switch: $r") } }
        awaitSwitched(rig, "second")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }

        onNodeWithText("Enter to send", substring = true).performTextInput("/model ")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { row("/model second-alpha").assertExists() } }
        row("/model second-beta").assertExists()
        row("/model stub-one").assertDoesNotExist()
        onNodeWithTag(SLASH_HEADER_TAG).assertTextEquals("second models")
    }

    @Test
    fun `a swap the daemon refuses shows its reason inline`() = withAgent { rig ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/harness ghost")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("No harness matches 'ghost'").assertExists() } }
        box("/harness ghost").performKeyInput { pressKey(Key.Enter) }
        // SWAP-AGENT-1: a harness the daemon does not report cannot be pre-selected: refused here, in plain words.
        waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.notices.any { it.text.startsWith("There is no provider 'ghost'") } }
        onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertDoesNotExist()

        // A swap that reaches the daemon and is refused there.
        val role = rig.controller.state.value.agents.getValue(rig.sessionId).session.role
        runBlocking { rig.controller.switchAgent(rig.sessionId, role, PickedModel("ghost", null)) }
        // The daemon's own words, on the strip under the chat: not swallowed, not rephrased.
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("agent_swap refused", substring = true).assertExists() } }
        onNodeWithTag(NOTICE_TAG).assertExists()
        assertTrue(rig.controller.state.value.pendingHarnessSwitch.isEmpty(), "a refused switch is no longer pending")
        assertEquals("stub", rig.controller.state.value.agents.getValue(rig.sessionId).session.provider)
        assertNotEquals("ghost", rig.controller.state.value.profiles.first { it.id == rig.agentId }.provider)
    }

    @Test
    fun `an unsaved session switches harness from the header chip and stays unsaved`() = withSession(saved = false) { rig ->
        val s0 = rig.controller.state.value
        assertTrue(s0.agents.getValue(rig.sessionId).session.agentId.isNullOrBlank(), "the rig session is ad hoc")
        val profilesBefore = s0.profiles.map { it.id }
        onNodeWithTag(EXECUTOR_SWITCH_CHIP_TAG).assertExists().performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
        onNodeWithTag("executor-harness-second").performClick()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).performClick()

        // The same session now runs on the new harness; no refusal, and no agent profile was created.
        runCatching {
            waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agents[rig.sessionId]?.session?.provider == "second" }
        }.onFailure {
            val s = rig.controller.state.value
            val shown = runCatching<String?> { onNodeWithTag(EXECUTOR_SWITCH_ERROR_TAG).fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }.getOrNull()
            throw AssertionError("no switch to second; popover error=$shown notices=${s.notices.map { it.text }}", it)
        }
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertDoesNotExist() } }
        val s = rig.controller.state.value
        assertEquals(rig.sessionId, s.selectedId, "the chat stays on the same session")
        assertEquals(profilesBefore, s.profiles.map { it.id }, "no agent profile was created")
        assertTrue(s.agents.getValue(rig.sessionId).session.agentId.isNullOrBlank())
        // The notice names the provider AND the model the session now uses (the second harness's default here).
        assertTrue(s.notices.any { it.text == "Switched to second · second-alpha" }, "${s.notices.map { it.text }}")
        assertTrue(s.notices.none { it.text.contains("saved agent") })

        // The next message runs on the new harness.
        onNodeWithText("Enter to send", substring = true).performTextInput("hello there")
        box("hello there").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = daemonWaitMs) { (rig.controller.state.value.agents[rig.sessionId]?.session?.runCount ?: 0) >= 1 &&
            rig.controller.state.value.agents[rig.sessionId]?.session?.provider == "second" &&
            rig.controller.state.value.agents[rig.sessionId]?.session?.providerSessionId != null }
    }

    @Test
    fun `slash harness on a provider with no default model opens the picker and switches nothing`() = withSession(saved = false) { rig ->
        onNodeWithText("Enter to send", substring = true).performTextInput("/harness third")
        box("/harness third").performKeyInput { pressKey(Key.Enter) }

        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Pick a model for third to switch.")
        val s = rig.controller.state.value
        assertTrue(s.pendingHarnessSwitch.isEmpty(), "nothing was sent")
        assertEquals("stub", s.agents.getValue(rig.sessionId).session.provider)
        assertTrue(s.sessionSwaps.isEmpty())
    }
}
