package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ticket 7e9bed2a: BUSY-but-silent is flagged, never killed while alive; ended only when provably dead. Fakes only. */
class SessionSilentSweepTest {
    private val min = 60_000L
    private val now = AtomicLong(1_000_000)
    private val flagged = mutableListOf<Pair<String, Long>>()

    private object Prov : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
        override fun sessionIdFromLine(line: String): String? = null
    }

    private class Hang : ProcessRunner {
        override val id = "hang"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            override val pid: Long? = null
            override val isAlive = true
            override fun writeInput(text: String) = Unit
            override fun closeInput() = Unit
            override fun killTree() = listener.onExit(137)
            override fun awaitExit(): Int = 137
        }
    }

    private fun manager(store: SessionStore = JsonSessionStore(File.createTempFile("silent", ".json").apply { delete() })) =
        SessionManager(
            provider = Prov, runner = Hang(), store = store, killGraceMs = 10, clock = now::get,
            deadlineMs = 1_000 * min, stallMs = 1_000 * min, watchdogPollMs = 1_000_000,
            backgroundPollMs = 0, idleSweepMs = 0, silentMs = 20 * min, silentPollMs = 0,
            pidProbe = { true }, bindingPolicy = com.superiorsoftllc.agenthq.daemon.plugins.TicketBindingPolicy(ticketDeadlineMs = 1_000 * min, gateLock = GateLock(dir = { null })),
        ).also { m -> m.silentSink = { s, mins -> flagged += s.id to mins } }

    @Test fun `a silent run with a live process is flagged once and never stopped`() {
        val m = manager()
        try {
            val s = m.startNew("work")
            now.addAndGet(19 * min); m.sweepSilent()
            assertEquals(0, flagged.size, "under the limit")
            now.addAndGet(5 * min); m.sweepSilent(); m.sweepSilent()
            assertEquals(listOf(s.id to 24L), flagged, "flagged once for one silence")
            assertTrue(m.get(s.id)!!.state != SessionState.EXITED, "a live run is never ended by the sweeper")
        } finally {
            m.shutdown()
        }
    }

    @Test fun `a registered bg job means the session is not silent`() {
        val m = manager()
        try {
            val s = m.startNew("work")
            m.bgJobBeat(s.id, BgJob("j", "gradle", 4242L, now.get(), lastBeat = now.get()))
            now.addAndGet(30 * min)
            m.sweepSilent()
            assertEquals(0, flagged.size)
        } finally {
            m.shutdown()
        }
    }

    @Test fun `a BUSY record with no run and no live pid is marked exited with the sweeper named`() {
        val m = manager()
        try {
            val ghostId = m.startNew("work").id
            // The daemon lost the run (as after a failed kill): the record still says BUSY, nothing runs, no pid.
            val runs = SessionManager::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(m) as MutableMap<*, *>
            runs.remove(ghostId)
            val before = m.get(ghostId)!!
            assertEquals(SessionState.BUSY, before.state, "precondition: reconcile keeps the ghost BUSY")
            now.addAndGet(25 * min)
            m.sweepSilent()
            val after = m.get(ghostId)!!
            assertEquals(SessionState.EXITED, after.state)
            assertEquals("silent sweeper", after.killedBy)
            assertEquals(0, flagged.size, "ended, not flagged")
        } finally {
            m.shutdown()
        }
    }
}
