package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A `sleep N` seen in a tool call becomes a "waiting" countdown that ends with the call; remaining time only falls. */
class SleepWaitTest {
    private object Store : SessionStore {
        override fun load() = emptyList<SessionRecord>()
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }

    private object Provider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
        override fun sessionIdFromLine(line: String) = null
    }

    private class Proc(private val l: ProcessListener) : RunningProcess {
        override val pid = 1L
        override val isAlive = true
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() = l.onExit(1)
        override fun awaitExit() = 0
    }

    private val runner = object : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = Proc(listener)
    }

    private var now = 1_000L
    private val m = SessionManager(Provider, runner, Store, killGraceMs = 50, clock = { now }, backgroundPollMs = 0, idleSweepMs = 0)

    @Test fun `starts on the call, counts down, ends on the result`() {
        val id = m.startNew("go").id
        m.setWait(id, WaitHint(80, now))
        val p = assertNotNull(m.get(id)?.progress)
        assertEquals("waiting", p.label)
        assertEquals(SessionProgress.SOURCE_SLEEP, p.estimateSource)
        var last = Long.MAX_VALUE
        for (t in listOf(0L, 10_000L, 40_000L, 80_000L, 200_000L)) {
            now = 1_000L + t
            val left = ProgressReducer.eta(p, now).remainingMs ?: 0L
            assert(left <= last) { "remaining grew: $left > $last" }
            last = left
        }
        assertEquals(80_000L, ProgressReducer.eta(p, 1_000L).remainingMs)
        m.clearWait(id)
        assertNull(m.get(id)?.progress)
    }

    @Test fun `the agent's own progress wins and survives a clear`() {
        val id = m.startNew("go").id
        m.reportProgress(id, ProgressReport("percent", percent = 40.0, label = "gradle"))
        m.setWait(id, WaitHint(60, now))
        assertEquals("gradle", m.get(id)?.progress?.label)
        m.clearWait(id)
        assertEquals("gradle", m.get(id)?.progress?.label)
    }

    @Test fun `never shown for a killed session`() {
        val id = m.startNew("go").id
        m.kill(id)
        m.setWait(id, WaitHint(60, now))
        assertNull(m.get(id)?.progress)
    }
}
