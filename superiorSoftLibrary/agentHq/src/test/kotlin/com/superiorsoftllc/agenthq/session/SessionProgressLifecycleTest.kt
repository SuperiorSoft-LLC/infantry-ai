package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract 1.117.0: when a session's progress is kept and when it is cleared, and that each change is pushed. */
class SessionProgressLifecycleTest {
    private class Store(private val initial: List<SessionRecord> = emptyList()) : SessionStore {
        override fun load() = initial
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }

    private object Provider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-1" else null
    }

    private class Proc(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() { alive = false; listener.onExit(1) }
        override fun awaitExit() = 0
        fun exit(code: Int) { alive = false; listener.onExit(code) }
    }

    private class Runner : ProcessRunner {
        override val id = "fake"
        var process: Proc? = null
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            Proc(listener).also { process = it; listener.onStdout("{\"session_id\":\"prov-1\"}") }
    }

    private var now = 10_000L
    private val runner = Runner()
    private val events = mutableListOf<SessionEvent>()

    private fun manager(store: SessionStore = Store(), alive: Set<Long> = emptySet()) = SessionManager(
        Provider, runner, store, killGraceMs = 50, pidProbe = { it in alive }, clock = { now }, backgroundPollMs = 0, idleSweepMs = 0,
    ).also { it.eventSink = { e -> synchronized(events) { events += e } } }

    private fun pct(p: Double) = ProgressReport("percent", percent = p)

    @Test fun `a report is stored, pushed as a session status, and a bad one changes nothing`() {
        val m = manager()
        val s = m.startNew("go")
        events.clear()
        val r = assertNotNull(m.reportProgress(s.id, pct(40.0)))
        assertEquals(40.0, r.progress!!.percent)
        assertTrue(events.any { it is SessionEvent.Status && it.session.progress?.percent == 40.0 }, "the change is pushed: $events")
        assertFailsWith<IllegalArgumentException> { m.reportProgress(s.id, ProgressReport("percent")) }
        assertEquals(40.0, m.get(s.id)!!.progress!!.percent)
        assertEquals(40.0, m.reportProgress(s.id, ProgressReport("get"))!!.progress!!.percent)
        assertNull(m.reportProgress("no-such", pct(1.0)))
    }

    @Test fun `a turn that reported keeps it, a later turn with no report clears it`() {
        val m = manager()
        val s = m.startNew("go")
        now += 1_000
        m.reportProgress(s.id, pct(50.0))
        runner.process!!.exit(0)
        assertEquals(50.0, m.get(s.id)!!.progress?.percent, "the turn that reported keeps its report")
        now += 1_000
        m.resume(s.id, "again")
        now += 1_000
        runner.process!!.exit(0)
        assertNull(m.get(s.id)!!.progress, "a turn with no report drops the old one")
    }

    @Test fun `a registered bg-run job keeps the report across a quiet turn`() {
        val m = manager(alive = setOf(77L))
        val s = m.startNew("go")
        m.reportProgress(s.id, pct(20.0))
        runner.process!!.exit(0)
        m.bgJobBeat(s.id, BgJob("j1", "gate", 77L, now))
        now += 1_000
        m.resume(s.id, "again")
        now += 1_000
        runner.process!!.exit(0)
        assertEquals(20.0, m.get(s.id)!!.progress?.percent)
    }

    @Test fun `a killed session drops its progress`() {
        val m = manager()
        val s = m.startNew("go")
        m.reportProgress(s.id, pct(70.0))
        val killed = m.kill(s.id)
        assertEquals(SessionState.EXITED, killed.state)
        assertNull(m.get(s.id)!!.progress)
    }

    @Test fun `an estimated job reads its label's history and a done one adds to it, a failed one does not`() {
        val m = manager()
        val history = InMemoryProgressEstimates()
        m.progressEstimates = history
        val s = m.startNew("go")
        val first = m.reportProgress(s.id, ProgressReport("status", label = "gate", estimate = true))!!.progress!!
        assertEquals(true, first.estimated)
        assertNull(first.estimateMs, "no history yet")
        now += 600_000
        m.reportProgress(s.id, ProgressReport("done"))
        assertEquals(600_000, history.estimate(null, "gate"))
        m.reportProgress(s.id, ProgressReport("done"))
        assertEquals(600_000, history.estimate(null, "gate"), "a second done of a finished job is not a second run")
        val second = m.reportProgress(s.id, ProgressReport("status", label = "gate", estimate = true))!!.progress!!
        assertEquals(600_000, second.estimateMs)
        now += 60_000
        m.reportProgress(s.id, ProgressReport("fail", label = "exit 1"))
        assertEquals(600_000, history.estimate(null, "gate"), "a failure is not a duration")
        m.reportProgress(s.id, ProgressReport("status", label = "plain"))
        now += 1_000
        m.reportProgress(s.id, ProgressReport("done"))
        assertEquals(1_000, history.estimate(null, "plain"), "1.123.0: every labelled job teaches its label, opted in or not")
    }

    @Test fun `a daemon start clears every persisted report, orphaned or not`() {
        val p = SessionProgress(mode = "percent", percent = 5.0, startedAt = 1, updatedAt = 1)
        fun rec(id: String, state: SessionState, pid: Long?) = SessionRecord(
            id = id, provider = "fake", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = state, childPid = pid, progress = p,
        )
        val m = manager(Store(listOf(rec("idle", SessionState.IDLE, null), rec("orphan", SessionState.BUSY, 99L))), alive = setOf(99L))
        m.loadAndReconcile()
        assertEquals(SessionState.ORPHANED, m.get("orphan")!!.state)
        assertNull(m.get("orphan")!!.progress)
        assertNull(m.get("idle")!!.progress)
    }
}
