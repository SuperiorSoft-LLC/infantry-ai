package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.context.ComposedContext
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedProcess
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEADLOCK-1 (ticket f3389c81): a worker's turn ending while a ticket is started froze the daemon. The turn end held the
 * SessionManager lock and ran the event listeners under it; one of them (FinishNoticeMonitor's idle comment) read the board,
 * while the start held the board lock in `handOut` and asked the manager for `startNew`. Here a listener that reads the board
 * stands in for that one, and the two paths race [ROUNDS] times. Every event must also reach the sink with the manager's lock free.
 */
class SessionBoardDeadlockTest {

    private val dir: File = Files.createTempDirectory("infantry-deadlock").toFile()
    private val opened = mutableListOf<SqlInfantryStore>()
    private val boards = mutableListOf<TicketBoard>()
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "deadlock-test").also { it.isDaemon = true } }

    /** Set when a round hung: its locks are held by stuck threads, so closing the board would hang the test JVM too. */
    @Volatile private var hung = false

    @AfterTest
    fun cleanUp() {
        pool.shutdownNow()
        if (hung) return
        boards.forEach { runCatching { it.close() } }
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val board: TicketBoard, val manager: SessionManager, val scripted: ProcessRunner, val profiles: AgentProfiles) {
        val runner: ScriptedRunner get() = scripted as ScriptedRunner
        val underLock = CopyOnWriteArrayList<String>()
        /** Extra work a test runs inside the sink, after the board read (a script hook calling tools from a listener). */
        @Volatile var inListener: (SessionEvent) -> Unit = {}
        val boardReads = AtomicInteger()
        /** A finished round's ticket goes to done and off the board: every list and hand-out reads the whole board, so it stays small. */
        fun retire(ticketId: String) { board.move(TicketMove(ticketId, DONE)); board.delete(ticketId) }

        fun ticket(n: Int): String {
            val worker = profiles.save(AgentProfile(name = "Worker $n", role = "backend"))
            return board.save(TicketSave(title = "Ticket $n", agentId = worker.id)).ticket.id
        }
    }

    private fun rig(runner: ProcessRunner = ScriptedRunner(autoExit = false)): Rig {
        val store = SqlInfantryStore.open(File(dir, "infantry-${opened.size}.db").absolutePath).also { opened += it }
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(store.agents, manager)
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        var n = 0
        val board = TicketBoard(
            store, idFactory = { "t${n++}" }, context = prompting, agents = profiles, sessions = manager, exitBehavior = { "leave" },
        ).also { boards += it }
        val rig = Rig(board, manager, runner, profiles)
        // The daemon's own route: session events reach the board through the BoardSeam (a SwapFeed), as DaemonMain wires it.
        // The live run of DEADLOCK-1 froze on that seam's lock too, so the rig goes through it.
        val seam = com.superiorsoftllc.infantry.daemon.BoardSeam<com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost, SessionEvent>({ h, e -> h.publish(e) })
        seam.attach(board.localHost!!)
        manager.eventSink = { event ->
            if (Thread.holdsLock(manager)) rig.underLock += event.javaClass.simpleName
            seam.emit(event)
            // The listener that closed the cycle: a turn end that reads the board (FinishNoticeMonitor.idleComment did).
            if (event is SessionEvent.Exit) { board.list(); rig.boardReads.incrementAndGet() }
            rig.inListener(event)
        }
        return rig
    }

    @Test
    fun aTurnEndAndATicketStartRacingNeverDeadlock() {
        val rig = rig()
        var ticket = rig.ticket(0)
        assertEquals("started", rig.board.move(TicketMove(ticket, IN_PROGRESS)).ticket.start?.reason)
        var running: ScriptedProcess = rig.runner.processes.last()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS)
        for (round in 1..ROUNDS) {
            val next = rig.ticket(round)
            val ending = running
            val barrier = CyclicBarrier(2)
            val turnEnd = pool.submit { barrier.await(); ending.exit(0) }
            val start = pool.submit<String?> { barrier.await(); rig.board.move(TicketMove(next, IN_PROGRESS)).ticket.start?.reason }
            val left = deadline - System.nanoTime()
            val started = try {
                turnEnd.get(left.coerceAtLeast(1), TimeUnit.NANOSECONDS)
                start.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                hung = true
                val stuck = ManagementFactory.getThreadMXBean().findDeadlockedThreads()?.size ?: 0
                fail("round $round of $ROUNDS did not finish within $TIME_LIMIT_SECONDS s; deadlocked threads: $stuck")
            }
            assertEquals("started", started, "round $round: the ticket start went through")
            running = rig.runner.processes.last()
            rig.retire(ticket)
            ticket = next
        }
        assertNull(ManagementFactory.getThreadMXBean().findDeadlockedThreads(), "no thread is deadlocked")
        assertTrue(rig.boardReads.get() > 0, "the board-reading listener ran")
        assertEquals(emptyList(), rig.underLock, "no event reached the sink while the SessionManager lock was held")
    }

    @Test
    fun everySessionEventReachesTheSinkWithTheManagerLockFree() {
        val rig = rig()
        val m = rig.manager
        val seen = CopyOnWriteArrayList<String>()
        val inner = m.eventSink!!
        m.eventSink = { e -> seen += e.javaClass.simpleName; inner(e) }
        // Through the board (handOut -> startNew under the board lock), then the manager's own paths.
        rig.board.move(TicketMove(rig.ticket(0), IN_PROGRESS))
        rig.runner.processes.last().say("working")
        rig.runner.processes.last().exit(0)
        val pod = m.startNew("hello")
        rig.runner.processes.last().exit(1)
        m.resume(pod.id, "again")
        m.interrupt(pod.id)
        m.resume(pod.id, "and again")
        m.kill(pod.id, force = true)
        m.delete(pod.id)
        assertTrue(seen.containsAll(listOf("Status", "Turn", "Stdout", "Exit")), "the run produced its events: $seen")
        assertEquals(emptyList(), rig.underLock, "no event reached the sink while the SessionManager lock was held")
    }

    /** A harness whose runs stay busy until the test ends them; each thread sees the process its own last start made. */
    private class ThreadRunner : ProcessRunner {
        override val id = "fake"
        private val n = AtomicInteger()
        val lastHere = ThreadLocal<ScriptedProcess?>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            listener.onStdout("{\"session_id\":\"prov-${n.incrementAndGet()}\"}")
            return ScriptedProcess(listener).also { lastHere.set(it) }
        }
    }

    /** The finishing worker's session, its ticket, the ticket a done hook starts next, and (once started) that worker's run. */
    private class Handover(val sessionId: String, val ticketId: String, val nextTicketId: String) {
        @Volatile var nextRun: ScriptedProcess? = null
        @Volatile var startReason: String? = null
    }

    /**
     * Owner 2026-11-03 (comment 4060ba35): scripts stay unlimited, so any tool call from any context must be safe. A done hook runs
     * INSIDE the turn-end listener (ticket to done, then ticket_start of the next), while at the same moment another caller wakes
     * the PM (chat_note with wake = a resume) and another removes a session (session_remove = kill + delete).
     */
    @Test
    fun aDoneHookThatStartsRemovesAndWakesFromTheListenerNeverDeadlocks() {
        val runner = ThreadRunner()
        val rig = rig(runner)
        val m = rig.manager
        val pm = m.startNew("pm")
        runner.lastHere.get()!!.exit(0)
        val pending = AtomicReference<Handover?>()
        rig.inListener = { e ->
            val h = pending.get()
            if (e is SessionEvent.Exit && h != null && e.sessionId == h.sessionId && pending.compareAndSet(h, null)) {
                rig.board.move(TicketMove(h.ticketId, DONE))
                h.startReason = rig.board.move(TicketMove(h.nextTicketId, IN_PROGRESS)).ticket.start?.reason
                h.nextRun = runner.lastHere.get()
            }
        }
        var ticket = rig.ticket(0)
        assertEquals("started", rig.board.move(TicketMove(ticket, IN_PROGRESS)).ticket.start?.reason)
        var running: ScriptedProcess = runner.lastHere.get()!!
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIME_LIMIT_SECONDS)
        for (round in 1..HOOK_ROUNDS) {
            val next = rig.ticket(round)
            val h = Handover(rig.board.get(ticket).sessionId!!, ticket, next)
            pending.set(h)
            val ending = running
            val barrier = CyclicBarrier(3)
            val jobs = listOf(
                pool.submit { barrier.await(); ending.exit(0) },
                pool.submit { barrier.await(); runCatching { m.resume(pm.id, "wake: round $round") }; runCatching { m.interrupt(pm.id) } },
                pool.submit {
                    barrier.await()
                    val scratch = m.startNew("scratch $round")
                    runCatching { m.kill(scratch.id, force = true) }
                    runCatching { m.delete(scratch.id) }
                },
            )
            try {
                jobs.forEach { it.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
            } catch (e: java.util.concurrent.TimeoutException) {
                hung = true
                val stuck = ManagementFactory.getThreadMXBean().findDeadlockedThreads()?.size ?: 0
                fail("round $round of $HOOK_ROUNDS did not finish within $TIME_LIMIT_SECONDS s; deadlocked threads: $stuck")
            }
            assertEquals("started", h.startReason, "round $round: the hook's ticket_start went through")
            assertEquals(DONE, rig.board.get(ticket).status, "round $round: the finished ticket is done")
            running = h.nextRun ?: fail("round $round: the next worker has no run")
            rig.board.delete(ticket)
            ticket = next
        }
        assertNull(ManagementFactory.getThreadMXBean().findDeadlockedThreads(), "no thread is deadlocked")
        assertEquals(emptyList(), rig.underLock, "no event reached the sink while the SessionManager lock was held")
    }

    private companion object {
        const val ROUNDS = 500
        const val HOOK_ROUNDS = 200
        const val TIME_LIMIT_SECONDS = 120L
    }
}
