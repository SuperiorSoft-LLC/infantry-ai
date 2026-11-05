// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.keystore.SecretMask
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.host.LifecyclePolicy
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HARNESS-GAP G18 (ticket 5a69ec0f): a secret an agent prints (`cat .env`) reaches no session output unmasked: not the raw stream
 * (chat feed, transcript), not stderr, not Activity, not the turn prompt, and not what a terminal plugin keeps as its screen,
 * even when the terminal splits the value across two chunks. The harness-neutral path: a plugin session behind the core adapter.
 */
class SessionSecretMaskTest {
    private val secret = "sk-live-0123456789abcdef"

    /** A process that "cats" the secret when a turn is written to it: in piped mode as whole lines, on a terminal split in two chunks. */
    private class CatRunner(private val secret: String, private val tty: Boolean) : ProcessRunner {
        override val id = "cat"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            @Volatile var alive = true
            override val isAlive get() = alive
            override fun writeInput(text: String) {
                Thread {
                    if (tty) {
                        val cut = secret.length / 2
                        listener.onStdout("TOKEN=${secret.substring(0, cut)}")
                        listener.onStdout("${secret.substring(cut)}\n")
                    } else {
                        listener.onStdout("""{"type":"text","text":"TOKEN=$secret"}""")
                        listener.onStderr("warning: $secret")
                    }
                    listener.onStdout("END")
                }.start()
            }
            override fun closeInput() = Unit
            override fun killTree() { alive = false; listener.onExit(137) }
            override fun awaitExit() = 0
        }
    }

    /** The plugin: keeps everything it is handed as its "screen" and reports a chat record read off its own channel. */
    private class Plugin(private val secret: String, private val tty: Boolean) : HarnessSession {
        val screen = StringBuffer()
        override val label = "fake"
        override fun fingerprint(request: HarnessRequest, env: Map<String, String>) = "fake"
        override fun launch(request: HarnessRequest, env: Map<String, String>) = HarnessProcess(listOf("fake-cli"))
        override fun terminal(launched: HarnessProcess) = tty
        override fun connect(out: SessionOutput): SessionConnection = object : SessionConnection {
            override fun onStdout(line: String) {
                screen.append(line)
                if (screen.endsWith("END")) {
                    // A chat row the plugin read from its own transcript file, never through the masked stdout.
                    if (tty) out.output("""{"type":"assistant","text":"I read $secret"}""")
                    out.detail("tool ran: cat .env -> $secret")
                    out.turnEnded(SessionTurnEnd.COMPLETED)
                }
            }
            override fun sendTurn(request: HarnessRequest) { out.write(request.prompt + "\n") }
            override fun abortTurn() = false
        }
    }

    private val managers = mutableListOf<SessionManager>()

    @AfterTest
    fun tearDown() = managers.forEach { it.shutdown() }

    private fun run(tty: Boolean): Pair<List<SessionEvent>, Plugin> {
        val plugin = Plugin(secret, tty)
        val runner = CatRunner(secret, tty)
        val adapter = ApiSessionAdapter("fake", "Fake_Plugin", plugin, {}, terminalRunner = { runner })
        val provider = object : AgentProvider {
            override val id = "fake"
            override fun spec(request: RunRequest) = ProcessSpec(listOf("fake-cli"), request.workingDir)
            override fun defaultModel() = "fake/model"
            override fun sessionAdapter() = adapter
        }
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        val manager = SessionManager(
            provider, runner, JsonSessionStore(File.createTempFile("infantry-mask", ".json").also { it.delete() }),
            lifecyclePolicy = { LifecyclePolicy(30) },
            lineRunner = runner,
        ).also { managers += it }
        manager.eventSink = { events += it }
        manager.outputMask = SecretMask { setOf(secret) }
        val session = manager.startNew("print the token $secret", model = "fake/model")
        val deadline = System.currentTimeMillis() + 10_000
        while (manager.get(session.id)?.state != SessionState.IDLE) {
            if (System.currentTimeMillis() > deadline) fail("turn never ended: ${manager.get(session.id)} $events")
            Thread.sleep(10)
        }
        return synchronized(events) { events.toList() } to plugin
    }

    private fun texts(events: List<SessionEvent>): List<String> = events.mapNotNull {
        when (it) {
            is SessionEvent.Stdout -> it.line
            is SessionEvent.Stderr -> it.line
            is SessionEvent.Activity -> it.detail
            is SessionEvent.Turn -> it.prompt
            else -> null
        }
    }

    @Test
    fun `piped harness - stdout, stderr, activity and the prompt are masked`() {
        val (events, plugin) = run(tty = false)
        val texts = texts(events)
        assertTrue(texts.any { it.contains("TOKEN=${SecretMask.MASK}") }, "the printed line is there, masked: $texts")
        assertTrue(texts.any { it == "warning: ${SecretMask.MASK}" }, "stderr is masked: $texts")
        assertFalse(texts.any { it.contains(secret) }, "no session output carries the secret: $texts")
        assertFalse(plugin.screen.contains(secret), "the plugin is handed masked lines only")
    }

    @Test
    fun `terminal harness - a value split across chunks is masked in the screen and in the records`() {
        val (events, plugin) = run(tty = true)
        val texts = texts(events)
        assertTrue(plugin.screen.contains("TOKEN=${SecretMask.MASK}"), "the screen shows the line, masked: ${plugin.screen}")
        assertFalse(plugin.screen.contains(secret), "the screen buffer never holds the secret")
        assertTrue(texts.any { it.contains("I read ${SecretMask.MASK}") }, "the chat record is masked: $texts")
        assertFalse(texts.any { it.contains(secret) }, "no session output carries the secret: $texts")
    }
}
