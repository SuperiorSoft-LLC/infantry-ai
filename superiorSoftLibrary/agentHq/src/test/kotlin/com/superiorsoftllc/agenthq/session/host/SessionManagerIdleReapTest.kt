package com.superiorsoftllc.agenthq.session.host

import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-M2-11f acceptance through the daemon's [SessionManager]: the idle stop reaches no client as an `exit`, leaves the
 * record IDLE with no live process, and the next prompt resumes; the kept process is on disk while it lives; a daemon
 * start reaps a leftover kept process only when pid AND start instant match.
 */
class SessionManagerIdleReapTest {
    private val adapter = FakeHarnessAdapter(autoComplete = true)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()
    private val storeFile = File.createTempFile("agenthq-idle-reap", ".json").also { it.delete() }
    @Volatile private var now = 5_000_000L

    private fun manager(table: FakeProcessTable, keepAlive: Int = 30) = SessionManager(
        FakeHarnessProvider(adapter),
        runner,
        JsonSessionStore(storeFile),
        killGraceMs = 50,
        clock = { now },
        lifecyclePolicy = { LifecyclePolicy(keepAlive) },
        lineRunner = runner,
        processTable = table,
        idleSweepMs = 0,
    ).also { it.eventSink = { e -> events += e } }

    private fun boots() = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line }.filter { it.contains("↻") } }

    private fun onDisk(id: String): SessionRecord = JsonSessionStore(storeFile).load().single { it.id == id }

    private fun record(id: String, state: SessionState, kept: KeptProcess?, childPid: Long? = null) = SessionRecord(
        id = id, provider = FakeHarnessAdapter.ID, workingDir = ".", spawnedAt = 1, lastHeartbeat = 1, state = state,
        providerSessionId = "prov-$id", childPid = childPid, keptProcess = kept,
    )

    @Test
    fun idleStop_sendsNoExit_leavesTheRecordIdleWithNoProcess_andTheNextPromptResumes() {
        val manager = manager(FakeProcessTable(1000L to 777L))
        val session = manager.startNew("one", model = "m1")
        assertEquals(KeptProcess(1000L, 777L, FakeHarnessAdapter.ID), onDisk(session.id).keptProcess, "persisted while it lives")

        now += 30 * 60_000L
        assertEquals(listOf(session.id), manager.stopIdleNow())

        assertFalse(adapter.current.isAlive)
        val stopped = manager.get(session.id)!!
        assertEquals(SessionState.IDLE, stopped.state)
        assertFalse(stopped.isProcessAlive)
        assertNull(stopped.keptProcess)
        assertNull(onDisk(session.id).keptProcess, "and cleared on disk, so a restart has nothing to reap")
        assertTrue(synchronized(events) { events.filterIsInstance<SessionEvent.ProcessExit>().isEmpty() }, "no `exit` frame")
        assertEquals(listOf("[agenthq] ↻ session started (fake-harness · m1)"), boots(), "no chat line")

        manager.resume(session.id, "two")
        assertEquals(2, adapter.launches.size)
        assertEquals("fake-provider-session", adapter.launches.last().resumeSessionId)
        assertEquals("[agenthq] ↻ session resumed (fake-harness · m1)", boots().last())
    }

    @Test
    fun reaping_killsAKeptProcessWhosePidAndStartInstantMatch_andTheRecordComesBackIdleAndResumable() {
        JsonSessionStore(storeFile).saveAll(listOf(record("a", SessionState.IDLE, KeptProcess(4242L, 1_000L, "opencode"))))
        val table = FakeProcessTable(4242L to 1_000L)

        val report = manager(table).let { m -> m.loadAndReconcile().also { assertTrue(m.get("a")!!.resumable) } }

        assertEquals(listOf(4242L), table.killed, "pid and start instant both match: it is ours, kill it")
        assertEquals(1, report.reaped)
        val back = onDisk("a")
        assertEquals(SessionState.IDLE, back.state)
        assertNull(back.keptProcess)
        assertNull(back.childPid)
        assertTrue(synchronized(events) { events.filterIsInstance<SessionEvent.Activity>().any { it.detail == "reaped stale opencode process pid 4242" } })
    }

    @Test
    fun reaping_leavesAReusedPidAlone() {
        JsonSessionStore(storeFile).saveAll(listOf(record("a", SessionState.IDLE, KeptProcess(4242L, 1_000L, "opencode"))))
        // Same pid, another start instant: the OS gave 4242 to an unrelated process since.
        val table = FakeProcessTable(4242L to 2_000L)

        val report = manager(table).loadAndReconcile()

        assertEquals(emptyList(), table.killed, "a reused pid is never killed")
        assertEquals(0, report.reaped)
        assertEquals(SessionState.IDLE, onDisk("a").state)
        assertNull(onDisk("a").keptProcess, "the record no longer claims a process")
    }

    @Test
    fun reaping_aKeptProcessCaughtMidTurn_comesBackIdle_notOrphaned() {
        JsonSessionStore(storeFile).saveAll(listOf(record("a", SessionState.BUSY, KeptProcess(4242L, 1_000L, "opencode"), childPid = 4242L)))
        val table = FakeProcessTable(4242L to 1_000L)

        manager(table).loadAndReconcile()

        assertEquals(listOf(4242L), table.killed)
        assertEquals(SessionState.IDLE, onDisk("a").state, "resumable, and nothing is left running")
    }

    @Test
    fun reaping_neverKillsWithoutARecordedStartInstant_orAProcessThatIsGone() {
        JsonSessionStore(storeFile).saveAll(
            listOf(
                record("unknown", SessionState.IDLE, KeptProcess(4242L, null, "opencode")),
                record("gone", SessionState.IDLE, KeptProcess(5151L, 3_000L, "opencode")),
            ),
        )
        val table = FakeProcessTable(4242L to 1_000L)

        manager(table).loadAndReconcile()

        assertEquals(emptyList(), table.killed, "nothing proves 4242 is ours; 5151 is not running")
        assertNull(onDisk("unknown").keptProcess)
        assertNull(onDisk("gone").keptProcess)
    }
}
