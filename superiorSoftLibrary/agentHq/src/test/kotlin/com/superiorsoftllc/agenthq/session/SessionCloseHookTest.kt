package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ticket b785f383: every session-closing event tells the daemon, with what the session keeps alive. */
class SessionCloseHookTest {
    private val kept = BgJob("k", "dev server", 501L, 0, keep = true)
    private val plain = BgJob("p", "gate", 502L, 0)

    private fun record(id: String) = SessionRecord(
        id = id, provider = "fake", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE,
    )

    private class Store(private val initial: List<SessionRecord>) : SessionStore {
        override fun load() = initial
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }

    private object NoProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
    }

    private object NoRunner : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("no run expected")
    }

    private val now = 5_000L
    private val closed = mutableListOf<SessionClosed>()

    private fun manager() = SessionManager(
        NoProvider, NoRunner, Store(listOf(record("a"), record("b"), record("c"))),
        pidProbe = { true }, clock = { now }, backgroundPollMs = 0, idleSweepMs = 0,
    ).also {
        it.loadAndReconcile() // bg jobs are never trusted from disk: they register again
        for (id in listOf("a", "b", "c")) { it.bgJobBeat(id, kept); it.bgJobBeat(id, plain) }
        it.onSessionClosed = { c -> closed += c }
    }

    @Test fun `kill, remove and restart each report one close with the keep-flagged jobs only`() {
        val m = manager()
        m.kill("a")
        m.delete("b")
        m.restart("c")
        assertEquals(
            listOf(
                SessionClosed("a", now, setOf(501L), "session killed"),
                SessionClosed("b", now, setOf(501L), "session removed"),
                SessionClosed("c", now, setOf(501L), "session restarted"),
            ),
            closed,
        )
    }

    @Test fun `a failing hook never fails the kill`() {
        val m = manager()
        m.onSessionClosed = { error("boom") }
        assertEquals(SessionState.EXITED, m.kill("a").state)
    }
}
