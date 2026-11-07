// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import com.superiorsoftllc.infantry.db.InfantryDatabaseHandle
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stopped daemon is STOPPED: `InfantryDaemon.close()` may not return while a thread of that daemon can
 * still write its store.
 *
 * WHY THIS EXISTS -- BUG "TicketStartDaemonTest.aRestartClearsQueueMarksAndStartsNothing flakes under
 * full-suite load with SQLITE_BUSY at TicketBoard.clearQueueMarks". That test (and every restart test)
 * boots a SECOND daemon over the state directory the first one left, which is only sound if the first
 * one has really stopped writing. It had not. `SessionManager.shutdown()` kills a live run while
 * holding the session manager's monitor, so the board cannot apply the killed run's outcome inline: it
 * QUEUES it to its own single-thread executor (`infantry-ticket-session-events`). `close()` never waited
 * for that queue, and the queued work is a `ticket_runs` UPDATE -- a write to the very database file
 * the second daemon opens a moment later. The file is then contended by a daemon the test believes is
 * closed, in a store whose SQLITE_BUSY budget is a fixed 5 s (measured: a write that finds the write
 * lock held waits the full timeout and then fails with exactly `[SQLITE_BUSY] The database file is
 * locked`).
 *
 * The interleaving is forced with a latch, never slept for: [ParkingDriver] parks the first `ticket_runs`
 * WRITE once armed, so when the test calls stop() it KNOWS that write is in flight, and it records which
 * thread parked -- if the write ever came from somewhere other than the board's queue, this test fails
 * loudly instead of passing for the wrong reason.
 */
class DaemonShutdownTest {

    @Test
    fun stopWaitsForTheTicketOutcomeItsOwnKillQueued() {
        val stateDir = Files.createTempDirectory("infantry-shutdown-order").toFile()
        val opened = InfantryDatabaseFactory.open(File(stateDir, "infantry.db").absolutePath)
        val driver = ParkingDriver(opened.driver)
        val store = SqlInfantryStore(InfantryDatabaseHandle(InfantryDatabaseFactory.newDatabase(driver), driver))
        val fixture = DaemonFixture.launch(
            stateDir = stateDir,
            store = store,
            runner = GatedRunner(),
            context = FakeTicketContext("HANG"),
            pluginsRoot = TestDaemonPlugins.fullRoot(),
        )
        val stopFailure = AtomicReference<Throwable?>()
        var ticketId = ""
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile("""{"id":"backend","name":"backend","role":"backend"}""")
            val ticket = fixture.client.saveTicket("""{"title":"held open","agentId":"backend"}""")
            ticketId = ticket.id
            val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertTrue(started.start?.ok == true, "the hanging start must succeed: ${started.start}")

            // Arm: from here the next `ticket_runs` write is the one the shutdown itself queues.
            driver.arm()
            val stopping = Thread({ runCatching { fixture.stop() }.onFailure(stopFailure::set) }, "stopping")
            stopping.start()

            assertTrue(
                driver.parked.await(10, TimeUnit.SECONDS),
                "the shutdown queued no ticket_runs write, so this test proved nothing; writes seen since arm: ${driver.seen}",
            )
            assertEquals(
                "infantry-ticket-session-events", driver.parkedBy.get(),
                "the parked write must be the board's own queued session-event write",
            )
            // Sampled BEFORE releasing: with the write provably in flight, a shutdown that drains it
            // cannot have returned yet.
            Thread.sleep(300)
            assertTrue(
                stopping.isAlive,
                "stop() returned while the board's queued ticket write was still in flight: the daemon " +
                    "writes its store after it is 'closed', so a second daemon over the same state " +
                    "directory contends the same SQLite file",
            )
            driver.release()
            stopping.join(10_000)
            assertFalse(stopping.isAlive, "stop() did not finish after the queued write landed")
            assertNull(stopFailure.get(), "stop() threw: ${stopFailure.get()}")

            // And the point of DRAINING rather than dropping: the killed run's outcome is durable the
            // moment stop() returns. Read while the state directory still exists; the finally removes it.
            SqlInfantryStore.open(File(stateDir, "infantry.db").absolutePath).use { reopened ->
                val run = reopened.ticketRuns.byTicket(ticketId).single()
                assertNotNull(run.endedAt, "the killed run's ticket_runs row must be finished by stop()")
                assertEquals(
                    "KILLED_FORCED", run.outcome,
                    "the write stop() waited for is the killed run's own outcome",
                )
            }
        } finally {
            driver.release()
            fixture.stop()
            fixture.deleteState()
        }
    }
}

/**
 * The real driver with the next `ticket_runs` WRITE parked on a latch: [arm] lets the test decide that
 * the write it is about to observe is the one the shutdown queues, [parked] says the write is in flight,
 * and [release] lets it through. Only `execute` is parked (SQLDelight sends INSERT/UPDATE/DELETE there;
 * SELECTs go to `executeQuery`), so the parked statement is a real write and not a read the outcome
 * would not depend on. Every other statement is delegated untouched.
 */
private class ParkingDriver(private val real: SqlDriver) : SqlDriver by real {
    private val armed = AtomicBoolean(false)
    val parked = CountDownLatch(1)
    val parkedBy = AtomicReference<String?>(null)
    /** Diagnostics: the thread and first words of every write since [arm], shown when no ticket_runs write arrives. */
    val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val released = CountDownLatch(1)

    fun arm() = armed.set(true)

    fun release() = released.countDown()

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        // Arm only on the FIRST matching write: an armed write of another table must not spend the one
        // arming, or the parked statement could be a statement this test says nothing about.
        if (armed.get()) seen += Thread.currentThread().name + ": " + sql.take(60)
        if (sql.contains("ticket_runs") && armed.compareAndSet(true, false)) {
            parkedBy.set(Thread.currentThread().name)
            parked.countDown()
            released.await(10, TimeUnit.SECONDS)
        }
        return real.execute(identifier, sql, parameters, binders)
    }
}
