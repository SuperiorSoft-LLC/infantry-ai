// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.GateLockView
import com.superiorsoftllc.infantry.daemon.api.processes.GateOwner
import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessDaemonDir
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * PROC-LEAK-2 (ticket 07448ac7): a detached build daemon whose client exited while no tick saw it (a daemon restart in between)
 * has no live parent and a command line without the worktree. The profile's `daemonDir` (Gradle: the per-pid daemon log's
 * `Received command: Build{... currentDir=...}`) names the directory it built in, so the ledger adopts it for that session.
 */
class DaemonDirAdoptTest {
    private var now = 10_000_000L
    private val os = FakeOs()
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private val tmp: File = Files.createTempDirectory("procleak2").toFile()
    private val gradleHome = File(tmp, "gradle-home")
    private val worktree = "C:\\work\\.worktrees\\ticket-aaa-fix"

    private val harness = 900_100L
    private val gradleDaemon = 900_102L
    private val kotlinDaemon = 900_103L

    /** The bundled gradle profile's `daemonDir`, against a temp GRADLE_USER_HOME. */
    private val dirSpec = ProcessDaemonDir(
        paths = listOf("\${env:GRADLE_USER_HOME}/daemon/*/daemon-\${pid}.out.log", "\${home}/.gradle/daemon/*/daemon-\${pid}.out.log"),
        match = "Received command: Build[{].*?currentDir=([^,}]+)",
    )
    private val profiles = DEFAULTS.map { if (it.name == "gradle") it.copy(daemonDir = dirSpec) else it }
    private val reader = DaemonDirReader(env = { if (it == "GRADLE_USER_HOME") gradleHome.path else null }, home = File(tmp, "nohome").path)

    init {
        os.sessionList = { sessions.values.map { it.watched() } }
        os.profiles = profiles
        os.gate = object : GateLockView {
            override fun read(): GateOwner? = null
            override fun holderAlive(owner: GateOwner) = false
            override fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long>): String? = null
        }
    }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun session(id: String, state: SessionState = SessionState.BUSY, worktreePath: String? = worktree) {
        os.start(harness, null, "claude --session", 1_000)
        sessions[id] = SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = state,
            keptProcess = KeptProcess(harness, 1_000, "claude"), lastOutputAt = now, worktreePath = worktreePath,
        )
    }

    private fun daemonLog(pid: Long, vararg dirs: String, version: String = "9.4.1"): File =
        File(gradleHome, "daemon/$version/daemon-$pid.out.log").also { f ->
            f.parentFile.mkdirs()
            f.writeText(buildString {
                appendLine("2026-11-04T21:39:00.000-0500 [INFO] [org.gradle.launcher.daemon.server.Daemon] start")
                for (d in dirs) appendLine("2026-11-04T21:39:01.000-0500 [INFO] [x.DefaultIncomingConnectionHandler] Received command: Build{id=abc, currentDir=$d}.")
            })
        }

    private fun ledger(file: File? = null) = ProcessLedger(os, file, "me", { now }, profiles = { profiles }, daemonDirs = reader)
    private fun watch(l: ProcessLedger) = ProcessWatch(l, os, { ProcessWatchConfig() }, { now }, enabled = { true }, log = {})

    // Its client (pid 777_777) is gone: the daemon is an orphan, and nothing on its command line names the worktree.
    private fun orphanDaemon(at: Long = 6_000) =
        os.start(gradleDaemon, 777_777L, "\"C:\\jdk\\bin\\java.exe\" -Xmx3g -cp C:\\Users\\me\\.gradle\\wrapper\\gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.4.1", at, memMb = 2_000)

    @Test fun `an orphan gradle daemon the ledger never saw is adopted by the worktree its daemon log names, then rogue once the session ends`() {
        session("s1")
        orphanDaemon()
        daemonLog(gradleDaemon, worktree)
        val l = ledger()
        val w = watch(l)
        w.tick()
        val view = w.report().tracked.single { it.pid == gradleDaemon }
        assertEquals("s1", view.sessionId)
        assertTrue(view.inferred)
        assertFalse(view.rogue)
        assertTrue(w.report().unattributed.none { it.pid == gradleDaemon })
        // A Kotlin daemon it forks later is under a ledger entry now: captured by the walk.
        os.start(kotlinDaemon, gradleDaemon, "java KotlinCompileDaemon", 9_000, memMb = 600)
        w.tick()
        assertEquals("s1", l.get(kotlinDaemon)?.sessionId)

        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        val rogue = w.report().rogue.single { it.pid == gradleDaemon }
        assertEquals("session ended (EXITED)", rogue.reason)
        assertTrue(gradleDaemon in w.reapRogue().map { it.pid })
        assertFalse(gradleDaemon in os.procs)
    }

    @Test fun `after a daemon restart the daemon started while it was down is adopted, and earlier entries survive from the file`() {
        val file = File(tmp, "state/process-ledger.json")
        session("s1")
        // Before the restart: a build daemon seen under the harness is recorded and saved.
        os.start(800_001L, harness, "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.4.1", 2_000, memMb = 1_500)
        watch(ledger(file)).tick()
        os.exit(harness)
        os.start(harness, null, "claude --session", 1_000) // the harness the session keeps after the restart
        // While Infantry AI was down: a build ran, its client exited, its daemon stayed.
        orphanDaemon(at = 7_000)
        daemonLog(gradleDaemon, "C:/elsewhere/project", worktree)
        val after = ledger(file)
        assertEquals("s1", after.get(800_001L)?.sessionId, "the ledger file outlives the restart")
        watch(after).tick()
        assertEquals("s1", after.get(gradleDaemon)?.sessionId, "the last build's currentDir wins")
    }

    @Test fun `on Windows the snapshot holds only the image path, and the orphan's full command line is read to match its profile`() {
        // The live repro: the JDK gives `C:\jdk\bin\java.exe` alone for a process this JVM did not start.
        val win = object : FakeOs() {
            override fun snapshot() = super.snapshot().map { it.copy(command = it.command.substringBefore(" -")) }
        }
        win.sessionList = os.sessionList; win.profiles = profiles; win.gate = os.gate
        win.start(harness, null, "claude --session", 1_000)
        sessions["s1"] = SessionRecord(
            id = "s1", provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = SessionState.IDLE,
            keptProcess = KeptProcess(harness, 1_000, "claude"), lastOutputAt = now, worktreePath = worktree,
        )
        win.start(gradleDaemon, 777_777L, "\"C:\\jdk\\bin\\java.exe\" -Xmx3g -cp gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.4.1", 6_000, memMb = 2_000)
        daemonLog(gradleDaemon, worktree)
        val l = ProcessLedger(win, null, "me", { now }, profiles = { profiles }, daemonDirs = reader)
        ProcessWatch(l, win, { ProcessWatchConfig() }, { now }, enabled = { true }, log = {}).tick()
        val e = l.get(gradleDaemon)
        assertEquals("s1", e?.sessionId)
        assertTrue(e!!.inferred)
    }

    @Test fun `a daemon log naming no session worktree, or an older log of a recycled pid, adopts nothing`() {
        session("s1")
        orphanDaemon()
        daemonLog(gradleDaemon, "C:\\work\\.worktrees\\ticket-aaa") // a prefix of no worktree boundary: ticket-aaa is not ticket-aaa-fix
        sessions["s1"] = sessions.getValue("s1").copy(worktreePath = "C:\\work\\.worktrees\\ticket-aaa-fix")
        val l = ledger()
        watch(l).tick()
        assertNull(l.get(gradleDaemon))

        val log = daemonLog(gradleDaemon, worktree)
        os.exit(gradleDaemon)
        orphanDaemon(at = log.lastModified() + 60_000) // the pid now belongs to a newer daemon; the log is the old one's
        val l2 = ProcessLedger(os, null, "me", { now }, profiles = { profiles }, daemonDirs = DaemonDirReader(env = { gradleHome.path }, home = ""))
        watch(l2).tick()
        assertNull(l2.get(gradleDaemon))
    }

    @Test fun `a daemon with a live parent outside the ledger (the owner's own build) is never adopted by its log`() {
        session("s1")
        os.start(555_000L, null, "C:\\Program Files\\JetBrains\\idea64.exe", 500)
        os.start(gradleDaemon, 555_000L, "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.4.1", 6_000, memMb = 2_000)
        daemonLog(gradleDaemon, worktree)
        val l = ledger()
        watch(l).tick()
        assertNull(l.get(gradleDaemon))
    }

    @Test fun `with no daemonDir in any profile the orphan stays unattributed`() {
        session("s1")
        orphanDaemon()
        daemonLog(gradleDaemon, worktree)
        os.profiles = DEFAULTS
        val l = ProcessLedger(os, null, "me", { now }, profiles = { DEFAULTS }, daemonDirs = reader)
        watch(l).tick()
        assertNull(l.get(gradleDaemon))
    }

    @Test fun `the reader follows the log incrementally, the last match wins, and an unset variable skips its template`() {
        val log = daemonLog(gradleDaemon, "C:/a")
        val p = ProcInfo(gradleDaemon, null, "GradleDaemon", log.lastModified())
        assertEquals("C:/a", reader.dirOf(p, dirSpec))
        log.appendText("partial line without newline Received command: Build{id=x, currentDir=C:/b}")
        assertEquals("C:/a", reader.dirOf(p, dirSpec), "a partial last line waits for its newline")
        log.appendText(".\n")
        assertEquals("C:/b", reader.dirOf(p, dirSpec))
        assertNull(DaemonDirReader(env = { null }, home = "").expand("\${env:GRADLE_USER_HOME}/daemon", 1))
        assertEquals("/h/.gradle/daemon-7.log", DaemonDirReader(env = { null }, home = "/h").expand("\${home}/.gradle/daemon-\${pid}.log", 7))
    }

    @Test fun `the bundled gradle profile carries the daemonDir source`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "superiorSoftLibrary/infantry/src/main/resources/watch/tool-profiles.json") }.firstOrNull { it.isFile }
        assertTrue(file != null, "tool-profiles.json not found above the test's working directory")
        val text = file.readText()
        val bundled = Regex("(?s)\"daemonDir\".*?\"match\":\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertEquals(dirSpec.match, bundled)
        assertTrue(dirSpec.paths.all { text.contains(it) })
        val line = "2026-11-04T22:29:44.373-0500 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] " +
            "Received command: Build{id=c0219f33-10f4-4dfe-b111-c61d0d4b446a, currentDir=C:\\Users\\me\\proj\\.worktrees\\ticket-x}."
        assertEquals("C:\\Users\\me\\proj\\.worktrees\\ticket-x", Regex(dirSpec.match).find(line)?.groupValues?.get(1))
    }
}
