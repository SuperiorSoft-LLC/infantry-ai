// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedProcess
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.SessionState
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/**
 * UI-AGENT-DEL (+M2-5 menu/confirm): Remove on a sidebar agent row deletes that session's row, and the other
 * row stays. Drives the REAL desktop window against a REAL in-process daemon (StubProvider), exactly
 * like [DemoFlowE2ETest]: no mocked daemon, no SendKeys.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarDeleteUiTest {

    private fun bootDaemon(closers: MutableList<() -> Unit>, runner: ProcessRunner? = null): Int {
        val dir = Files.createTempDirectory("infantry-ui-delete").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = runner ?: provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
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

    @Test
    fun `the Remove button on a sidebar row deletes that session and leaves the other row`() = removeTheFirstOfTwoRows(hold = null)

    /**
     * BUG-FLAKE-2 (gate #21, E2E-OWNER-1): under load the second session is still WORKING when the first row's "⋯"
     * is clicked, and it settles while that click is in flight. The WORKING group and its header leave the list, the
     * first row moves up under the pointer, the release lands outside the 18dp button and the click is dropped: the
     * menu never opens and `session-remove-<id>` is not found. Forced here instead of hoped for: the second turn is
     * held (for up to [HOLD_MS], a slow machine), and the first mouse press releases it and returns only once the
     * controller has every session at rest, so the regroup would land inside that click. It passes because the flow
     * waits for every session to be IDLE before clicking; without that wait it fails at the `session-remove-<id>` lookup.
     */
    @Test
    fun `Remove still works when the other session settles while the row menu is being clicked`() =
        removeTheFirstOfTwoRows(hold = HoldOneTurnRunner(heldPrompt = "second task", holdMs = HOLD_MS))

    /**
     * BUG-UX-SHIFT-1: the product bug behind BUG-FLAKE-2. A real user does not wait for every session to be IDLE: the
     * other session settles while "⋯" is held down, the WORKING group leaves the list, the row moves up under the pointer
     * and the click is dropped. The row's menu must open anyway.
     */
    @Test
    fun `the row menu opens even when the other session settles while it is being clicked`() =
        removeTheFirstOfTwoRows(hold = HoldOneTurnRunner(heldPrompt = "second task", holdMs = HOLD_MS), waitForIdle = false)

    private fun removeTheFirstOfTwoRows(hold: HoldOneTurnRunner?, waitForIdle: Boolean = true) {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers, hold)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent {
                    if (hold == null) {
                        App(controller, "ws://127.0.0.1:$port/ws", null)
                    } else {
                        // Sees every press first (Initial pass) and consumes nothing: the click itself is untouched.
                        Box(Modifier.fillMaxSize().pointerInput(hold) {
                            awaitPointerEventScope {
                                while (true) {
                                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) hold.settleOnFirstPress(controller)
                                }
                            }
                        }) { App(controller, "ws://127.0.0.1:$port/ws", null) }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive && controller.state.value.roles.isNotEmpty()
                }

                // Spawn two sessions; both finish quickly and settle as history (IDLE) rows.
                assertSent(runBlocking { controller.spawn("first task") })
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.size == 1 }
                val id1 = controller.state.value.agents.keys.single()
                assertSent(runBlocking { controller.spawn("second task") })
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.size == 2 }
                val id2 = controller.state.value.agents.keys.first { it != id1 }
                // The repro's own precondition: the second turn really is held, so it is still WORKING at this point.
                if (hold != null) waitUntil(timeoutMillis = 20_000) { hold.held }

                // BUG-FLAKE-2: click only once both rows have settled. A session leaving WORKING takes the WORKING group
                // header with it and moves the rows below, so a settle during the click drops it off the button.
                if (waitForIdle) waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.values.all { it.session.state == SessionState.IDLE } }
                onNodeWithTag("session-menu-$id1").assertExists()
                onNodeWithTag("session-menu-$id2").assertExists()

                // Remove row 1 through its "⋯" menu and the confirm dialog (M2-5): its row disappears, the other stays.
                onNodeWithTag("session-menu-$id1").performClick()
                onNodeWithTag("session-remove-$id1").performClick()
                onNodeWithTag(SESSION_REMOVE_CONFIRM_TAG).performClick()
                waitUntil(timeoutMillis = 20_000) { id1 !in controller.state.value.agents }
                onNodeWithTag("session-menu-$id1").assertDoesNotExist()
                onNodeWithTag("session-menu-$id2").assertExists()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    /**
     * SESSION-RM-1: the composer's trash button on a selected EXITED session is "Remove agent" (End session has
     * nothing left to end there): it opens the same confirm as the row menu and removes the row. On a session that
     * has not ended it is End session, enabled even while IDLE (ticket a7f33ba2).
     */
    @Test
    fun `the composer trash button removes a selected exited session`() {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.connection.isLive && controller.state.value.roles.isNotEmpty()
                }
                assertSent(runBlocking { controller.spawn("a task") })
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.size == 1 }
                val id = controller.state.value.agents.keys.single()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents[id]?.session?.state == SessionState.IDLE }
                controller.select(id)
                waitForIdle()
                onNodeWithContentDescription(END_SESSION_LABEL).assertIsEnabled()
                onNodeWithContentDescription(REMOVE_AGENT_LABEL).assertDoesNotExist()

                runBlocking { controller.kill(id) }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.agents[id]?.session?.state == SessionState.EXITED }
                waitUntil(timeoutMillis = 10_000) { exists { onNodeWithContentDescription(REMOVE_AGENT_LABEL).assertIsEnabled() } }
                onNodeWithContentDescription(REMOVE_AGENT_LABEL).performClick()
                onNodeWithTag(SESSION_REMOVE_CONFIRM_TAG).performClick()
                waitUntil(timeoutMillis = 20_000) { id !in controller.state.value.agents }
                onNodeWithTag("session-menu-$id").assertDoesNotExist()
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.exists(check: () -> Unit): Boolean = runCatching { check() }.isSuccess

    private fun assertSent(r: com.superiorsoftllc.infantry.ui.net.CommandResult) {
        check(r is com.superiorsoftllc.infantry.ui.net.CommandResult.Sent) { "expected the spawn to be sent, was $r" }
    }

    private companion object {
        /** How long the held turn stays in flight if no press releases it first: a machine slow enough to matter. */
        const val HOLD_MS = 2_000L
    }
}

/**
 * BUG-FLAKE-2: the stub's scripted transport, except that the turn whose stdin carries [heldPrompt] does not start its
 * replay (stdin EOF, as on a real CLI) until the first mouse press or [holdMs], whichever comes first. Its session
 * stays WORKING meanwhile, the way a slow machine keeps it there, and then finishes with the stub's normal script.
 */
private class HoldOneTurnRunner(private val heldPrompt: String, private val holdMs: Long) : ProcessRunner {
    override val id: String = "hold-one-turn"

    private val gate = CountDownLatch(1)
    private val pressed = AtomicBoolean(false)

    /** True once the held turn reached stdin EOF and was held. */
    @Volatile var held = false
        private set

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        val turn = ScriptedProcess(listener, StubScript.default(), delayMs = 0)
        return object : RunningProcess by turn {
            override fun closeInput() {
                if (heldPrompt !in turn.deliveredInput) return turn.closeInput()
                held = true
                Thread({ gate.await(holdMs, TimeUnit.MILLISECONDS); turn.closeInput() }, "held-turn").apply { isDaemon = true }.start()
            }
        }
    }

    /**
     * Runs on the UI thread inside the first press's dispatch: lets the held turn finish and blocks until the
     * controller has every session at rest, so the next frames (the click's own press-to-release interval) regroup
     * the list while the button is held down.
     */
    fun settleOnFirstPress(controller: Controller) {
        if (!pressed.compareAndSet(false, true)) return
        gate.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (controller.state.value.agents.values.any { it.session.state != SessionState.IDLE } && System.nanoTime() < deadline) Thread.sleep(5)
    }
}
