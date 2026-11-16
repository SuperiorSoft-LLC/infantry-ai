// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.mvp.ProjectBaseCheck
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLink
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.protocol.TicketStatus.PENDING
import com.superiorsoftllc.infantry.protocol.TicketStatus.TODO
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.usage.GateDecision
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import com.superiorsoftllc.infantry.worktree.TicketBranches
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.5.0 (story 9.2): the backlog's rules, over the real SQLite store.
 *
 * Columns and positions are the daemon's (contiguous, 0 at the top, new and daemon-moved tickets at the
 * bottom); `pending` is derived from `blocked_by` links and never set by hand; won't fix does not release;
 * cycles and self-links are refused; every committed change reaches [TicketBoard.eventSink], and a refusal
 * writes nothing.
 */
class TicketBoardTest {

    private val dir: File = Files.createTempDirectory("infantry-backlog").toFile()
    private val dbPath = File(dir, "infantry.db").absolutePath
    private val opened = mutableListOf<SqlInfantryStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openStore(): SqlInfantryStore = SqlInfantryStore.open(dbPath).also { opened += it }

    /** A clock that ticks once per operation, and ids `a`, `b`, `c`... in creation order. */
    private fun board(store: SqlInfantryStore = openStore()): Pair<TicketBoard, MutableList<BoardEvent>> {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        var tick = 0L
        val events = mutableListOf<BoardEvent>()
        val board = TicketBoard(store, clock = { ++tick }, idFactory = { ids.next() })
        board.eventSink = { events += it }
        return board to events
    }

    private fun TicketBoard.new(title: String, type: String? = null): Ticket =
        save(TicketSave(title = title, ticketType = type)).ticket

    private fun TicketBoard.link(a: String, type: String, b: String) = link(TicketLinkRequest(a, b, type))
    private fun TicketBoard.move(id: String, status: String? = null, position: Int? = null, wontFix: Boolean? = null) =
        move(TicketMove(id, status, position, wontFix))

    private fun TicketBoard.column(status: String): List<String> = list().filter { it.status == status }.map { it.id }

    /** Positions run 0, 1, 2... in every column, and the list is in board order. */
    private fun assertContiguous(board: TicketBoard) {
        val tickets = board.list()
        for ((status, column) in tickets.groupBy { it.status }) {
            assertEquals((0 until column.size).toList(), column.map { it.position }, "column $status is not contiguous")
        }
        val order = listOf(TODO, IN_PROGRESS, PENDING, DONE)
        assertEquals(tickets.sortedBy { order.indexOf(it.status) }.map { it.id }, tickets.map { it.id }, "board order")
    }

    // --- creating and editing ------------------------------------------------------------------------

    @Test
    fun aNewTicketGoesToTheBottomOfTodoAndIsStored() {
        val (board, _) = board()
        val a = board.new("  Add login  ", type = "Enhancement")
        board.new("Fix the build")
        board.new("Write the docs")

        assertEquals("a", a.id)
        assertEquals("Add login", a.title, "trimmed")
        assertEquals("enhancement", a.ticketType, "normalised to the wire's lowercase")
        assertEquals("", a.description)
        assertEquals(TODO, a.status)
        assertEquals(a.createdAt, a.updatedAt)
        assertFalse(a.wontFix)
        assertEquals(listOf("a", "b", "c"), board.column(TODO))
        assertContiguous(board)

        // The board is the database's: a fresh store over the same file sees the same tickets.
        val (reopened, _) = board(openStore())
        assertEquals(board.list(), reopened.list())
    }

    // --- hand-out and session events -----------------------------------------------------------------

    /**
     * H1, lock order: the board lock is taken before the session manager's, never after. A session event is
     * emitted while the session manager holds its own lock, so it must not wait for the board, even when it
     * lands in the middle of a ticket hand-out (which holds the board lock and then needs the session
     * manager's). The latches hold each side at that point, so the interleaving is the same on every run.
     */
    @Test
    fun aHandOutCompletesWhileAnotherSessionsEventArrives() {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = dir,
            bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket

        // The hand-out reaches the launch gate inside the board lock; the other session's event is emitted
        // inside the session manager's lock. Each side waits until the other one holds its lock.
        val inHandOut = CountDownLatch(1)
        val eventEmitted = CountDownLatch(1)
        manager.launchGate = { _, _, _ ->
            inHandOut.countDown()
            eventEmitted.await(2, TimeUnit.SECONDS)
            GateDecision.Allow
        }
        manager.eventSink = { event ->
            eventEmitted.countDown()
            board.localHost?.publish(event) // the daemon's fourth arm
        }

        var answer: TicketAnswer? = null
        val handOut = Thread { answer = board.move(ticket.id, IN_PROGRESS) }.apply { isDaemon = true }
        val otherSession = Thread {
            inHandOut.await(2, TimeUnit.SECONDS)
            manager.startNew("another session's work")
        }.apply { isDaemon = true }
        handOut.start()
        otherSession.start()

        handOut.join(2_000)
        assertEquals(0L, eventEmitted.count, "the other session's event never reached the session manager's sink")
        assertFalse(
            handOut.isAlive,
            "handOut did not complete within 2 s (handOut ${handOut.state}, other session ${otherSession.state})",
        )
        val started = checkNotNull(answer) { "handOut returned no answer" }
        assertEquals(IN_PROGRESS, started.ticket.status)
        assertEquals("started", started.ticket.start?.reason)
    }

    /**
     * BUG-TriggerRunIds: the hand-out spawns the session BEFORE the commit that records it (the commit needs the
     * session id), so a commit that throws -- SQLITE_BUSY_SNAPSHOT, a full disk -- must not leave that session
     * running against a ticket the board still files as todo. The session is killed and the failure rethrown.
     */
    @Test
    fun aHandOutWhoseCommitThrowsLeavesNoLiveSession() {
        val real = openStore()
        var failTransactions = false
        val store = object : com.superiorsoftllc.infantry.persistence.InfantryStore by real {
            override fun <T> transaction(block: () -> T): T {
                if (failTransactions) throw IllegalStateException("forced commit failure")
                return real.transaction(block)
            }
            // The commit is a write-first transaction (BUG-BoardCommitImmediate); it must fail the same way.
            override fun <T> writeTransaction(block: () -> T): T {
                if (failTransactions) throw IllegalStateException("forced commit failure")
                return real.writeTransaction(block)
            }
        }
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(real.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        // A non-blank prompt, so startNew starts a run (a blank one leaves the session IDLE, never live).
        val prompting = object : com.superiorsoftllc.infantry.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.infantry.context.ContextInput) =
                com.superiorsoftllc.infantry.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.infantry.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket
        // The launch gate runs inside startNew, after every read the hand-out makes: the next transaction is its commit.
        manager.launchGate = { _, _, _ -> failTransactions = true; GateDecision.Allow }

        val thrown = runCatching { board.move(ticket.id, IN_PROGRESS) }.exceptionOrNull()

        failTransactions = false
        assertEquals("forced commit failure", thrown?.message, "the commit failure must surface to the caller")
        val spawned = manager.list().filter { it.ticketId == ticket.id }
        assertEquals(1, spawned.size, "the hand-out must have spawned exactly one session before its commit")
        assertFalse(spawned.single().live, "the commit failed, yet the session it spawned is still live: ${spawned.single().state}")
        assertEquals(TODO, board.list().single { it.id == ticket.id }.status)
    }

    /**
     * P9.9, the H1 lock order under repetition. A hand-out holds the board monitor and then takes the
     * session manager's (its `startNew`), while a session exit is emitted from inside the session
     * manager's monitor (its `finish`). The exit must therefore never wait for the board: the daemon's
     * fourth arm queues it to the board's own session-events thread when the emitting thread holds the
     * manager lock. Without that defer the two threads deadlock every time the interleaving lands --
     * the hand-out holds the board and wants the manager, the exit holds the manager and wants the
     * board -- so this runs the race fifty times, each iteration bounded to 5 s so a hang is a failure
     * and not a stuck suite.
     */
    @Test
    fun aHandOutRacingAnotherSessionsExitCompletesFiftyTimes() {
        var uid = 0
        repeat(50) { iteration ->
            // One store per iteration: the queued exit bookkeeping runs on the board's own thread and
            // writes runs, so iterations sharing one SQLite file would race each other's transactions.
            val store = SqlInfantryStore.open(File(dir, "race-$iteration.db").absolutePath).also { opened += it }
            val runner = ScriptedRunner(autoExit = false)
            val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
            val profiles = AgentProfiles(store.agents, manager)
            val worker = profiles.save(AgentProfile(name = "Worker-$iteration", role = "backend"))
            val otherWorker = profiles.save(AgentProfile(name = "Other-$iteration", role = "backend"))
            val board = TicketBoard(store, idFactory = { "t${uid++}" }, agents = profiles, sessions = manager)
            val ticket = board.save(TicketSave(title = "Ticket $iteration", agentId = worker.id)).ticket
            val otherTicket = board.save(TicketSave(title = "Other $iteration", agentId = otherWorker.id)).ticket

            // The other session: another ticket's stub run, kept BUSY until the race ends it.
            val otherSession = manager.startNew(
                prompt = "another session's work",
                agentId = otherWorker.id,
                role = otherWorker.role,
                ticketId = otherTicket.id,
            )
            val otherProcess = runner.processes.single()

            // The hand-out reaches the launch gate inside the board lock; the exit is emitted inside
            // the session manager's lock. Each side waits until the other one holds its lock.
            val inHandOut = CountDownLatch(1)
            val exitFired = CountDownLatch(1)
            val done = CountDownLatch(2)
            manager.launchGate = { _, _, _ ->
                inHandOut.countDown()
                exitFired.await(5, TimeUnit.SECONDS)
                GateDecision.Allow
            }
            manager.eventSink = { event ->
                if (event is SessionEvent.Exit) exitFired.countDown()
                board.localHost?.publish(event) // the daemon's fourth arm
            }

            var answer: TicketAnswer? = null
            val handOut = Thread({
                runCatching { answer = board.move(ticket.id, IN_PROGRESS) }
                done.countDown()
            }, "hand-out-$iteration").apply { isDaemon = true }
            val otherExit = Thread({
                inHandOut.await(5, TimeUnit.SECONDS)
                runCatching { otherProcess.exit(0) }
                done.countDown()
            }, "session-exit-$iteration").apply { isDaemon = true }
            handOut.start()
            otherExit.start()

            assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "iteration $iteration: hand-out and session exit did not both complete within 5 s " +
                    "(handOut ${handOut.state}, otherExit ${otherExit.state})",
            )
            assertEquals(0L, exitFired.count, "iteration $iteration: the exit event never reached the sink")
            // The queued exit books the other ticket's run row on the board's own thread. Waiting for
            // that finished row (bounded) both proves the deferred work landed instead of disappearing
            // into the queue, and keeps its writes from leaking into the next iteration's transactions.
            val finishedRunRow = {
                store.ticketRuns.byTicket(otherTicket.id).any { it.sessionId == otherSession.id && it.endedAt != null }
            }
            val bookDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!finishedRunRow() && System.nanoTime() < bookDeadline) Thread.sleep(10)
            assertTrue(finishedRunRow(), "iteration $iteration: the exited session's run row was never finished")
            val started = checkNotNull(answer) { "iteration $iteration: the hand-out returned no answer" }
            assertEquals(IN_PROGRESS, started.ticket.status, "iteration $iteration")
            assertEquals("started", started.ticket.start?.reason, "iteration $iteration")
        }
    }

    /**
     * BUG-BoardCommitImmediate: a board commit reads the whole board and then writes. Run as a DEFERRED transaction
     * that read takes only a snapshot, so a writer on another connection that commits between the read and the write
     * makes the snapshot impossible to upgrade: SQLITE_BUSY_SNAPSHOT, immediately, and busy_timeout does not apply.
     * The commit must take the write lock FIRST, so the competing writer WAITS (busy_timeout) instead of winning and
     * aborting the commit. Forced, not hoped for: the competing write is fired from inside the commit's load.
     */
    @Test
    fun aConcurrentWriterBetweenTheCommitsLoadAndItsWriteDoesNotAbortIt() {
        val real = openStore()
        val other = openStore() // another connection on the same file: the "another trigger / usage write" writer
        var armed = false
        var competing: Thread? = null
        var competingFinishedInsideLoad = false
        var competingFailure: Throwable? = null
        val store = object : com.superiorsoftllc.infantry.persistence.InfantryStore by real {
            override val tickets = object : com.superiorsoftllc.infantry.persistence.TicketRepository by real.tickets {
                override fun all(): List<com.superiorsoftllc.infantry.persistence.TicketRow> {
                    val rows = real.tickets.all()
                    if (armed) {
                        armed = false
                        val writer = Thread {
                            try {
                                other.settings.put(com.superiorsoftllc.infantry.persistence.SettingRow("probe", "\"x\"", 1L))
                            } catch (t: Throwable) {
                                competingFailure = t
                            }
                        }
                        competing = writer
                        writer.start()
                        // Give it the whole window to commit between this read and the commit's write.
                        writer.join(750)
                        competingFinishedInsideLoad = !writer.isAlive
                    }
                    return rows
                }
            }
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, clock = { 1L }, idFactory = { ids.next() })
        board.new("First")

        armed = true
        val saved = runCatching { board.new("Second") }

        competing!!.join(10_000)
        assertNull(saved.exceptionOrNull(), "a concurrent writer between load and write must not abort the board commit: ${saved.exceptionOrNull()}")
        assertFalse(competingFinishedInsideLoad, "the competing writer committed inside the commit's read: the commit did not hold the write lock")
        assertNull(competingFailure, "the competing writer waits for the lock and then succeeds: $competingFailure")
        assertEquals(listOf("First", "Second"), board.list().map { it.title })
        assertEquals("\"x\"", real.settings.get("probe")?.value)
    }

    @Test
    fun aRoleTriggerStartsATodoTicketAssignedToAProfileOfThatRole() {
        val store = openStore()
        val runner = ScriptedRunner(autoExit = false)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val prompting = object : com.superiorsoftllc.infantry.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.infantry.context.ContextInput) =
                com.superiorsoftllc.infantry.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.infantry.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager)
        board.save(TicketSave(title = "Add login", agentId = worker.id))

        assertNull(board.startNextUnattended("role:frontend"), "another role's trigger must skip it")
        assertEquals("started", board.startNextUnattended("role:backend")?.ticket?.start?.reason)
        board.close()
    }

    // --- BUG-E2E-2: a finished run decides the ticket's column ------------------------------------------

    /** One assistant text line, the work a briefed worker shows. */
    private val assistantText = "{\"type\":\"text\",\"text\":\"Done: login shipped.\"}"

    /**
     * Starts one ticket on a scripted session that prints [output] (after the provider session id, when [providerSession]),
     * ends that session with [exitCode], and answers the ticket afterwards; [sweepFlags] gets what a worker sweep then flags.
     */
    private fun ticketAfterRunExits(
        exitCode: Int,
        exitBehavior: String = "done",
        output: List<String> = listOf(assistantText),
        providerSession: Boolean = true,
        minOutputs: Long = 1,
        sweepFlags: MutableList<String>? = null,
    ): Ticket {
        val store = openStore()
        val runner = ScriptedRunner(autoExit = false, emitSessionId = providerSession)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy())
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        // A non-blank prompt, so startNew starts a run (a blank one leaves the session IDLE, never live).
        val prompting = object : com.superiorsoftllc.infantry.context.TicketContext {
            override fun compose(input: com.superiorsoftllc.infantry.context.ContextInput) =
                com.superiorsoftllc.infantry.context.ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): com.superiorsoftllc.infantry.protocol.EffectiveContext? = null
        }
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager,
            exitBehavior = { exitBehavior }, autoDoneMinOutputs = { minOutputs },
        )
        manager.eventSink = { board.localHost?.publish(it) } // the daemon's fourth arm
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id)).ticket
        assertEquals("started", board.move(ticket.id, IN_PROGRESS).ticket.start?.reason)

        val process = runner.processes.single()
        output.forEach(process::say)
        process.exit(exitCode)

        // The exit is applied on the board's own session-events thread: wait (bounded) for the finished run.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (board.get(ticket.id).lastRun == null && System.nanoTime() < deadline) Thread.sleep(10)
        board.close()
        sweepFlags?.let { flags -> board.sweepWorkers(graceMs = 0) { _, _, detail -> flags += detail } }
        return board.get(ticket.id)
    }

    @Test
    fun aSucceededRunMovesItsStillInProgressTicketToDone() {
        val after = ticketAfterRunExits(0)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(DONE, after.status, "a SUCCEEDED run must not leave its ticket in_progress forever")
        assertTrue(
            after.lastRun?.detail.orEmpty().contains("moved to done"),
            "the ticket must say why the daemon moved it: '${after.lastRun?.detail}'",
        )
    }

    // --- GAP 855db590: a silent clean exit is not work done -----------------------------------------------

    @Test
    fun aSilentFirstTurnThatExitsCleanlyLeavesItsTicketInProgressAndIsFlagged() {
        val flags = mutableListOf<String>()
        val after = ticketAfterRunExits(0, output = emptyList(), sweepFlags = flags)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "exit 0 with no output is not work done: the ticket must stay in_progress")
        assertTrue(after.lastRun?.detail.orEmpty().startsWith(TicketBoard.NO_WORK_DETAIL), "lastRun says why: '${after.lastRun?.detail}'")
        assertTrue(after.lastRun?.detail.orEmpty().contains("0 assistant output(s)"), "'${after.lastRun?.detail}'")
        assertEquals(1, flags.size, "the sweep flags the briefed worker that did no work: $flags")
        assertTrue(flags.single().contains("done no work"), flags.single())
    }

    @Test
    fun aCleanExitThatNeverOpenedAProviderSessionLeavesItsTicketInProgress() {
        val after = ticketAfterRunExits(0, output = emptyList(), providerSession = false)

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "an unbriefed worker (no provider session id) never moves its ticket to done")
        assertTrue(after.lastRun?.detail.orEmpty().contains("never opened a provider session"), "'${after.lastRun?.detail}'")
    }

    @Test
    fun outputWithoutAProviderSessionIsStillNotWorkDone() {
        val after = ticketAfterRunExits(0, providerSession = false)

        assertEquals(IN_PROGRESS, after.status, "output alone, with no provider session, does not count")
    }

    @Test
    fun theWorkThresholdIsConfigurable() {
        val belowThree = ticketAfterRunExits(0, output = listOf(assistantText, assistantText), minOutputs = 3)
        assertEquals(IN_PROGRESS, belowThree.status, "2 outputs are below a threshold of 3")
        assertTrue(belowThree.lastRun?.detail.orEmpty().contains("2 assistant output(s)"), "'${belowThree.lastRun?.detail}'")

        val toolCall = "{\"type\":\"tool_use\",\"name\":\"Bash\"}"
        assertEquals(DONE, ticketAfterRunExits(0, output = listOf(assistantText, toolCall, assistantText), minOutputs = 3).status, "a tool call counts as work")

        assertEquals(DONE, ticketAfterRunExits(0, output = emptyList(), minOutputs = 0).status, "0 = any clean exit counts (the old rule)")
    }

    @Test
    fun aSucceededRunLeavesItsTicketInProgressUnderTheDefaultExitBehavior() {
        val after = ticketAfterRunExits(0, exitBehavior = "leave")

        assertEquals("SUCCEEDED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "tickets.exitBehavior=leave keeps a clean exit in_progress")
    }

    @Test
    fun aFailedRunLeavesItsTicketInProgressWithTheFailureOnLastRun() {
        val after = ticketAfterRunExits(1)

        assertEquals("FAILED", after.lastRun?.outcome)
        assertEquals(IN_PROGRESS, after.status, "a FAILED run stays in_progress for a human to look at")
    }

    // --- BUG-WT-1: a worker never runs in the project's own git checkout ---------------------------------

    private fun noRepoBoard(projectFolder: File, useWorktree: Boolean? = null): Triple<TicketBoard, SessionManager, String> {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = projectFolder,
            bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, agents = profiles, sessions = manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = worker.id, useWorktree = useWorktree)).ticket
        return Triple(board, manager, ticket.id)
    }

    @Test
    fun noRepoConfiguredAndAGitProjectFolderPutsTheWorkerOnItsTicketBranch() {
        val checkout = newGitRepo("infantry-bugwt1")
        val checkoutBranch = { testGit().run(checkout, listOf("branch", "--show-current")).stdout.trim() }
        val branchBefore = checkoutBranch()
        try {
            val (board, manager, id) = noRepoBoard(checkout)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertTrue(
                manager.list().none { File(it.workingDir).absoluteFile == checkout.absoluteFile },
                "no worker runs in the project's own checkout: ${manager.list().map { it.workingDir }} (${answer.start})",
            )
            assertEquals("started", answer.start?.reason, "the start succeeds: ${answer.start}")
            val session = manager.get(answer.sessionId!!)!!
            val workingDir = File(session.workingDir).absoluteFile
            assertEquals(File(session.worktreePath!!).absoluteFile, workingDir, "the worker runs in its worktree")
            assertTrue(
                workingDir.startsWith(File(checkout, ".worktrees").absoluteFile),
                "the worktree is cut under the project folder's repo: $workingDir",
            )
            // The branch is named after the task by the one rule the configured-repo path uses.
            val expected = TicketBranches.branchFor(id, "Add login")
            val current = testGit().run(workingDir, listOf("branch", "--show-current")).stdout.trim()
            assertEquals(expected, current, "the worker's worktree is on the branch named after the task")
            assertEquals(expected, session.worktreeBranch)
            assertEquals(expected, board.get(id).branch, "the ticket records its branch")
            assertEquals(branchBefore, checkoutBranch(), "the project checkout's own branch is unchanged")
        } finally {
            checkout.deleteRecursively()
        }
    }

    /**
     * BUG-REGRESS-1: uncommitted changes in the project checkout never refuse a start. Since BUG-WT-1 a board with no
     * repo configured cuts every ticket's worktree from the git checkout it runs in, and the dirty-base refusal then
     * kept EVERY start of a checkout with a half-finished edit in To Do. The branch is cut from the base ref's commit,
     * so the edit stays in the checkout, and the start answer says what the branch was cut from.
     */
    @Test
    fun noRepoConfiguredAndADirtyGitProjectFolderStillStartsTheTicketOnItsBranch() {
        val checkout = newGitRepo("infantry-regress1")
        val head = testGit().run(checkout, listOf("rev-parse", "HEAD")).stdout.trim()
        // A tracked change nobody committed: someone's half-finished work in the project checkout.
        File(checkout, "README.md").writeText("half-written\n")
        try {
            val (board, manager, id) = noRepoBoard(checkout)
            val answer = board.move(id, IN_PROGRESS).ticket

            assertEquals("started", answer.start?.reason, "a dirty checkout never refuses a start: ${answer.start}")
            assertEquals(IN_PROGRESS, answer.status, "the started ticket is in progress")
            assertEquals(TicketBranches.branchFor(id, "Add login"), answer.branch, "the ticket is on its own branch")
            assertEquals(head, answer.base, "the branch is cut from the base ref's commit")
            val detail = answer.start?.detail.orEmpty()
            assertTrue(detail.contains("cut from HEAD ${head.take(8)}"), "the start answer names what it was cut from: $detail")
            val worktree = File(manager.get(answer.sessionId!!)!!.worktreePath!!)
            assertEquals("hello", File(worktree, "README.md").readText().trim(), "only committed work reaches the ticket branch")
            assertEquals("half-written", File(checkout, "README.md").readText().trim(), "the checkout keeps its own edit")
        } finally {
            checkout.deleteRecursively()
        }
    }

    @Test
    fun anExplicitUseWorktreeFalseOverAGitProjectFolderIsRefused() {
        val checkout = newGitRepo("infantry-bugwt1-false")
        try {
            val (board, manager, id) = noRepoBoard(checkout, useWorktree = false)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("git repo"), "the reason names the git repo: ${answer.start}")
            assertEquals(TODO, answer.status, "a refused start leaves the ticket where it was")
            assertEquals(0, manager.list().size)
        } finally {
            checkout.deleteRecursively()
        }
    }

    @Test
    fun noRepoConfiguredAndANonGitProjectFolderStillStartsInIt() {
        val folder = Files.createTempDirectory("infantry-bugwt1-plain").toFile()
        try {
            val (board, manager, id) = noRepoBoard(folder)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("started", answer.start?.reason)
            assertEquals(folder.absolutePath, manager.get(answer.sessionId!!)!!.workingDir)
        } finally {
            folder.deleteRecursively()
        }
    }

    // --- NOGIT-1: a project folder that is not a git repo runs in place, whatever checkout the daemon is in -----

    private fun projectDirBoard(
        daemonDir: File,
        projectDir: File,
        useWorktree: Boolean? = null,
        baseRef: TicketBaseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD),
    ): Triple<TicketBoard, SessionManager, String> {
        val store = openStore()
        val manager = SessionManager(
            FakeAgentProvider(),
            ScriptedRunner(autoExit = false),
            tempSessionStore(),
            killGraceMs = 50,
            defaultWorkingDir = daemonDir,
            bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
        )
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(
            store, idFactory = { ids.next() }, agents = profiles, sessions = manager, baseRef = baseRef,
            projectDirectory = { projectDir.path },
        )
        val ticket = board.save(
            TicketSave(title = "Add login", agentId = worker.id, projectId = "project-x", useWorktree = useWorktree),
        ).ticket
        return Triple(board, manager, ticket.id)
    }

    @Test
    fun aNonGitProjectDirectoryRunsInPlaceEvenWhenTheDaemonIsInAGitCheckout() {
        val daemonRepo = newGitRepo("infantry-nogit-daemon")
        val folder = Files.createTempDirectory("infantry-nogit-plain").toFile()
        try {
            val (board, manager, id) = projectDirBoard(daemonRepo, folder)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("started", answer.start?.reason, "the start succeeds: ${answer.start}")
            assertNull(answer.branch, "no branch is cut for a folder with no git repo")
            val session = manager.get(answer.sessionId!!)!!
            assertEquals(folder.absolutePath, File(session.workingDir).absolutePath, "the worker runs in the project folder")
            assertNull(session.worktreePath, "no worktree")
            assertFalse(File(daemonRepo, ".worktrees").exists(), "nothing is cut in the daemon's checkout")
            val branchList = testGit().run(daemonRepo, listOf("branch", "--list")).stdout.lines().count { it.isNotBlank() }
            assertEquals(1, branchList, "the daemon's repo gained no branch")
        } finally {
            daemonRepo.deleteRecursively()
            folder.deleteRecursively()
        }
    }

    @Test
    fun useWorktreeTrueOnANonGitProjectFolderNamesTheNonGitFolder() {
        val folder = Files.createTempDirectory("infantry-nogit-wt").toFile()
        try {
            val (board, _, id) = projectDirBoard(folder, folder, useWorktree = true)
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("is not a git repository"), "one clear reason: ${answer.start}")
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun aGitRepoWithNoCommitsIsRefusedWithAHaveNoCommitsReason() {
        val repo = Files.createTempDirectory("infantry-nogit-empty").toFile()
        try {
            testGit().run(repo, listOf("init"))
            val (board, _, id) = projectDirBoard(repo, repo, baseRef = TicketBaseRef.fixed(TicketBaseRef.NONE))
            val answer = board.move(id, IN_PROGRESS).ticket
            assertEquals("start_refused", answer.start?.reason)
            assertTrue(answer.start!!.detail!!.contains("no commits yet"), "names the cause: ${answer.start}")
            val check = ProjectBaseCheck(TicketBranches()) { repo }
            val failure = assertFailsWith<IllegalArgumentException> {
                check.check(Project(id = "project-x", name = "x", directory = repo.path), "main")
            }
            assertTrue(failure.message!!.contains("no commits yet"), failure.message)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun aBaseBranchOnANonGitProjectSaysBaseBranchesDoNotApply() {
        val folder = Files.createTempDirectory("infantry-nogit-base").toFile()
        try {
            val check = ProjectBaseCheck(TicketBranches()) { null }
            val failure = assertFailsWith<IllegalArgumentException> {
                check.check(Project(id = "project-x", name = "x", directory = folder.path), "main")
            }
            assertTrue(failure.message!!.contains("is not a git repository"), failure.message)
            assertFalse(failure.message!!.contains("INFANTRY_REPO"), "no misleading advice: ${failure.message}")
        } finally {
            folder.deleteRecursively()
        }
    }
}
