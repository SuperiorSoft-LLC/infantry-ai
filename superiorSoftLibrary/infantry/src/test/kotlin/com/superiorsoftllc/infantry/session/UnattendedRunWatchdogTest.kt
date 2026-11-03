package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnattendedRunWatchdogTest {
    @Test
    fun `human started sessions are outside the watchdog`() {
        val run = UnattendedRunWatchdog.Run(startedAt = 0, lastHeartbeat = 0, unattended = false)

        assertNull(UnattendedRunWatchdog.expiry(run, now = 120_000, deadlineMs = 60_000, stallMs = 15_000))
    }

    @Test
    fun `stall is based on last heartbeat and checked before the longer deadline`() {
        val run = UnattendedRunWatchdog.Run(startedAt = 0, lastHeartbeat = 10_000, unattended = true)

        assertNull(UnattendedRunWatchdog.expiry(run, now = 24_999, deadlineMs = 60_000, stallMs = 15_000))
        assertEquals(WatchdogExpiry.STALLED, UnattendedRunWatchdog.expiry(run, now = 25_000, deadlineMs = 60_000, stallMs = 15_000))
    }

    @Test
    fun `deadline catches active runs that continue to produce output`() {
        val run = UnattendedRunWatchdog.Run(startedAt = 0, lastHeartbeat = 59_000, unattended = true)

        assertEquals(WatchdogExpiry.DEADLINE, UnattendedRunWatchdog.expiry(run, now = 60_000, deadlineMs = 60_000, stallMs = 15_000))
    }

    @Test
    fun `manager stops unattended run once and reports before kill`() {
        val now = AtomicLong(0)
        val stopped = CountDownLatch(1)
        val attention = mutableListOf<WatchdogExpiry>()
        val process = HangingProcess(stopped)
        val manager = SessionManager(
            provider = FakeProvider(),
            runner = HangingRunner(process),
            store = JsonSessionStore(File.createTempFile("watchdog", ".json").apply { delete() }),
            killGraceMs = 10,
            clock = now::get,
            deadlineMs = 100,
            stallMs = 50,
            watchdogPollMs = 5,
        )
        manager.watchdogSink = { _, expiry ->
            assertTrue(process.isAlive, "attention is raised before the run is stopped")
            attention += expiry
        }
        try {
            val session = manager.startNew("automated work", unattended = true)
            now.set(50)

            assertTrue(stopped.await(2, TimeUnit.SECONDS), "watchdog should stop a stalled process")
            assertEquals(SessionState.EXITED, manager.get(session.id)?.state)
            assertEquals(listOf(WatchdogExpiry.STALLED), attention)
            assertTrue(process.killed)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `a registered alive signal suppresses the stall but not the deadline, and a retry is due once`() {
        val run = UnattendedRunWatchdog.Run(startedAt = 0, lastHeartbeat = 0, unattended = true)
        assertNull(UnattendedRunWatchdog.expiry(run, now = 59_000, deadlineMs = 60_000, stallMs = 15_000, alive = true))
        assertEquals(WatchdogExpiry.DEADLINE, UnattendedRunWatchdog.expiry(run, now = 60_000, deadlineMs = 60_000, stallMs = 15_000, alive = true))
        assertEquals(false, UnattendedRunWatchdog.retryDue(0, 999, 100, false))
        assertEquals(false, UnattendedRunWatchdog.retryDue(50, 149, 100, false))
        assertEquals(true, UnattendedRunWatchdog.retryDue(50, 150, 100, false))
        assertEquals(false, UnattendedRunWatchdog.retryDue(50, 150, 100, true))
    }

    @Test
    fun `a live gate run for the ticket keeps a silent worker alive, then the kill names the watchdog`() {
        val now = AtomicLong(0)
        val stopped = CountDownLatch(1)
        val tmp = File.createTempFile("gate", "").apply { delete(); mkdirs() }
        val lockDir = File(tmp, "infantry-gate.lock").apply { mkdirs() }
        File(lockDir, "owner.txt").writeText("ticket=ticket-abc session=x pid=77")
        val holderAlive = java.util.concurrent.atomic.AtomicBoolean(true)
        val manager = SessionManager(
            provider = FakeProvider(), runner = HangingRunner(HangingProcess(stopped)),
            store = JsonSessionStore(File.createTempFile("watchdog", ".json").apply { delete() }),
            killGraceMs = 10, clock = now::get, deadlineMs = 100, stallMs = 50, watchdogPollMs = 5,
            bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(ticketDeadlineMs = 100_000, gateLock = GateLock(dir = { lockDir }, pidAlive = { holderAlive.get() })),
        )
        try {
            val session = manager.startNew("gate", unattended = true, ticketId = "ticket-abc")
            now.set(500) // past the stall AND the plain deadline: neither applies
            Thread.sleep(150)
            assertTrue(manager.get(session.id)?.state != SessionState.EXITED, "still running")
            holderAlive.set(false)
            assertTrue(stopped.await(2, TimeUnit.SECONDS), "with the gate run gone the stall applies")
            val ended = manager.get(session.id)!!
            assertEquals(SessionState.EXITED, ended.state)
            assertEquals("watchdog", ended.killedBy)
            assertTrue(ended.killReason!!.startsWith("stalled: no output"), ended.killReason)
        } finally {
            manager.shutdown()
        }
    }

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
        override fun sessionIdFromLine(line: String): String? = null
    }

    private class HangingRunner(private val process: HangingProcess) : ProcessRunner {
        override val id = "hanging"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = process.also { it.listener = listener }
    }

    private class HangingProcess(private val stopped: CountDownLatch) : RunningProcess {
        lateinit var listener: ProcessListener
        @Volatile var killed = false
        @Volatile private var alive = true
        override val pid: Long? = null
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() {
            killed = true
            alive = false
            listener.onExit(137)
            stopped.countDown()
        }
        override fun awaitExit(): Int = 137
    }
}
