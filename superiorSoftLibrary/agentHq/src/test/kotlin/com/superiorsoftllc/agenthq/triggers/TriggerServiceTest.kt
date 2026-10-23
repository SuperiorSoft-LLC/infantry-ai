package com.superiorsoftllc.agenthq.triggers

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.backlog.PromptLaunch
import com.superiorsoftllc.agenthq.backlog.TicketAnswer
import com.superiorsoftllc.agenthq.persistence.AttentionRepository
import com.superiorsoftllc.agenthq.persistence.TriggerRepository
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStart
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerSave
import com.superiorsoftllc.agenthq.protocol.TriggerView
import com.superiorsoftllc.agenthq.session.SessionEvent
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerServiceTest {
    /**
     * Thread-safe like the SQLite repository it stands in for: every method holds this object's monitor. The
     * service's launch thread REPLACES an event row (a run's relabel) while `events()` / `list()` read without
     * the service lock, and SQLite replaces in one `INSERT OR REPLACE`, so a reader never sees the row missing.
     * Unsynchronized, the remove-then-add below handed a reader an empty list ("List is empty" at `.single()`)
     * or a ConcurrentModificationException. Same fix as WebhookIngressTest (2169237e).
     */
    private class MemoryTriggers : TriggerRepository {
        private val rows = LinkedHashMap<String, TriggerView>()
        private val eventRows = mutableListOf<TriggerEventView>()
        private val periods = HashMap<String, Long>()
        @Synchronized override fun upsert(trigger: TriggerView, intervalMs: Long?) {
            rows[trigger.id] = trigger
            if (intervalMs == null) periods.remove(trigger.id) else periods[trigger.id] = intervalMs
        }
        @Synchronized override fun get(id: String): TriggerView? = rows[id]
        @Synchronized override fun all(): List<TriggerView> = rows.values.toList()
        @Synchronized override fun intervals(): Map<String, Long> = periods.toMap()
        @Synchronized override fun delete(id: String): Boolean {
            periods.remove(id)
            return rows.remove(id) != null
        }
        @Synchronized override fun insertEvent(event: TriggerEventView, keep: Long) {
            eventRows.removeAll { it.eventId == event.eventId }
            eventRows += event
        }
        @Synchronized override fun events(triggerId: String): List<TriggerEventView> = eventRows.filter { it.triggerId == triggerId }
        private val webhookSecrets = HashMap<String, String>()
        @Synchronized override fun setWebhookSecret(triggerId: String, secret: String) { webhookSecrets[triggerId] = secret }
        @Synchronized override fun webhookSecret(triggerId: String): String? = webhookSecrets[triggerId]
        @Synchronized override fun deleteWebhookSecret(triggerId: String) { webhookSecrets.remove(triggerId) }
        private val pendingMails = HashMap<String, String>()
        @Synchronized override fun pendingMail(triggerId: String): String? = pendingMails[triggerId]
        @Synchronized override fun setPendingMail(triggerId: String, mailId: String?) { if (mailId == null) pendingMails.remove(triggerId) else pendingMails[triggerId] = mailId }
    }

    private class MemoryAttention : AttentionRepository {
        override fun insert(item: AttentionItem) {}
        override fun get(id: String): AttentionItem? = null
        override fun all(): List<AttentionItem> = emptyList()
        override fun acknowledge(id: String, at: Long): AttentionItem? = null
        override fun openCount(): Long = 0
    }

    @Test
    fun triggerViewEchoesIntervalMs() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000,
        )

        val saved = service.save(TriggerSave(name = "every minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0))

        assertEquals(60_000L, saved.intervalMs, "save() must echo the schedule period on the returned TriggerView")
        assertEquals(60_000L, service.list().single { it.id == saved.id }.intervalMs, "list() must echo the stored schedule period")
    }

    @Test
    fun `triggerSaveAcceptsScheduleKind`() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000,
        )

        val saved = service.save(TriggerSave(name = "nightly", kind = "schedule"))

        assertEquals("schedule", saved.kind)
        assertEquals("armed", saved.state)
    }

    @Test
    fun `triggerSaveAcceptsShipKind`() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000,
        )

        val saved = service.save(TriggerSave(name = "on ship", kind = "ship"))

        assertEquals("ship", saved.kind)
        assertEquals("armed", saved.state)
    }

    @Test
    fun `shipTriggerFiresWhenTicketReachesDone`() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000,
        )
        val saved = service.save(TriggerSave(name = "on done", kind = "ship"))

        service.onTicketDone()

        val events = service.events(saved.id)
        assertEquals(1, events.size, "reaching done must record exactly one ship event")
        assertEquals("ship", events.single().source)
        service.close()
    }

    @Test
    fun `shipTriggerIgnoresNonDoneTransitions`() {
        var now = 0L
        var startCalls = 0
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                startCalls++
                null
            },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // the pump is driven by hand, so every window is observed deterministically
        )
        val saved = service.save(TriggerSave(name = "on done", kind = "ship"))

        // A ticket moving to in_progress or pending is not a done transition: the board never calls
        // onTicketDone for it, so the armed ship trigger must record nothing and start nothing.
        now = 10_000
        service.pumpWindows() // ticket -> in_progress
        now = 20_000
        service.pumpWindows() // ticket -> pending
        now = 30_000
        service.pumpWindows()

        assertEquals(0, startCalls, "a non-done transition must never start a ship run")
        assertTrue(service.events(saved.id).isEmpty(), "a non-done transition must record no ship event")
        assertEquals("armed", stateOf(service, saved.id), "the ship trigger stays armed through non-done transitions")
        service.close()
    }

    @Test
    fun `scheduleTriggerFiresWhenIntervalElapses`() {
        var now = 0L
        val startedTicket = Ticket(
            id = "t-1", title = "fixture", status = "in_progress", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = "s-1", start = TicketStart(ok = true, reason = "started"),
        )
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { TicketAnswer(ticket = startedTicket, pushed = false) },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // the scheduler never ticks in this test: the pump is driven by hand
        )
        val saved = service.save(TriggerSave(name = "every minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0))

        now = 60_000
        service.pumpWindows()

        val events = service.events(saved.id)
        assertEquals(1, events.size, "one interval elapsed, so exactly one event must be recorded")
        assertEquals("schedule", events.single().source)
        assertEquals("started", events.single().disposition)
    }

    @Test
    fun `scheduleTriggerCoalescesMissedTicks`() {
        var now = 0L
        val startCalls = AtomicInteger(0)
        val startedTicket = Ticket(
            id = "t-coalesced", title = "fixture", status = "in_progress", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = "s-coalesced",
            start = TicketStart(ok = true, reason = "started"),
        )
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                startCalls.incrementAndGet()
                TicketAnswer(ticket = startedTicket, pushed = false)
            },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // drive schedule ticks deterministically
        )
        val saved = service.save(
            TriggerSave(name = "coalesced schedule", kind = "schedule", intervalMs = 60_000, coalesceMs = 120_000),
        )

        // The first two interval ticks arrive within one 120-second coalescing window.
        now = 60_000
        service.pumpWindows()
        now = 120_000
        service.pumpWindows()
        assertEquals(0, startCalls.get(), "no run starts while the coalescing window is open")
        assertEquals("folded", service.events(saved.id).last().disposition)

        // At the window boundary the pending schedule event is launched once.
        now = 180_000
        service.pumpWindows()
        awaitTrue { startCalls.get() == 1 }

        assertEquals(1, startCalls.get(), "coalesced schedule ticks must start exactly one run")
        val events = service.events(saved.id)
        assertEquals(3, events.size, "each due schedule tick is recorded")
        assertEquals(listOf("started", "folded", "folded"), events.map { it.disposition })
        assertEquals(listOf("schedule", "schedule", "schedule"), events.map { it.source })
        service.close()
    }

    @Test
    fun `scheduleIntervalBelowOneMinuteIsRefused`() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000,
        )
        val refused = kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.save(TriggerSave(name = "too fast", kind = "schedule", intervalMs = 59_999, coalesceMs = 0))
        }
        assertTrue(refused.message!!.contains("at least 1 minute"), "the refusal names the floor: ${refused.message}")
        assertEquals(60_000L, service.save(TriggerSave(name = "one minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0)).intervalMs)
        assertEquals(null, service.save(TriggerSave(name = "no period", kind = "schedule", coalesceMs = 0)).intervalMs)
        service.close()
    }

    @Test
    fun `scheduleTriggerSkipsWhenDisabled`() {
        var now = 0L
        var startCalls = 0
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                startCalls++
                null
            },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // the scheduler never ticks in this test: the pump is driven by hand
        )
        val saved = service.save(
            TriggerSave(name = "disabled minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0, enabled = false),
        )

        now = 60_000
        service.pumpWindows()

        assertEquals(0, startCalls, "a disabled trigger must never start a run")
        val events = service.events(saved.id)
        assertEquals(1, events.size, "the due event is still recorded, just suppressed")
        assertEquals("schedule", events.single().source)
        assertEquals("suppressed_disabled", events.single().disposition)
    }

    @Test
    fun `scheduleTriggerSkipsWhenTripped`() {
        var now = 0L
        val startCalls = AtomicInteger(0)
        val refusedTicket = Ticket(
            id = "t-2", title = "fixture", status = "todo", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = null, start = TicketStart(ok = false, reason = "refused"),
        )
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                startCalls.incrementAndGet()
                TicketAnswer(ticket = refusedTicket, pushed = false)
            },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // the scheduler never ticks in this test: the pump is driven by hand
        )
        val saved = service.save(TriggerSave(name = "tripped minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0))

        // A refused start trips the trigger. The launch runs on its own thread, so wait for the trip to land.
        service.fire(saved.id)
        awaitTrue { stateOf(service, saved.id) == "tripped" }
        assertEquals(1, startCalls.get(), "the refused run is the only start so far")

        now = 60_000
        service.pumpWindows()

        assertEquals(1, startCalls.get(), "a tripped trigger must never start a run when its interval elapses")
        val scheduled = service.events(saved.id).single { it.source == "schedule" }
        assertEquals("suppressed_tripped", scheduled.disposition, "the due schedule event is recorded, just suppressed")
        assertEquals("tripped", stateOf(service, saved.id), "only arm() clears a trip")

        service.arm(saved.id)
        now = 120_000
        service.pumpWindows()

        awaitTrue { startCalls.get() == 2 }
        assertEquals(2, startCalls.get(), "after arm() the next interval starts a run again")
    }

    @Test
    fun `scheduleIntervalSurvivesRestart`() {
        val repo = MemoryTriggers()
        var now = 0L
        val startedTicket = Ticket(
            id = "t-restart", title = "fixture", status = "in_progress", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = "s-restart", start = TicketStart(ok = true, reason = "started"),
        )
        val beforeRestart = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = { TicketAnswer(ticket = startedTicket, pushed = false) },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000,
        )
        val saved = beforeRestart.save(TriggerSave(name = "restart minute", kind = "schedule", intervalMs = 60_000, coalesceMs = 0))
        beforeRestart.close()

        // The daemon restarts: a brand-new service over the SAME repository. The interval must come back with it.
        val afterRestart = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = { TicketAnswer(ticket = startedTicket, pushed = false) },
            sessions = { null },
            clock = { now },
            pumpMs = 60_000, // the pump is driven by hand
        )

        now = 60_000
        afterRestart.pumpWindows()

        val events = afterRestart.events(saved.id)
        assertEquals(1, events.size, "a restored schedule must still fire once its interval elapses after restart")
        assertEquals("schedule", events.single().source)
        afterRestart.close()
    }

    /**
     * FLAKE-1 (TriggerDaemonTest.aTriggerRunningWhenTheDaemonStopsComesBackTripped). A start that is
     * still inside `startNext` when the daemon stops must NOT be recorded as a refusal: shutdown
     * interrupts the run, it does not refuse it. The row has to stay RUNNING so the next boot's `init`
     * still finds it and trips it "daemon restarted mid-run" (PHASE9-SPEC 5.5). Before the fix, the
     * launch thread came back from `startNext` with a refused answer, `launch()` recorded START_REFUSED
     * and overwrote the RUNNING row with TRIPPED, so the next daemon's `state == RUNNING` filter matched
     * nothing and the trip reason stayed "ended with START_REFUSED" -- the intermittent gate failure.
     *
     * The interleaving is forced deterministically: `startNext` parks on a latch until the test has
     * called `close()` (closing=true, exactly as `AgentHqDaemon.close` sets it before `manager.shutdown`
     * makes starts refuse), then returns a refused answer. No sleep, no retry.
     */
    @Test
    fun `aRefusedStartAfterShutdownLeavesTheRunRunningSoTheNextBootTripsIt`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launchThread = AtomicReference<Thread>()
        val refusedTicket = Ticket(
            id = "t-refused", title = "fixture", status = "todo", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = null,
            start = TicketStart(ok = false, reason = "refused", detail = "start_refused"),
        )
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                launchThread.set(Thread.currentThread())
                entered.countDown()
                try {
                    release.await() // parked inside the launch, as the real startNext blocks on the board/session manager
                } catch (e: InterruptedException) {
                    // the daemon's close() interrupts us; fall through to return the refusal
                }
                TicketAnswer(ticket = refusedTicket, pushed = false)
            },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000, // the pump never ticks: the launch is the only thing under test
        )
        val saved = service.save(TriggerSave(name = "nightly", kind = "manual", coalesceMs = 0))

        service.fire(saved.id)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the launch must reach startNext")

        // The daemon stops while the run is still inside startNext -- the last moment of the window.
        service.close()
        release.countDown()
        launchThread.get().join(5_000)

        assertEquals("running", stateOf(service, saved.id), "a shutdown-interrupted run must stay RUNNING for the next boot")
        assertNull(service.list().single { it.id == saved.id }.trippedReason, "shutdown must not trip the run")
    }

    /**
     * A repository whose write of [disposition] parks on [parked] until the test calls [release]: it
     * exposes exactly the window between a run settling and that disposition becoming readable. Every
     * other method is plain in-memory storage.
     */
    private class ParkingTriggers(
        private val disposition: String,
        private val memory: MemoryTriggers = MemoryTriggers(),
    ) : TriggerRepository by memory {
        val parked = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun release() = released.countDown()
        override fun insertEvent(event: TriggerEventView, keep: Long) {
            if (event.disposition == disposition) {
                parked.countDown()
                // The client reads while this write is in flight. Bounded, so a failing test can not
                // strand the launch thread.
                released.await(5, TimeUnit.SECONDS)
            }
            memory.insertEvent(event, keep)
        }
    }

    /**
     * 63a8314f (TriggerDaemonTest.anEmptyBoardIsNothingToStartAndNeverATrip, `expected:<[armed]> but
     * was:<[running]>` in the 2026-10-14 gate). A client waits for the durable `nothing_to_start`
     * disposition and then reads the trigger, exactly as that daemon test does. The launch thread settles
     * the run in two writes -- the disposition row, then the trigger row -- and the reader takes no lock,
     * so a reader served between them sees the disposition of a run that has already finished next to
     * the `running` state of a run that is still being settled.
     *
     * The interleaving is forced deterministically: the disposition's own store parks on a latch, so the
     * test reads the list at exactly the moment a slow store leaves the client there. Before the fix the
     * state at that instant is `running` (RED, the gate failure); once the trigger row settles before the
     * disposition that announces it, it is `armed`. No sleep, no retry.
     */
    @Test
    fun `theTriggerIsAlreadyArmedWhenNothingToStartBecomesReadable`() {
        val repo = ParkingTriggers("nothing_to_start")
        val service = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = { null }, // the empty board: there is nothing to start
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000, // the pump never ticks: the launch is the only thing under test
        )
        try {
            val saved = service.save(TriggerSave(name = "nightly", kind = "manual", coalesceMs = 0))
            service.fire(saved.id)

            assertTrue(repo.parked.await(5, TimeUnit.SECONDS), "the launch must reach its nothing_to_start write")
            // Read the way the daemon's WebSocket handler does: list() takes no lock, so this IS what a
            // client sees while the launch settles.
            assertEquals(
                "armed",
                service.list().single { it.id == saved.id }.state,
                "a client that reads the trigger after its nothing_to_start disposition must not see the run still settling as 'running'",
            )

            repo.release()
            awaitTrue { stateOf(service, saved.id) == "armed" }
            assertEquals(listOf("nothing_to_start"), service.events(saved.id).map { it.disposition })
        } finally {
            repo.release()
            service.close()
        }
    }

    /**
     * 63a8314f, the same rule for the other settling disposition. A start refused by the launch gate
     * settles the row to TRIPPED (a refused start is a failure to TriggerGuard.complete) before
     * `refused_launch_gate` becomes readable, so the read-after-disposition protocol cannot observe the
     * run still `running` either.
     */
    @Test
    fun `theTriggerIsAlreadyTrippedWhenARefusedLaunchGateBecomesReadable`() {
        val repo = ParkingTriggers("refused_launch_gate")
        val refused = Ticket(
            id = "t-gate", title = "fixture", status = "todo", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = null,
            start = TicketStart(ok = false, reason = "refused", detail = "refused by the launch gate"),
        )
        val service = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = { TicketAnswer(ticket = refused, pushed = false) },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000, // the pump never ticks: the launch is the only thing under test
        )
        try {
            val saved = service.save(TriggerSave(name = "nightly", kind = "manual", coalesceMs = 0))
            service.fire(saved.id)

            assertTrue(repo.parked.await(5, TimeUnit.SECONDS), "the launch must reach its refused_launch_gate write")
            assertEquals(
                "tripped",
                service.list().single { it.id == saved.id }.state,
                "a client that reads the trigger after its refused_launch_gate disposition must not see the refused run still settling as 'running'",
            )

            repo.release()
            awaitTrue { stateOf(service, saved.id) == "tripped" }
            assertEquals(listOf("refused_launch_gate"), service.events(saved.id).map { it.disposition })
        } finally {
            repo.release()
            service.close()
        }
    }

    /**
     * BUG-BoardUiFlakes, the reverse of 63a8314f's protocol: the desktop's Events list re-reads a trigger's
     * events when the trigger's state changes, so it reads them right after the `armed` push, while the
     * launch is still relabelling the event `started` -> `nothing_to_start`. Nothing is pushed after that
     * relabel, so a read served in between showed `started` for good (BoardUiTest, under load).
     *
     * Forced deterministically, with the same parked write as above: the trigger is already `armed`, the
     * relabel is in flight, and a client thread asks for the events. A lock-free read returns at once
     * with `started` (RED). Read under the service lock, it waits for the launch's block and gets
     * `nothing_to_start`. No sleep, no retry.
     */
    @Test
    fun `aClientThatSeesTheTriggerArmedReadsTheFinalDispositionNotStarted`() {
        val repo = ParkingTriggers("nothing_to_start")
        val service = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = { null }, // the empty board: there is nothing to start
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000, // the pump never ticks: the launch is the only thing under test
        )
        try {
            val saved = service.save(TriggerSave(name = "pull next", kind = "manual", coalesceMs = 0))
            service.fire(saved.id)

            assertTrue(repo.parked.await(5, TimeUnit.SECONDS), "the launch must reach its nothing_to_start write")
            assertEquals("armed", service.list().single { it.id == saved.id }.state, "the client has been told the run is over")

            val read = AtomicReference<List<String>>()
            val client = Thread { read.set(service.events(saved.id).map { it.disposition }) }.apply { start() }
            // Either the read already came back (served mid-settle) or it is waiting for the launch's block.
            awaitTrue { client.state == Thread.State.TERMINATED || client.state == Thread.State.BLOCKED }

            repo.release()
            client.join(5_000)
            assertEquals(
                listOf("nothing_to_start"), read.get(),
                "a client that re-reads the events after the trigger turned armed must see what the run did, not the in-flight 'started'",
            )
        } finally {
            repo.release()
            service.close()
        }
    }

    /** A repository that runs [beforeInsertEvent] ahead of every event write. Everything else is plain in-memory storage. */
    private class HookedTriggers(private val memory: MemoryTriggers = MemoryTriggers()) : TriggerRepository by memory {
        @Volatile var beforeInsertEvent: (TriggerEventView) -> Unit = {}
        override fun insertEvent(event: TriggerEventView, keep: Long) {
            beforeInsertEvent(event)
            memory.insertEvent(event, keep)
        }
    }

    /**
     * BUG-TriggerDaemonFlake (TriggerDaemonTest.firingStartsTheTopTicketUnattendedAndASuccessReArms, `no frame
     * after #6 matching the ticket the trigger started` at TriggerDaemonTest.kt:73). The guard hands a run to the
     * launch thread BEFORE fire() commits the trigger's own rows (the `started` event, RUNNING). On the real store
     * the launch's hand-out is the board's read-then-write transaction, and a trigger commit landing between its
     * read and its write aborts it with SQLITE_BUSY_SNAPSHOT -- after the session was spawned: the trigger trips
     * "start_next failed", the ticket stays todo and no `ticket` frame carrying `start` is ever pushed.
     *
     * The interleaving is forced deterministically: fire()'s `started` write is held until the launch thread has
     * either entered startNext (the bug) or parked on the service's lock (the fix) -- two observable facts, not a
     * timing. startNext notes what is durable when the hand-out begins: before the fix, neither row; once the
     * launch waits for the call that released it, both. No sleep, no retry.
     */
    @Test
    fun `theHandOutBeginsOnlyAfterFireHasCommittedTheTriggersOwnRows`() {
        val repo = HookedTriggers()
        val triggerId = AtomicReference<String>()
        val entered = CountDownLatch(1)
        val durableAtHandOut = AtomicReference<Pair<List<String>, String?>>()
        val startedTicket = Ticket(
            id = "t-top", title = "fixture", status = "in_progress", position = 0,
            createdAt = 0L, updatedAt = 0L, sessionId = "s-top", start = TicketStart(ok = true, reason = "started"),
        )
        val service = TriggerService(
            repo = repo,
            attention = AttentionStore(MemoryAttention()),
            startNext = {
                val id = triggerId.get()
                durableAtHandOut.set(repo.events(id).map { it.disposition } to repo.get(id)?.state)
                entered.countDown()
                TicketAnswer(ticket = startedTicket, pushed = false)
            },
            sessions = { null },
            clock = { 0L },
            pumpMs = 60_000, // the pump never ticks: fire() and its launch are the only things under test
        )
        try {
            val saved = service.save(TriggerSave(name = "nightly", kind = "manual", coalesceMs = 0))
            triggerId.set(saved.id)
            repo.beforeInsertEvent = { event ->
                // fire() has handed the run over and is about to commit its first row: hold it right here.
                if (event.disposition == "started") awaitTrue(30_000) { entered.count == 0L || launchParkedOn(service) }
            }

            service.fire(saved.id)

            assertTrue(entered.await(30, TimeUnit.SECONDS), "the launch must reach startNext")
            val (events, state) = durableAtHandOut.get()
            assertEquals(listOf("started"), events, "the hand-out began before fire() committed the event that started the run")
            assertEquals("running", state, "the hand-out began before fire() committed the trigger's RUNNING row")
        } finally {
            service.close()
        }
    }

    /**
     * BUG-TriggerRunIds: each trigger owns a guard (and a coalescer), and every coalescer used to number its runs
     * from `trigger-run-1`, while the service keys its in-flight runs by run id across ALL triggers. Two triggers
     * running at once both held `trigger-run-1`: the second overwrote the first's entry, so the first one's session
     * ending settled the SECOND trigger's run and left the first RUNNING forever.
     */
    @Test
    fun `twoTriggersRunningAtOnceEachSettleOnlyTheirOwnRun`() {
        val service = TriggerService(
            repo = MemoryTriggers(),
            attention = AttentionStore(MemoryAttention()),
            startNext = { null },
            sessions = { null },
            startPrompt = { agentId, _ -> PromptLaunch.Started("s-$agentId") },
            clock = { 0L },
            pumpMs = 60_000,
        )
        try {
            val a = service.save(TriggerSave(name = "a", kind = "manual", action = com.superiorsoftllc.agenthq.protocol.TriggerAction(kind = "prompt", agentId = "a", prompt = "go"), coalesceMs = 0))
            val b = service.save(TriggerSave(name = "b", kind = "manual", action = com.superiorsoftllc.agenthq.protocol.TriggerAction(kind = "prompt", agentId = "b", prompt = "go"), coalesceMs = 0))
            service.fire(a.id)
            service.fire(b.id)
            assertEquals("running", stateOf(service, a.id))
            assertEquals("running", stateOf(service, b.id))

            service.onSessionEvent(SessionEvent.Exit(sessionId = "s-a", code = 1))

            awaitTrue { stateOf(service, a.id) != "running" }
            assertEquals("running", stateOf(service, b.id), "trigger a's session ending settled trigger b's run")

            service.onSessionEvent(SessionEvent.Exit(sessionId = "s-b", code = 1))
            awaitTrue { stateOf(service, b.id) != "running" }
        } finally {
            service.close()
        }
    }

    private fun stateOf(service: TriggerService, triggerId: String): String =
        synchronized(service) { service.list().single { it.id == triggerId }.state }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    /** True once a trigger launch thread is BLOCKED entering [service]'s own monitor (the lock fire() holds). */
    private fun launchParkedOn(service: TriggerService): Boolean {
        val monitor = service.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(service))
        val threads = ManagementFactory.getThreadMXBean()
        return threads.getThreadInfo(threads.allThreadIds, 0).any {
            it != null && it.threadName == "agenthq-trigger-launch" && it.threadState == Thread.State.BLOCKED && it.lockName == monitor
        }
    }
}
