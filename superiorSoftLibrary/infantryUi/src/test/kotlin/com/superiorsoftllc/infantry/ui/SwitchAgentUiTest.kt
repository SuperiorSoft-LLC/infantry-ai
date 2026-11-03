package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.SWITCH_WAIT_FOR_TURN
import com.superiorsoftllc.infantry.ui.model.isBusy
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
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
 * SWAP-AGENT-1 in the REAL window against a REAL in-process daemon with two stub harnesses (`stub`, the default, and
 * `second`, whose runs are recorded): the composer's Switch agent opens the one all-in-one picker, and Apply swaps the
 * agent of the SAME session -- role, harness and model -- with the conversation carried to the new harness
 * (HARNESS-CARRY-1). The sidebar's New agent still creates a new session; a turn in flight disables Apply; the header
 * chip opens the same picker pre-filled; an unresolved model keeps Apply disabled.
 */
@OptIn(ExperimentalTestApi::class)
class SwitchAgentUiTest {
    /**
     * FLAKE-4 (the FLAKE-1 pattern): the budget for every wait on a daemon round trip here, infantry's TestWait (60 s,
     * INFANTRY_TEST_WAIT_MS overrides it). Under concurrent build load the fixed 10-30 s waits timed out on a turn or a
     * save the daemon answered late. A passing run waits milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private val script = StubScript(name = "switch", stdoutLines = listOf("""{"type":"step_start","sessionID":"s-1"}""", """{"type":"step_finish"}"""), exitCode = 0)
    private val roleA = "backend"
    private val roleB = "qa"

    private inner class Second(val runner: ScriptedRunner) : AgentProvider by StubProvider(script, delayMs = 0, modelIds = listOf("second-alpha", "second-beta")) {
        override val id = "second"
        override fun defaultModel(): String = "second-alpha"
        override fun transport(): ProcessRunner = runner
    }

    /** A harness that names NO default model: picking it leaves the model unresolved. */
    private inner class Third : AgentProvider by StubProvider(script, delayMs = 0, modelIds = listOf("third-x", "third-y")) {
        override val id = "third"
    }

    private class Rig(val controller: Controller, val sessionId: String, val second: ScriptedRunner)

    /** A session running [roleA] on `stub`; [stubDelayMs] slows each `stub` line so a turn stays in flight. */
    private fun withRoleSession(stubDelayMs: Long = 0, body: ComposeUiTest.(Rig) -> Unit) {
        val dir = Files.createTempDirectory("infantry-switch-agent-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(script, delayMs = stubDelayMs, modelIds = listOf("stub-one", "stub-two"))
        val secondRunner = ScriptedRunner(script, 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            additionalProviders = listOf(Second(secondRunner), Third()),
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
            runDesktopComposeUiTest(1700, 1100) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.roles.any { it.id == roleA } && s.roles.any { it.id == roleB } &&
                        s.providers.any { it.id == "second" } && s.providers.any { it.id == "third" }
                }
                assertTrue(runBlocking { controller.createProject("Alpha") } is CommandResult.Sent)
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId != null }
                controller.pickSpawnRole(roleA)
                assertTrue(runBlocking { controller.spawn("first task") } is CommandResult.Sent)
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents.size == 1 }
                val sid = controller.state.value.agents.keys.single()
                controller.select(sid)
                // BUG-FLAKE-SWAGENT: hand over only once the first turn has come to rest, as the turn-in-flight test and
                // HarnessSwitchUiTest's rig do. The role-bound spawn binds the session at `new` (IDLE, runCount 0), and that
                // never-run session already reads "Enter to send" before its first turn starts: a body acting then lands
                // mid-turn (its prompt or swap is refused, or Apply is disabled).
                waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.agents[sid]?.let { it.session.runCount >= 1 && !isBusy(it) } == true }
                waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("Enter to send", substring = true).assertExists() } }
                body(Rig(controller, sid, secondRunner))
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }

    private fun exists(check: () -> Unit): Boolean = runCatching(check).isSuccess

    private fun ComposeUiTest.box(text: String) = onNode(hasSetTextAction() and hasText(text, substring = true))

    private fun ComposeUiTest.send(rig: Rig, text: String, runs: Int) {
        onNodeWithText("Enter to send", substring = true).performTextInput(text)
        box(text).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = daemonWaitMs) {
            val a = rig.controller.state.value.agents[rig.sessionId]
            a != null && a.session.runCount >= runs && !isBusy(a)
        }
    }

    private fun ComposeUiTest.openFromComposer() {
        onNodeWithContentDescription(SWITCH_AGENT_LABEL).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
    }

    @Test
    fun `Apply swaps role and harness in the same session and the next turn runs there with the earlier turns`() = withRoleSession { rig ->
        send(rig, "second task", runs = 2)
        val before = rig.controller.state.value
        assertEquals(roleA, before.agents.getValue(rig.sessionId).role)

        openFromComposer()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsNotEnabled() // nothing changed yet
        onNodeWithTag(SWITCH_ROLE_TAG_PREFIX + roleB).performClick()
        onNodeWithTag("executor-harness-second").performClick()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Model: second-alpha (default)")
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsEnabled().performClick()

        // Acceptance 1: the same session stays selected, now role B on `second`, and no session was added.
        runCatching {
            waitUntil(timeoutMillis = daemonWaitMs) {
                val a = rig.controller.state.value.agents[rig.sessionId]
                a?.session?.provider == "second" && a.role == roleB
            }
        }.onFailure { val s = rig.controller.state.value; throw AssertionError("no swap; notices=${s.notices.map { it.text }} err=${s.sessionSwitchError}", it) }
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertDoesNotExist() } }
        val s = rig.controller.state.value
        assertEquals(rig.sessionId, s.selectedId, "the same session stays selected")
        assertEquals(setOf(rig.sessionId), s.agents.keys, "no new session in the sidebar")
        val name = s.role(roleB)!!.name
        assertTrue(s.notices.any { it.text == "Switched to $name · second · second-alpha" }, "${s.notices.map { it.text }}")
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithText("second · second-alpha").assertExists() } }

        // Acceptance 2: the next message runs on `second` as role B, and its first invocation carries the earlier turns.
        send(rig, "what did I ask first?", runs = 1)
        waitUntil(timeoutMillis = daemonWaitMs) { rig.second.processes.isNotEmpty() }
        val input = rig.second.processes.first().deliveredInput
        assertTrue(input.contains("first task") && input.contains("second task"), "the earlier turns are carried: ${input.take(1500)}")
        assertTrue(input.lastIndexOf("role '$roleB'") > input.lastIndexOf("second task"), "role B's prompt opens the new turn: ${input.take(1500)}")
        assertTrue(input.trimEnd().endsWith("what did I ask first?"), input.takeLast(200))
    }

    @Test
    fun `the sidebar New agent still creates a new session`() = withRoleSession { rig ->
        onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + roleB).assertExists() } }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + roleB).performClick()
        // The dialog's own rule: Add waits for a harness AND a model (free text: the stub harnesses publish no catalog).
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "second").performClick()
        waitForIdle()
        onNodeWithTag(NEW_AGENT_MODEL_FREETEXT_TAG).performTextInput("second-beta")
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("another job")
        waitForIdle()
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsEnabled().performClick()
        runCatching { waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agents.size == 2 } }.onFailure {
            val st = rig.controller.state.value
            val shown = runCatching { onNodeWithTag(NEW_AGENT_ERROR_TAG).fetchSemanticsNode().config.toString().take(400) }.getOrNull()
            throw AssertionError("no second session; notices=${st.notices.map { it.text }} add=$shown", it)
        }
        assertEquals(roleA, rig.controller.state.value.agents.getValue(rig.sessionId).role, "the first session is untouched")
    }

    @Test
    fun `with a turn in flight Apply is disabled with its reason and nothing changes`() = withRoleSession(stubDelayMs = 1_500) { rig ->
        // The slow first turn comes to rest before the picker opens.
        waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agents[rig.sessionId]?.let { !isBusy(it) && it.session.runCount >= 1 } == true }
        openFromComposer()
        onNodeWithTag(SWITCH_ROLE_TAG_PREFIX + roleB).performClick()
        onNodeWithTag("executor-harness-second").performClick()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsEnabled()

        onNodeWithText("Enter to send", substring = true).performTextInput("keep working")
        box("keep working").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agents[rig.sessionId]?.let(::isBusy) == true }
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsNotEnabled()
        onNode(SemanticsMatcher.expectValue(IconActionTooltip, SWITCH_WAIT_FOR_TURN)).assertExists()
        // The controller refuses too, so no path sends a swap mid-turn.
        val r = runBlocking { rig.controller.switchAgent(rig.sessionId, roleB, com.superiorsoftllc.infantry.ui.model.PickedModel("second", null)) }
        assertEquals(CommandResult.Refused(SWITCH_WAIT_FOR_TURN), r)
        val a = rig.controller.state.value.agents.getValue(rig.sessionId)
        assertEquals("stub", a.session.provider)
        assertEquals(roleA, a.role)
        assertTrue(rig.controller.state.value.sessionSwaps.isEmpty(), "no swap was sent")
    }

    @Test
    fun `the header chip opens the picker pre-filled and changing only the model keeps role and provider`() = withRoleSession { rig ->
        onNodeWithTag(EXECUTOR_SWITCH_CHIP_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { exists { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() } }
        // The stub harness lists no models: the picker's model is free text.
        onNodeWithTag("executor-model-freetext").performTextInput("stub-two")
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Model: stub-two")
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { rig.controller.state.value.agents[rig.sessionId]?.session?.model == "stub-two" }
        val a = rig.controller.state.value.agents.getValue(rig.sessionId)
        assertEquals("stub", a.session.provider, "the provider is kept")
        assertEquals(roleA, a.role, "the role is kept")
        assertEquals(roleA, a.session.role)
    }

    @Test
    fun `an unresolved model leaves Apply disabled`() = withRoleSession { rig ->
        openFromComposer()
        // The stub harness names no default model, so its model is a free-text field: a model typed is resolved...
        onNodeWithTag("executor-model-freetext").performTextInput("stub-one")
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsEnabled()
        // ...and clearing it leaves nothing resolved: Apply is disabled and the model line says what is missing.
        onNodeWithTag("executor-model-freetext").performTextClearance()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Pick a model for stub to switch.")
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsNotEnabled()
        // A harness with no default clears the model too.
        onNodeWithTag("executor-harness-third").performClick()
        waitForIdle()
        onNodeWithTag(EXECUTOR_SWITCH_MODEL_TAG).assertTextEquals("Pick a model for third to switch.")
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertIsNotEnabled()
        assertEquals("stub", rig.controller.state.value.agents.getValue(rig.sessionId).session.provider, "nothing was applied")
    }
}
