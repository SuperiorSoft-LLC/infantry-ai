package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.session.host.KeptProcess
import com.superiorsoftllc.agenthq.session.host.ProcessTable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROGUE-1: the ledger proves ownership by pid + start instant; only provably ours, ended or idle-heavy, is reaped. */
class ProcessWatchTest {
    /** A fake OS: live processes by pid; a parent that dies leaves its children with a dangling parent pid (Windows). */
    private class FakeOs : ProcessSource, ProcessTable, MemorySource {
        val procs = LinkedHashMap<Long, ProcInfo>()
        val mb = HashMap<Long, Long>()
        val killed = mutableListOf<Long>()
        var free: Long? = 16_000

        fun start(pid: Long, parent: Long?, cmd: String, at: Long, memMb: Long = 50) {
            procs[pid] = ProcInfo(pid, parent, cmd, at); mb[pid] = memMb
        }
        fun exit(pid: Long) { procs.remove(pid) }

        override fun descendants(rootPid: Long): List<ProcInfo> {
            val out = mutableListOf<ProcInfo>()
            var frontier = setOf(rootPid)
            while (frontier.isNotEmpty()) {
                val next = procs.values.filter { it.parentPid in frontier && it.pid !in out.map { o -> o.pid } }
                out += next; frontier = next.map { it.pid }.toSet()
            }
            return out
        }
        override fun info(pid: Long) = procs[pid]?.copy(parentPid = null)
        override fun all() = procs.values.toList()
        override fun startInstant(pid: Long) = procs[pid]?.startedAt
        override fun killTree(pid: Long, startedAt: Long) {
            if (procs[pid]?.startedAt != startedAt) return
            (descendants(pid).map { it.pid } + pid).forEach { killed += it; procs.remove(it) }
        }
        override fun freeMb() = free
        override fun totalMb() = 32_000L
        override fun processes() = procs.values.map { ProcMem(it.pid, it.command.substringBefore(' '), mb[it.pid] ?: 0) }
    }

    private var now = 10_000_000L
    private val os = FakeOs()
    private val sessions = LinkedHashMap<String, SessionRecord>()

    private fun session(id: String, kept: Long, state: SessionState = SessionState.IDLE, lastOutputAt: Long? = now) {
        sessions[id] = SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = state,
            keptProcess = KeptProcess(kept, os.procs[kept]?.startedAt, "claude"), lastOutputAt = lastOutputAt,
        )
    }

    private fun watch(
        ledger: ProcessLedger = ProcessLedger(os, os, clock = { now }),
        config: ProcessWatchConfig = ProcessWatchConfig(),
        pressure: MutableList<ProcessReport> = mutableListOf(),
        lockAlive: Boolean = false,
    ) = ProcessWatch(ledger, { sessions.values.toList() }, os, os, { config }, { now }, onPressure = { pressure += it }, lockHolderAlive = { lockAlive })

    // Pids far above anything real, so the test JVM's own ancestry (protected) never collides.
    private val harness = 900_100L
    private val gradlew = 900_101L
    private val gradleDaemon = 900_102L
    private val kotlinDaemon = 900_103L
    private val stranger = 900_999L

    private fun gradleUnder(session: String) {
        os.start(harness, null, "claude --session", 1_000)
        os.start(gradlew, harness, "cmd /c gradlew test", 5_000)
        os.start(gradleDaemon, gradlew, "java GradleDaemon", 6_000, memMb = 1_200)
        session(session, harness)
    }

    @Test fun `an orphan of a removed session is listed as rogue and reaped`() {
        gradleUnder("s1")
        os.start(stranger, null, "java SomethingElse", 2_000, memMb = 3_000)
        val w = watch()
        w.tick()
        os.exit(gradlew) // the client ends; the daemon's parent pid now dangles, it is nobody's descendant
        sessions.remove("s1")
        os.exit(harness)
        w.tick()

        val report = w.report()
        assertEquals(listOf(gradleDaemon), report.rogue.map { it.pid })
        assertEquals("session removed", report.rogue.single().reason)
        assertEquals(listOf(gradleDaemon), w.reapRogue().map { it.pid })
        assertEquals(listOf(gradleDaemon), os.killed)
        assertTrue(stranger in os.procs, "an unrelated process is never touched")
    }

    @Test fun `a grandchild forked after its parent was orphaned is still captured`() {
        gradleUnder("s1")
        val w = watch()
        w.tick()
        os.exit(gradlew)
        os.start(kotlinDaemon, gradleDaemon, "java KotlinCompileDaemon", 9_000, memMb = 800)
        w.tick()
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        val rogue = w.report().rogue.map { it.pid }.toSet()
        assertTrue(gradleDaemon in rogue && kotlinDaemon in rogue)
        assertEquals("session ended (EXITED)", w.report().rogue.first().reason)
    }

    @Test fun `a live session's children are never rogue`() {
        gradleUnder("s1")
        val w = watch()
        w.tick()
        // busy: a turn in flight
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.BUSY, lastOutputAt = 0)
        assertTrue(w.report().rogue.isEmpty())
        // idle but recent output
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.IDLE, lastOutputAt = now - 60_000)
        assertTrue(w.report().rogue.isEmpty())
        // idle with a registered bg job
        sessions["s1"] = sessions.getValue("s1").copy(lastOutputAt = 0, bgJobs = listOf(BgJob("j", "build", gradlew, 0)))
        assertTrue(w.report().rogue.isEmpty())
        // the session's own harness process is refused even when asked
        assertIs<ReapResult.Refused>(w.reap(harness))
        assertTrue(os.killed.isEmpty())
    }

    @Test fun `an idle session holding a heavy child past the quiet window is rogue, a light one is not`() {
        gradleUnder("s1")
        os.start(900_200L, harness, "node light.js", 7_000, memMb = 100)
        val w = watch()
        w.tick()
        sessions["s1"] = sessions.getValue("s1").copy(lastOutputAt = now - 31 * 60_000L)
        val rogue = w.report().rogue
        assertEquals(listOf(gradleDaemon), rogue.map { it.pid })
        assertTrue(rogue.single().reason!!.startsWith("session idle 31 min"))
    }

    @Test fun `a daemon whose parent died before any tick is adopted by the worktree path in its command line`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1", harness)
        sessions["s1"] = sessions.getValue("s1").copy(worktreePath = "C:/work/.worktrees/ticket-aaa-fix")
        os.start(gradleDaemon, 777_777L, "java GradleDaemon -Duser.dir=C:/work/.worktrees/ticket-aaa-fix", 6_000, memMb = 900)
        val w = watch()
        w.tick()
        val view = w.report().tracked.single { it.pid == gradleDaemon }
        assertEquals("s1", view.sessionId)
        assertTrue(view.inferred)
        assertFalse(view.rogue)
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        assertEquals("session ended (EXITED)", w.report().rogue.single { it.pid == gradleDaemon }.reason)
        assertEquals(listOf(gradleDaemon, harness).toSet(), w.reapRogue().map { it.pid }.toSet())
    }

    @Test fun `a live parent chain to a proven entry attributes a heavy child by ancestry`() {
        gradleUnder("s1")
        val w = watch()
        w.tick()
        os.start(kotlinDaemon, gradleDaemon, "java KotlinCompileDaemon", 9_000, memMb = 300)
        os.start(900_300L, kotlinDaemon, "node tool.js", 9_500, memMb = 300)
        os.exit(gradlew)
        w.tick()
        assertEquals("s1", w.report().tracked.single { it.pid == 900_300L }.sessionId)
    }

    @Test fun `an unmatched orphan gradle daemon is flagged rogue but never reaped, unless the gate lock holder lives`() {
        os.start(gradleDaemon, 777_777L, "java GradleDaemon 8.5", 6_000, memMb = 900)
        val w = watch()
        w.tick()
        val rogue = w.report().rogue.single()
        assertEquals(gradleDaemon, rogue.pid)
        assertNull(rogue.sessionId)
        assertTrue(rogue.command.startsWith("java GradleDaemon"))
        assertTrue(w.reapRogue().isEmpty())
        assertTrue(os.killed.isEmpty() && gradleDaemon in os.procs)
        assertIs<ReapResult.Refused>(w.reap(gradleDaemon))
        assertTrue(watch(lockAlive = true).report().rogue.isEmpty())
    }

    @Test fun `unrelated and recycled pids are never touched`() {
        os.start(stranger, null, "java Unrelated", 2_000, memMb = 4_000)
        gradleUnder("s1")
        val w = watch()
        w.tick()
        val r = w.report()
        assertFalse(r.tracked.any { it.pid == stranger })
        assertEquals(listOf(stranger), r.unattributed.filter { it.pid == stranger }.map { it.pid })
        assertIs<ReapResult.Refused>(w.reap(stranger))

        // the daemon's pid is recycled: same number, another process
        sessions.remove("s1")
        os.exit(gradleDaemon)
        os.start(gradleDaemon, null, "java SomeoneElse", 99_000, memMb = 2_000)
        assertIs<ReapResult.Refused>(w.reap(gradleDaemon))
        assertTrue(gradleDaemon in os.procs)
        w.tick()
        assertNull(w.report().tracked.firstOrNull { it.pid == gradleDaemon })
        assertFalse(os.killed.contains(stranger) || os.killed.contains(gradleDaemon))
    }

    @Test fun `protected processes are never recorded`() {
        gradleUnder("s1")
        val ledger = ProcessLedger(os, os, clock = { now }, isProtected = { it.pid == gradleDaemon })
        watch(ledger).tick()
        assertFalse(ledger.entries().any { it.pid == gradleDaemon })
    }

    @Test fun `pressure fires below the floor, not above, throttled`() {
        gradleUnder("s1")
        val fired = mutableListOf<ProcessReport>()
        val w = watch(pressure = fired)
        os.free = 8_000
        w.tick()
        assertTrue(fired.isEmpty())
        os.free = 2_000
        w.tick()
        assertEquals(1, fired.size)
        assertTrue(fired.single().underPressure)
        assertEquals(gradleDaemon, fired.single().topOffenders().first().pid)
        w.tick()
        assertEquals(1, fired.size, "throttled")
        now += 11 * 60_000L
        w.tick()
        assertEquals(2, fired.size)
    }

    @Test fun `auto-reap is off by default and reaps rogue when on`() {
        gradleUnder("s1")
        val ledger = ProcessLedger(os, os, clock = { now })
        val w = watch(ledger)
        w.tick()
        sessions.remove("s1")
        w.tick()
        assertTrue(os.killed.isEmpty())
        val auto = watch(ledger, config = ProcessWatchConfig(autoReap = true))
        auto.tick()
        assertTrue(gradleDaemon in os.killed)
    }

    @Test fun `the ledger survives a restart for its owner only`() {
        val dir = Files.createTempDirectory("ledger").toFile()
        try {
            val f = File(dir, ProcessLedger.FILE_NAME)
            gradleUnder("s1")
            ProcessLedger(os, os, f, "me", { now }).observe(mapOf("s1" to listOf(harness)))
            assertEquals(3, ProcessLedger(os, os, f, "me", { now }).entries().size)
            assertTrue(ProcessLedger(os, os, f, "other", { now }).entries().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `the processes frame lists, reaps one, refuses a stranger, reaps all rogue`() {
        gradleUnder("s1")
        os.start(stranger, null, "java Unrelated", 2_000, memMb = 4_000)
        val w = watch()
        w.tick()
        sessions.remove("s1")
        val listed = ProcessFrames.answer(w, ProcessesRequest())
        assertEquals(setOf(harness, gradlew, gradleDaemon), listed.report.rogue.map { it.pid }.toSet())
        val refused = ProcessFrames.answer(w, ProcessesRequest("reap", stranger))
        assertTrue(refused.refused!!.contains("never reaped"))
        val one = ProcessFrames.answer(w, ProcessesRequest("reap", gradleDaemon))
        assertEquals(listOf(gradleDaemon), one.reaped.map { it.pid })
        assertFalse(one.report.tracked.any { it.pid == gradleDaemon })
        val rest = ProcessFrames.answer(w, ProcessesRequest("reap_rogue"))
        assertTrue(harness in rest.reaped.map { it.pid }) // its tree kill takes gradlew with it
        assertFalse(harness in os.procs || gradlew in os.procs)
        assertTrue(stranger in os.procs)
        kotlin.test.assertFailsWith<IllegalArgumentException> { ProcessFrames.answer(w, ProcessesRequest("nuke")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ProcessFrames.answer(w, ProcessesRequest("reap")) }
    }

    @Test fun `tasklist and ps output parse`() {
        val t = OsMemorySource.parseTasklist("\"java.exe\",\"1234\",\"Console\",\"1\",\"1,234,567 K\"\r\n\"x.exe\",\"9\",\"S\",\"0\",\"2.048 K\"\r\n")
        assertEquals(listOf(ProcMem(1234, "java.exe", 1205), ProcMem(9, "x.exe", 2)), t)
        assertEquals(listOf(ProcMem(77, "node", 500)), OsMemorySource.parsePs("   77 512000 /usr/bin/node\n"))
    }
}
