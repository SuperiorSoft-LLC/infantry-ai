// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RESUME-NOTIFY-1: a daemon restart cut two workers' turns and nobody was told. A session that was mid-turn when the daemon
 * stopped is resumed on the next start and its starter and the owner hear about it; a session at rest between turns is left
 * alone; the setting turns the resume off but never the notice.
 */
class RestartResumeTest {

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-123" else null
    }

    private class FakeProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() { alive = false; listener.onExit(1) }
        override fun awaitExit(): Int = 0
        fun exit(code: Int) { alive = false; listener.onExit(code) }
    }

    private class FakeRunner : ProcessRunner {
        override val id = "fake"
        val specs = mutableListOf<ProcessSpec>()
        var process: FakeProcess? = null
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return FakeProcess(listener).also { process = it; listener.onStdout("{\"session_id\":\"prov-123\"}") }
        }
    }

    private fun storeFile(): File = File.createTempFile("infantry-restart-resume", ".json").also { it.delete() }

    /** The daemon that comes back: same store, the old child is gone. */
    private fun restarted(file: File, runner: FakeRunner = FakeRunner(), alive: Boolean = false) =
        SessionManager(FakeProvider(), runner, JsonSessionStore(file), killGraceMs = 50, pidProbe = { _: Long -> alive })

    private class Recorder {
        val prompts = mutableListOf<Pair<String, String>>()
        val notices = mutableListOf<Pair<String, String>>()
        val raised = mutableListOf<Pair<String, List<String>>>()
    }

    private fun resumer(manager: SessionManager, rec: Recorder, enabled: Boolean = true, prompt: String = "") = RestartResume(
        get = manager::get,
        resume = { id, text -> rec.prompts += id to text; manager.resume(id, text, wakeReason = RestartResume.WAKE_REASON) },
        notify = { s, line -> rec.notices += s.id to line },
        raise = { summary, ids -> rec.raised += summary to ids },
        enabled = { enabled },
        prompt = { prompt },
    )

    @Test
    fun midTurnAtStop_isResumedAndNotified() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        assertEquals(SessionState.BUSY, session.state, "the turn is in flight when the daemon stops")

        val runnerB = FakeRunner()
        val managerB = restarted(file, runnerB)
        val report = managerB.loadAndReconcile()
        assertEquals(listOf(session.id), report.cutTurns, "the cut turn is reported")

        val rec = Recorder()
        val outcome = resumer(managerB, rec).run(report)

        assertEquals(listOf(session.id), outcome.resumed)
        assertEquals(listOf(session.id to RestartResume.DEFAULT_PROMPT), rec.prompts, "the default prompt when the setting is empty")
        val resumed = managerB.get(session.id)!!
        assertEquals(SessionState.BUSY, resumed.state, "the session runs again")
        assertEquals(listOf("fake", "prov-123"), runnerB.specs.last().command, "it continues the same conversation")
        assertEquals(1, rec.notices.size, "one notice to the session's starter")
        assertEquals(session.id, rec.notices.single().first)
        assertTrue(rec.notices.single().second.contains("prompted to continue"), rec.notices.single().second)
        assertEquals(1, rec.raised.size, "one attention item for the owner")
        assertTrue(rec.raised.single().first.startsWith("1 session(s) had their turn cut by a daemon restart; 1 resumed."), rec.raised.single().first)
        assertEquals(listOf(session.id), rec.raised.single().second)
    }

    @Test
    fun sessionAtRestBetweenTurns_isNotPrompted() {
        val file = storeFile()
        val runnerA = FakeRunner()
        val session = SessionManager(FakeProvider(), runnerA, JsonSessionStore(file), killGraceMs = 50).startNew("short turn")
        runnerA.process!!.exit(0)

        val managerB = restarted(file)
        val report = managerB.loadAndReconcile()
        assertTrue(report.cutTurns.isEmpty(), "an idle session's turn was not cut: ${report.cutTurns}")

        val rec = Recorder()
        val outcome = resumer(managerB, rec).run(report)
        assertEquals(0, outcome.total)
        assertTrue(rec.prompts.isEmpty() && rec.notices.isEmpty() && rec.raised.isEmpty(), "nothing is sent for a session at rest")
        assertEquals(SessionState.IDLE, managerB.get(session.id)!!.state)
    }

    @Test
    fun resumeOff_stillNotifies_butDoesNotPrompt() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        val managerB = restarted(file)
        val report = managerB.loadAndReconcile()

        val rec = Recorder()
        val outcome = resumer(managerB, rec, enabled = false).run(report)
        assertEquals(listOf(session.id), outcome.notResumed)
        assertTrue(rec.prompts.isEmpty(), "auto-resume off: no prompt")
        assertEquals(SessionState.EXITED, managerB.get(session.id)!!.state)
        assertTrue(rec.notices.single().second.contains(RestartResume.ENABLED_SETTING), "the notice names the setting")
        assertEquals(1, rec.raised.size)
    }

    @Test
    fun customPrompt_isTheOneDelivered() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        val managerB = restarted(file)
        val rec = Recorder()
        resumer(managerB, rec, prompt = "Go on.").run(managerB.loadAndReconcile())
        assertEquals(listOf(session.id to "Go on."), rec.prompts)
    }

    @Test
    fun orphanStillRunning_isNamedButNotPrompted() {
        val file = storeFile()
        val session = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        val managerB = restarted(file, alive = true)
        val report = managerB.loadAndReconcile()
        assertTrue(report.cutTurns.isEmpty())
        assertEquals(listOf(session.id), report.orphanedIds)

        val rec = Recorder()
        val outcome = resumer(managerB, rec).run(report)
        assertEquals(listOf(session.id), outcome.orphaned)
        assertTrue(rec.prompts.isEmpty(), "a process that outlived the daemon is never prompted twice")
        assertTrue(rec.notices.single().second.contains("still alive"))
        assertEquals(SessionState.ORPHANED, managerB.get(session.id)!!.state)
    }
}
