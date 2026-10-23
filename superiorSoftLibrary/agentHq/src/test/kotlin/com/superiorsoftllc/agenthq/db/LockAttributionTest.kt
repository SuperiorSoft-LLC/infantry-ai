package com.superiorsoftllc.agenthq.db

import com.superiorsoftllc.agenthq.persistence.SettingRow
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LOCK ATTRIBUTION: a blocked writer is told WHO holds the lock, WHAT it is doing, for HOW LONG, and
 * WHICH of the two SQLITE_BUSY failures this is. Every test uses two REAL store instances over one file
 * (own connections, own tracker, own published record); the cross-process test uses a second JVM.
 */
class LockAttributionTest {

    private val dir: File = Files.createTempDirectory("agenthq-lock-attr").toFile()
    private val db = File(dir, "agenthq.db").absolutePath
    private val opened = mutableListOf<SqlAgentHqStore>()

    private fun open(label: String, busyMs: Int = 1_500) = SqlAgentHqStore.open(db, label, busyMs).also { opened += it }

    private fun write(s: SqlAgentHqStore, key: String) = s.settings.put(SettingRow(key, "v", 1))

    @AfterTest fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    /** Holds the write lock in a transaction on [s] until [release] counts down. */
    private fun holdLock(s: SqlAgentHqStore, release: CountDownLatch): Thread {
        val holding = CountDownLatch(1)
        val t = thread(name = "holder-thread") {
            s.transaction { write(s, "held"); holding.countDown(); release.await(30, TimeUnit.SECONDS) }
        }
        assertTrue(holding.await(10, TimeUnit.SECONDS), "holder never took the write lock")
        return t
    }

    @Test
    fun aTimeoutNamesTheHolderTheOperationAndTheHoldTime() {
        val a = open("dev-1")
        val b = open("dev-2")
        val release = CountDownLatch(1)
        val t = holdLock(a, release)
        try {
            val e = assertFailsWith<StoreLockedException> { write(b, "blocked") }
            assertEquals(LockFailureKind.TIMEOUT, e.kind)
            val m = e.message!!
            assertTrue("locked by dev-1" in m, m)
            assertTrue("writing settings" in m, m)
            assertTrue("held" in m, m)
            assertTrue("TIMEOUT" in m, m)
            assertTrue("agenthq.db" in m, m)
            assertTrue(e.holders.first().heldForMs >= 250, "hold time must be real, was ${e.holders.first().heldForMs}")
        } finally { release.countDown(); t.join(10_000) }
    }

    @Test
    fun aTransactionOpenedOutsideTheTrackedHelperStillNamesItsHolder() {
        // BUG-534baac6: a raw `database.transaction` (the shape the schema migration uses) bypasses
        // TrackedTransacter; its lock used to be unregistered between statements -> "writer that is not registered".
        val a = open("dev-1")
        val b = open("dev-2")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = AgentHQDatabaseFactory.newDatabase(a.handle.driver)
        val t = thread(name = "raw-holder") {
            raw.transaction {
                raw.settingsQueries.putSetting("raw", "v", 1)
                holding.countDown(); release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(holding.await(10, TimeUnit.SECONDS))
        try {
            val e = assertFailsWith<StoreLockedException> { write(b, "blocked") }
            assertTrue("locked by dev-1" in e.message!!, e.message)
            assertTrue("not registered" !in e.message!!, e.message)
        } finally { release.countDown(); t.join(10_000) }
        assertTrue(a.handle.writes!!.holders().none { it.sameProcess }, "raw transaction still registered after commit")
    }

    @Test
    fun aSnapshotFailureIsSaidToBeNotRetryableAndFastAndIsNotATimeout() {
        val a = open("dev-1")
        val b = open("dev-2", busyMs = 5_000)
        write(a, "seed")
        val started = System.currentTimeMillis()
        val e = assertFailsWith<StoreLockedException> {
            b.transaction {
                b.settings.get("seed")      // read first: takes the snapshot
                write(a, "other")           // another connection commits in between
                write(b, "mine")            // the upgrade can never succeed
            }
        }
        assertEquals(LockFailureKind.SNAPSHOT, e.kind)
        assertTrue(System.currentTimeMillis() - started < 3_000, "a snapshot failure must not wait out busy_timeout")
        assertTrue("NOT a timeout" in e.message!!, e.message)
        assertTrue("will not help" in e.message!!, e.message)
    }

    @Test
    fun theRecordIsClearedOnCommitAndOnRollback() {
        val a = open("dev-1")
        a.transaction { write(a, "x") }
        assertTrue(a.handle.writes!!.holders().isEmpty(), "still registered after commit")
        runCatching { a.transaction { write(a, "y"); error("boom") } }
        assertTrue(a.handle.writes!!.holders().isEmpty(), "still registered after rollback")
        a.handle.writes!!.publishNow()
        val published = File("$db.holders").listFiles { f -> f.name.endsWith(".json") }!!.single().readText()
        assertTrue("\"inFlight\":[]" in published.replace(" ", ""), published)
    }

    @Test
    fun aStoreThatIsClosedLeavesNoRecordBehind() {
        val a = open("dev-1")
        write(a, "x"); a.handle.writes!!.publishNow()
        assertTrue(File("$db.holders").listFiles()!!.isNotEmpty())
        a.close()
        assertTrue(File("$db.holders").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aLapsedRecordFromADeadProcessIsNotReportedAndIsDeleted() {
        val b = open("dev-2")
        val holders = File("$db.holders").apply { mkdirs() }
        val stale = File(holders, "dead0001.json")
        // A holder that was mid-write when its process died: recent heartbeat, but the pid is gone.
        val deadPid = Long.MAX_VALUE - 1
        stale.writeText(
            """{"instanceId":"dead0001","label":"ghost","pid":$deadPid,"heartbeatAt":${System.currentTimeMillis()},""" +
                """"inFlight":[{"what":"writing tickets","state":"HOLDING","since":1,"thread":"t"}]}""",
        )
        assertTrue(b.handle.writes!!.holders().isEmpty(), "reported a holder whose process is dead")
        assertTrue(!stale.exists(), "a dead record must be cleaned up")
        // A live pid with a lapsed heartbeat is just as stale.
        val lapsed = File(holders, "lapsed01.json")
        lapsed.writeText(
            """{"instanceId":"lapsed01","label":"ghost","pid":${ProcessHandle.current().pid()},"heartbeatAt":1,""" +
                """"inFlight":[{"what":"writing tickets","state":"HOLDING","since":1,"thread":"t"}]}""",
        )
        assertTrue(b.handle.writes!!.holders().isEmpty(), "reported a holder whose heartbeat lapsed")
    }

    @Test
    fun aSeparateProcessHoldsTheLockAndTheBlockedWriterNamesItThenItDies() {
        // The parent opens FIRST (creates the schema, owns an idle connection on this thread). Observed on
        // Windows: a NEW connection opened while another process holds a write transaction can fail in
        // xerial's connect-time pragmas with SQLITE_IOERR_TRUNCATE; that is connection setup, not a write.
        val b = open("parent-daemon", busyMs = 3_000)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val child = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            "com.superiorsoftllc.agenthq.db.LockHolderChild", db, "child-daemon",
        ).redirectErrorStream(true).start()
        try {
            val out = child.inputStream.bufferedReader()
            // The child may print library noise (SLF4J) before it says it holds the lock.
            val line = generateSequence { out.readLine() }.firstOrNull { it == "HOLDING" }
            assertEquals("HOLDING", line, "child never reported holding the lock")
            val e = assertFailsWith<StoreLockedException> { write(b, "blocked") }
            // Across processes on Windows the lock shows up while a NEW connection is being set up (an I/O
            // error, not BUSY); the attribution must name the holder for either shape.
            assertTrue(e.kind == LockFailureKind.TIMEOUT || e.kind == LockFailureKind.CONNECT, "kind was ${e.kind}")
            val m = e.message!!
            assertTrue("locked by child-daemon" in m, m)
            assertTrue("pid ${child.pid()}" in m, m)
            assertTrue("writing settings" in m, m)
            assertTrue(File("$db.holders").listFiles()!!.any { it.name.endsWith(".json") })
            // The holder dies without cleaning up: the pointer must heal, not send agents to a corpse.
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            assertTrue(b.handle.writes!!.holders().isEmpty(), "still pointing at a dead process")
            // Observed: the FIRST connection after a holder was killed fails once with SQLITE_IOERR_TRUNCATE
            // (crash recovery of the shared WAL files) and the next succeeds. That is not attributable (the
            // holder is gone), so it is bounded-retried here and recorded in the report, not hidden.
            var tries = 0
            while (true) { try { write(b, "now-free"); break } catch (t: Throwable) { if (++tries > 5) throw t; Thread.sleep(200) } }
        } finally { child.destroyForcibly() }
    }
}
