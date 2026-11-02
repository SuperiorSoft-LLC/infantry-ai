package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UI-BOOT-1: the daemon explains a run, so a chat is never silent until the first reply and a failed run says why.
 *
 * Contract 1.64.0 (BUG-M2-11b-1) supersedes the per-turn chat lines: a launch shows ONE `* session …` chat line, and
 * the per-turn plumbing (`starting …`, `process started …`, `resuming provider session …`, `Resuming session …`) is in
 * the Activity box. The model naming and the auth hint keep their meaning.
 */
class SessionBootLinesTest {

    private class Rig(script: StubScript, providerDefault: String? = null) {
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        val manager: SessionManager

        init {
            val file = File.createTempFile("agenthq-boot", ".json").also { it.delete() }
            manager = SessionManager(
                StubProvider(script, delayMs = 0).let { stub ->
                    if (providerDefault == null) stub
                    else object : AgentProvider by stub { override fun defaultModel() = providerDefault }
                },
                ScriptedRunner(script, delayMs = 0),
                JsonSessionStore(file),
            )
            manager.eventSink = { events += it }
        }

        fun awaitExit(count: Int = 1) {
            val deadline = System.currentTimeMillis() + 5_000
            while (events.count { it is SessionEvent.Exit } < count) {
                if (System.currentTimeMillis() > deadline) fail("run never exited: $events")
                Thread.sleep(10)
            }
        }

        fun stderr(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line } }
        fun activity(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Activity>().map { it.detail } }
    }

    private val replying = StubScript(
        stdoutLines = listOf("""{"type":"step_start","sessionID":"prov-sess-9"}""", """{"type":"text","text":"hi"}"""),
        exitCode = 0,
    )

    @Test
    fun spawn_emitsOneBootLine_andStartingThenStartedInActivity_beforeAnyText() {
        val rig = Rig(replying)
        rig.manager.startNew("hello", model = "some/model")
        rig.awaitExit()

        val firstStdout = rig.events.indexOfFirst { it is SessionEvent.Stdout }
        val boot = rig.events.indexOfFirst { it is SessionEvent.Stderr && it.line == "[agenthq] * session started (stub · some/model)" }
        val starting = rig.events.indexOfFirst { it is SessionEvent.Activity && it.detail.startsWith("starting stub model some/model in ") }
        val started = rig.events.indexOfFirst { it is SessionEvent.Activity && it.detail.startsWith("process started") }
        assertTrue(boot >= 0, "missing boot line: ${rig.stderr()}")
        assertTrue(starting >= 0, "missing starting detail: ${rig.activity()}")
        assertTrue(started > starting, "started must follow starting: ${rig.activity()}")
        assertTrue(firstStdout > started && firstStdout > boot, "all must precede any text: ${rig.events}")
        assertTrue(rig.stderr().none { it.startsWith("[agenthq] starting") || it.startsWith("[agenthq] process started") }, "${rig.stderr()}")
    }

    @Test
    fun spawnWithoutModel_saysDefault() {
        val rig = Rig(replying)
        rig.manager.startNew("hello")
        rig.awaitExit()
        assertTrue(rig.activity().any { it.startsWith("starting stub model default in ") }, "${rig.activity()}")
        assertTrue("[agenthq] * session started (stub · default)" in rig.stderr(), "${rig.stderr()}")
    }

    /** BUG-G2: the line names the model the run really uses, not a bare "default" (AGENTHQ_OPENCODE_MODEL). */
    @Test
    fun spawnWithoutModel_namesTheProvidersConfiguredDefault() {
        val rig = Rig(replying, providerDefault = "opencode/big-pickle")
        rig.manager.startNew("hello")
        rig.awaitExit()
        assertTrue(
            rig.activity().any { it.startsWith("starting stub model opencode/big-pickle (provider default) in ") },
            "${rig.activity()}",
        )
        assertTrue("[agenthq] * session started (stub · opencode/big-pickle)" in rig.stderr(), "${rig.stderr()}")
    }

    @Test
    fun silentNonZeroExit_emitsAuthHint() {
        val rig = Rig(StubScript(exitCode = 1))
        rig.manager.startNew("hello")
        rig.awaitExit()
        val hint = "[agenthq] run failed (exit 1). If this is an auth problem run `opencode auth login` / `claude login`."
        assertTrue(hint in rig.stderr(), "missing auth hint: ${rig.stderr()}")
    }

    @Test
    fun nonZeroExitAfterReplyText_emitsNoAuthHint() {
        val rig = Rig(StubScript(stdoutLines = listOf("""{"type":"text","text":"partial"}"""), exitCode = 3))
        rig.manager.startNew("hello")
        rig.awaitExit()
        assertTrue(rig.stderr().none { it.contains("run failed") }, "${rig.stderr()}")
    }

    @Test
    fun cleanExit_emitsNoAuthHint() {
        val rig = Rig(StubScript(exitCode = 0))
        rig.manager.startNew("hello")
        rig.awaitExit()
        assertTrue(rig.stderr().none { it.contains("run failed") }, "${rig.stderr()}")
    }

    @Test
    fun resume_emitsResumingDetailWithProviderSessionId() {
        val rig = Rig(replying)
        val first = rig.manager.startNew("hello")
        rig.awaitExit()
        rig.manager.resume(first.id, "again")
        rig.awaitExit(2)
        assertEquals(1, rig.activity().count { it == "resuming provider session prov-sess-9" }, "${rig.activity()}")
        assertTrue(rig.activity().first { it.startsWith("resuming") } != rig.activity().first())
    }

    @Test
    fun resume_emitsResumingSessionFeedbackDetail() {
        val rig = Rig(replying)
        val first = rig.manager.startNew("hello")
        rig.awaitExit()
        rig.manager.resume(first.id, "again")
        rig.awaitExit(2)
        assertTrue(rig.activity().any { it.contains("Resuming session") }, "missing resume feedback detail: ${rig.activity()}")
    }
}
