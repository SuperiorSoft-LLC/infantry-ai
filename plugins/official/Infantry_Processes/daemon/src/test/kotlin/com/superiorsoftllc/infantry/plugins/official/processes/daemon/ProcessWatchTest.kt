// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * STACK-NEUTRAL-2: ProcessWatchTest ported from core. The watch runs against a FAKE `api.processes` ([FakeOs]): its process table,
 * memory, sessions, gate lock and start-instant-checked kill. Same scenarios, same expectations as the core test it replaces.
 */

internal enum class SessionState { IDLE, BUSY, EXITED, FAILED, ORPHANED }

internal data class KeptProcess(val pid: Long, val startedAt: Long?, val label: String)

internal data class BgJob(val id: String, val label: String, val pid: Long, val startedAt: Long, val keep: Boolean = false)

/** What the host would know of a session; [watched] is what `api.processes.sessions()` shows the plugin. */
internal data class SessionRecord(
    val id: String,
    val provider: String,
    val workingDir: String,
    val spawnedAt: Long,
    val lastHeartbeat: Long,
    val state: SessionState,
    val keptProcess: KeptProcess? = null,
    val lastOutputAt: Long? = null,
    val worktreePath: String? = null,
    val bgJobs: List<BgJob> = emptyList(),
    val ticketId: String? = null,
) {
    fun watched() = WatchedSession(
        id, state.name, state == SessionState.BUSY, bgJobs.isNotEmpty(), lastOutputAt ?: lastHeartbeat,
        listOfNotNull(keptProcess?.pid).distinct(), worktreePath, ticketId, keepPidsOf(this), bgJobs.map { it.pid },
    )
}

internal fun keepPidsOf(r: SessionRecord): Set<Long> = (r.bgJobs.filter { it.keep }.map { it.pid } + listOfNotNull(r.keptProcess?.pid)).toSet()

/** The gate lock directory, as core's GateLock reads and releases it. */
internal class FileGate(private val dir: () -> File?, private val pidAlive: (Long) -> Boolean) : GateLockView {
    override fun read(): GateOwner? {
        val d = dir()?.takeIf { it.isDirectory } ?: return null
        val fields = runCatching { File(d, "owner.txt").takeIf { it.isFile }?.readText() }.getOrNull()
            ?.split(Regex("\\s+"))?.mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }
            ?.toMap().orEmpty()
        val pid = fields["pid"]?.toLongOrNull() ?: runCatching { File(d, "pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
        return GateOwner(pid, fields["ticket"]?.ifBlank { null }, fields["session"]?.ifBlank { null })
    }

    override fun holderAlive(owner: GateOwner) = owner.pid?.let(pidAlive) == true

    override fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long>): String? {
        val d = dir()?.takeIf { it.isDirectory } ?: return null
        val owner = read() ?: return null
        if (!owner.named) return null
        val names = owner.session == sessionId ||
            (ticketId != null && owner.ticket?.removePrefix("ticket-") != null && owner.ticket?.removePrefix("ticket-") == ticketId.removePrefix("ticket-"))
        if (!names) return null
        val holderGone = owner.pid == null || owner.pid?.let(pidAlive) != true || owner.pid in killedPids
        if (!holderGone || !d.deleteRecursively()) return null
        return "gate lock ${d.path} released"
    }
}

/** The bundled profiles the watch reads (gradle, kotlin, runtimes, mcp), as `api.processes.toolProfiles()` shows them. */
internal val DEFAULTS = listOf(
    ProcessToolProfile(
        "gradle", daemons = listOf("GradleDaemon", "org\\.gradle\\.launcher\\.daemon"), sharedDaemon = true,
        clients = listOf("gradlew", "GradleWrapperMain", "GradleMain"), heavyImages = listOf("gradle.*"), heavyCommands = listOf("gradle"),
        env = listOf(
            ProcessEnvTemplate("GRADLE_OPTS", "-Dorg.gradle.workers.max=\${maxWorkers}", "maxWorkers"),
            ProcessEnvTemplate("GRADLE_OPTS", "-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process"),
            ProcessEnvTemplate("GRADLE_OPTS", "-Dorg.gradle.project.infantry.testForks=\${maxParallelForks}", "maxParallelForks"),
            ProcessEnvTemplate("GRADLE_OPTS", "-Dorg.gradle.project.infantry.uiTestForks=\${maxParallelForks}", "maxParallelForks"),
        ),
    ),
    ProcessToolProfile("kotlin", daemons = listOf("KotlinCompileDaemon", "kotlin-daemon"), sharedDaemon = true, heavyImages = listOf("kotlin.*"), heavyCommands = listOf("kotlin")),
    ProcessToolProfile(
        "runtimes",
        heavyImages = listOf("java", "javaw", "node", "opencode", "docker.*", "com\\.docker.*", "vmmem.*", "wsl.*", "bun", "pi"),
        heavyCommands = listOf("java", "node"),
    ),
    ProcessToolProfile("mcp", heavyCommands = listOf("npx", "playwright", "modelcontextprotocol")),
)

/** The daemon, its ancestors and the app's own runtime, as the host guards them. */
private val PROTECTED = generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.map { it.pid() }.toSet()

/** A fake `api.processes`: live processes by pid; a parent that dies leaves its children with a dangling parent pid (Windows). */
internal open class FakeOs : Processes {
    val procs = LinkedHashMap<Long, ProcInfo>()
    val mb = HashMap<Long, Long>()
    val killed = mutableListOf<Long>()
    val alone = mutableListOf<Long>()
    var free: Long? = 16_000
    var profiles: List<ProcessToolProfile> = DEFAULTS
    var sessionList: () -> List<WatchedSession> = { emptyList() }
    var gate: GateLockView = NoProcesses.gateLock
    val cpuNs = HashMap<Long, Long>()

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
    override fun snapshot() = procs.values.toList()
    override fun startInstant(pid: Long) = procs[pid]?.startedAt

    override fun kill(pid: Long, startedAt: Long, tree: Boolean): KillResult {
        if (procs[pid]?.startedAt != startedAt) return KillResult.Refused("gone")
        if (tree) (descendants(pid).map { it.pid } + pid).forEach { killed += it; procs.remove(it) }
        else { killed += pid; alone += pid; procs.remove(pid) }
        return KillResult.Killed(pid, tree)
    }

    override fun isProtected(p: ProcInfo) = p.pid in PROTECTED
    override val ownerId: String? = "me"
    override fun freeMb() = free
    override fun totalMb() = 32_000L
    override fun memory() = procs.values.map { ProcMem(it.pid, it.command.substringBefore(' '), mb[it.pid] ?: 0) }
    override fun cpuNanos(pid: Long) = cpuNs[pid]
    override fun sessions() = sessionList()
    override fun onSessionClosed(listener: (SessionClosed) -> Unit) = AutoCloseable {}
    override fun onDaemonShutdown(listener: () -> Unit) = AutoCloseable {}
    override val gateLock: GateLockView get() = gate
    override fun toolProfiles() = profiles
    override fun toolProfilesEnv() = "INFANTRY_TOOL_PROFILES" to "[]"
    override fun shimDir(): String? = null
    override fun inheritedEnv(name: String): String? = null
    override fun serve(handler: ProcessesHandler) = AutoCloseable {}
    override fun publishPressure(reportJson: String) {}
    override fun raiseAlert(outcome: String, detail: String) {}
}

/** ROGUE-1: the ledger proves ownership by pid + start instant; only provably ours, ended or idle-heavy, is reaped. */
class ProcessWatchTest {
    private var now = 10_000_000L
    private val os = FakeOs()
    private val sessions = LinkedHashMap<String, SessionRecord>()

    init {
        os.sessionList = { sessions.values.map { it.watched() } }
    }

    private fun session(id: String, kept: Long, state: SessionState = SessionState.IDLE, lastOutputAt: Long? = now) {
        sessions[id] = SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = state,
            keptProcess = KeptProcess(kept, os.procs[kept]?.startedAt, "claude"), lastOutputAt = lastOutputAt,
        )
    }

    private fun watch(
        ledger: ProcessLedger = ProcessLedger(os, clock = { now }),
        config: ProcessWatchConfig = ProcessWatchConfig(),
        pressure: MutableList<ProcessReport> = mutableListOf(),
        lockAlive: Boolean = false,
        lockHolder: Long? = null,
        gateLock: GateLockView? = null,
        profiles: List<ProcessToolProfile> = DEFAULTS,
        enabled: () -> Boolean = { true },
    ): ProcessWatch {
        os.profiles = profiles
        os.gate = gateLock ?: object : GateLockView {
            override fun read() = if (lockHolder != null || lockAlive) GateOwner(lockHolder ?: 1L, null, null) else null
            override fun holderAlive(owner: GateOwner) = lockAlive || (owner.pid != null && owner.pid in os.procs && lockHolder != null)
            override fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long>): String? = null
        }
        return ProcessWatch(ledger, os, { config }, { now }, onPressure = { pressure += it }, enabled = enabled, log = {})
    }

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

    private val pwNpx = "node C:/npm/bin/npx-cli.js -y @playwright/mcp@latest --browser chrome"
    private val pwSrv = "node C:/cache/_npx/9833/node_modules/.bin/playwright-mcp"

    @Test fun `a playwright mcp pair under a live session is adopted, named for it, and killed with the session close`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1", harness)
        val w = watch()
        w.tick()
        os.start(900_400L, harness, pwNpx, 2_000, memMb = 140)
        os.start(900_401L, 900_400L, pwSrv, 2_100, memMb = 95)
        w.tick()
        val rep = w.report().tracked
        assertTrue(rep.single { it.pid == 900_401L }.command.startsWith("Playwright MCP for session s1 | "))
        sessions.remove("s1")
        w.reapSession("s1", now)
        assertTrue(900_400L in os.killed && 900_401L in os.killed)
    }

    @Test fun `STACK-NEUTRAL-1 - an mcp server no session started is the owner's - never listed, never reaped, not even by hand`() {
        os.start(900_500L, 777_777L, pwNpx, 3_000, memMb = 400)
        os.start(900_501L, 900_500L, pwSrv, 3_100, memMb = 300)
        val w = watch(config = ProcessWatchConfig(autoReap = true))
        w.tick()
        assertTrue(w.report().unattributed.none { it.pid == 900_500L || it.pid == 900_501L })
        assertTrue(w.reapRogue().isEmpty() && w.reapSession("none", now).isEmpty())
        assertIs<ReapResult.Refused>(w.reap(900_500L))
        assertTrue(os.killed.isEmpty())
    }

    @Test fun `STACK-NEUTRAL-1 - an opencode serve no session started is never listed or reaped, nor is any stranger`() {
        val oc = 900_600L
        os.start(oc, null, "opencode.exe serve --port 47123 --hostname 127.0.0.1", 3_000, memMb = 1_700)
        os.start(900_601L, null, "node unrelated.js", 3_000, memMb = 1_700)
        val w = watch(config = ProcessWatchConfig(autoReap = true))
        w.tick()
        assertTrue(w.report().unattributed.none { it.pid == oc })
        assertTrue(w.reapRogue().isEmpty())
        assertIs<ReapResult.Refused>(w.reap(900_601L))
        assertIs<ReapResult.Refused>(w.reap(oc))
        assertTrue(os.killed.isEmpty())
    }

    @Test fun `STACK-NEUTRAL-1 - a process the owner started by hand in a worktree (live parent) is never adopted or killed`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1", harness)
        sessions["s1"] = sessions.getValue("s1").copy(worktreePath = "C:/work/.worktrees/ticket-aaa-fix")
        val ownerShell = 900_650L
        os.start(ownerShell, null, "pwsh.exe", 500)
        os.start(900_651L, ownerShell, "node C:/work/.worktrees/ticket-aaa-fix/node_modules/.bin/vite", 6_000, memMb = 600)
        val ledger = ProcessLedger(os, clock = { now })
        val w = watch(ledger)
        w.tick()
        assertNull(ledger.get(900_651L))
        close()
        w.reapSession("s1", now)
        assertTrue(900_651L in os.procs && 900_651L !in os.killed)
    }

    @Test fun `STACK-NEUTRAL-1 - worktree adoption needs a path boundary`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1", harness)
        sessions["s1"] = sessions.getValue("s1").copy(worktreePath = "C:/work/.worktrees/ticket-aaa")
        os.start(gradleDaemon, 777_777L, "java GradleDaemon -Duser.dir=C:/work/.worktrees/ticket-aaa-fix", 6_000, memMb = 900)
        os.start(kotlinDaemon, 777_778L, "java KotlinCompileDaemon C:\\work\\.worktrees\\ticket-aaa\\build", 6_000, memMb = 900)
        val ledger = ProcessLedger(os, clock = { now })
        watch(ledger).tick()
        assertNull(ledger.get(gradleDaemon), "ticket-aaa is not ticket-aaa-fix")
        assertEquals("s1", ledger.get(kotlinDaemon)?.sessionId)
    }

    @Test fun `STACK-NEUTRAL-1 - with no profile naming it, an orphan is never adopted by path`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1", harness)
        sessions["s1"] = sessions.getValue("s1").copy(worktreePath = "C:/work/.worktrees/ticket-aaa-fix")
        os.start(gradleDaemon, 777_777L, "java GradleDaemon -Duser.dir=C:/work/.worktrees/ticket-aaa-fix", 6_000, memMb = 900)
        val ledger = ProcessLedger(os, clock = { now }, profiles = { emptyList() })
        watch(ledger, profiles = emptyList()).tick()
        assertNull(ledger.get(gradleDaemon))
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

    @Test fun `DISCONNECT-1 - a java --add-opens lookalike that no session started is never killed by a close, a reap, or reap-all`() {
        val lookalike = 900_998L
        os.start(lookalike, null, "\"C:/Program Files/Infantry AI/runtime/bin/java.exe\" --add-opens java.base/java.lang=ALL-UNNAMED -jar app.jar", 2_000, memMb = 4_000)
        gradleUnder("s1")
        val w = watch()
        w.tick()
        sessions.remove("s1")
        w.reapSession("s1", now)
        assertIs<ReapResult.Refused>(w.reap(lookalike))
        w.reapRogue()
        assertTrue(lookalike in os.procs)
        assertFalse(lookalike in os.killed)
    }

    @Test fun `protected processes are never recorded`() {
        gradleUnder("s1")
        val ledger = ProcessLedger(os, clock = { now }, isProtected = { it.pid == gradleDaemon })
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
        val ledger = ProcessLedger(os, clock = { now })
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
            val f = File(dir, "process-ledger.json")
            gradleUnder("s1")
            ProcessLedger(os, f, "me", { now }).observe(mapOf("s1" to listOf(harness)))
            assertEquals(3, ProcessLedger(os, f, "me", { now }).entries().size)
            assertTrue(ProcessLedger(os, f, "other", { now }).entries().isEmpty())
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

    // --- ticket b785f383: close-time cleanup ---------------------------------------------------------------------

    private val bash = 900_400L
    private val conhost = 900_401L
    private val node = 900_402L

    /** s1's harness ran bash (with its conhost) running node, and gradlew whose daemon outlives it. */
    private fun busyTree(id: String = "s1") {
        gradleUnder(id)
        os.start(bash, harness, "bash.exe -c npm run dev", 7_000)
        os.start(conhost, bash, "conhost.exe 0x4", 7_001)
        os.start(node, bash, "node server.js", 7_100)
    }

    /** The session closes: the host stopped its harness (and what still hung under it is now orphaned). */
    private fun close(id: String = "s1", state: SessionState = SessionState.EXITED) {
        os.exit(harness)
        sessions[id] = sessions.getValue(id).copy(state = state, keptProcess = null)
    }

    @Test fun `closing a session kills its whole ledger tree, leaf first`() {
        busyTree()
        val w = watch()
        w.tick()
        os.exit(gradlew)
        close()
        val reaped = w.reapSession("s1", now, reason = "session killed").map { it.pid }
        assertEquals(setOf(bash, conhost, node, gradleDaemon), reaped.toSet())
        assertTrue(reaped.indexOf(node) < reaped.indexOf(bash) && reaped.indexOf(conhost) < reaped.indexOf(bash), "leaf first: $reaped")
        assertTrue(listOf(bash, conhost, node, gradleDaemon).none { it in os.procs })
        assertTrue(w.report().tracked.isEmpty())
        assertTrue(w.reapSession("s1", now).isEmpty(), "a second close is a no-op")
    }

    @Test fun `close spares foreign and recycled pids and the other sessions`() {
        busyTree()
        os.start(stranger, null, "java Unrelated", 2_000)
        val other = 900_500L
        val otherChild = 900_501L
        os.start(other, null, "claude --other", 1_500)
        os.start(otherChild, other, "bash.exe -c sleep", 8_000)
        session("s2", other)
        val w = watch()
        w.tick()
        close()
        // node's pid is recycled between the last tick and the close: same number, another process
        os.exit(node)
        os.start(node, null, "notepad.exe", 50_000)
        w.reapSession("s1", now)
        assertTrue(stranger in os.procs && other in os.procs && otherChild in os.procs, "other sessions and strangers untouched")
        assertTrue(node in os.procs && os.procs.getValue(node).startedAt == 50_000L, "recycled pid untouched")
        assertFalse(node in os.killed || stranger in os.killed || otherChild in os.killed)
        assertTrue(bash !in os.procs)
    }

    @Test fun `close releases the gate lock its owner txt names for the session, and only that one`() {
        busyTree()
        val lockDir = java.io.File(kotlin.io.path.createTempDirectory("gl").toFile(), "infantry-gate.lock").apply { mkdirs() }
        java.io.File(lockDir, "owner.txt").writeText("session=s1 pid=$bash")
        val lock: GateLockView = FileGate({ lockDir }) { it in os.procs }
        val w = watch(gateLock = lock)
        w.tick()
        close()
        w.reapSession("s2", now)
        assertTrue(lockDir.isDirectory, "another session closing keeps the lock")
        w.reapSession("s1", now)
        assertFalse(lockDir.exists(), "the owner closed, its holder was killed with it")
    }

    @Test fun `a lock owned by a live foreign holder or with no owner txt survives the close, and the catch-up frees an ended owner`() {
        busyTree()
        val lockDir = java.io.File(kotlin.io.path.createTempDirectory("gl").toFile(), "infantry-gate.lock").apply { mkdirs() }
        java.io.File(lockDir, "pid").writeText("$stranger")
        os.start(stranger, null, "bash gate.sh", 2_000)
        val lock: GateLockView = FileGate({ lockDir }) { it in os.procs }
        val w = watch(gateLock = lock)
        w.tick()
        close()
        w.reapSession("s1", now)
        assertTrue(lockDir.isDirectory, "no owner.txt: kept")
        java.io.File(lockDir, "owner.txt").writeText("session=s1 pid=$stranger")
        w.tick()
        assertTrue(lockDir.isDirectory, "owner ended but the holder is alive: kept")
        os.exit(stranger)
        w.tick()
        assertFalse(lockDir.exists(), "owner ended and holder gone: the catch-up releases it")
    }

    @Test fun `a kept bg job and everything under it survive, its parent goes alone`() {
        busyTree()
        val wrapper = 900_600L
        val gate = 900_601L
        os.start(wrapper, bash, "infantry bg run --keep -- gradlew test", 7_200)
        os.start(gate, wrapper, "cmd /c gradle test", 7_300)
        val w = watch()
        w.tick()
        sessions["s1"] = sessions.getValue("s1").copy(bgJobs = listOf(BgJob("j", "gate", wrapper, 7_200, keep = true)))
        close()
        val keep = keepPidsOf(sessions.getValue("s1"))
        assertEquals(setOf(wrapper), keep)
        w.reapSession("s1", now, keep)
        assertTrue(wrapper in os.procs && gate in os.procs, "kept job survives")
        assertTrue(bash !in os.procs && node !in os.procs)
        assertTrue(bash in os.alone, "the kept job's parent is killed alone, not as a tree")
    }

    @Test fun `a shared gradle daemon is spared for a live gate-lock holder, handed to a live gradle client, else killed`() {
        // spared: the gate-lock holder (outside this session) is alive
        gradleUnder("s1")
        val holder = 900_700L
        os.start(holder, null, "bash gate.sh", 500)
        var w = watch(lockHolder = holder)
        w.tick()
        os.exit(gradlew)
        close()
        w.reapSession("s1", now)
        assertTrue(gradleDaemon in os.procs)

        // handed over: another live session runs a gradle client
        val ledger = ProcessLedger(os, clock = { now })
        w = watch(ledger)
        val h2 = 900_710L
        os.start(h2, null, "claude --s2", 1_000)
        os.start(900_711L, h2, "cmd /c gradlew build", 9_000)
        session("s2", h2)
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.IDLE)
        os.start(harness, null, "claude --session", 1_000)
        os.procs[gradleDaemon] = os.procs.getValue(gradleDaemon).copy(parentPid = harness)
        sessions["s1"] = sessions.getValue("s1").copy(keptProcess = KeptProcess(harness, 1_000, "claude"))
        w.tick()
        assertEquals("s1", ledger.get(gradleDaemon)!!.sessionId)
        close()
        w.reapSession("s1", now)
        assertTrue(gradleDaemon in os.procs)
        assertEquals("s2", ledger.get(gradleDaemon)!!.sessionId)

        // nobody else uses it: killed with its session
        sessions["s2"] = sessions.getValue("s2").copy(state = SessionState.EXITED)
        w.reapSession("s2", now)
        assertFalse(gradleDaemon in os.procs)
    }

    @Test fun `restart never kills the new run's fresh processes`() {
        busyTree()
        val w = watch()
        w.tick()
        val closedAt = 8_000L
        val fresh = 900_800L
        val freshChild = 900_801L
        os.exit(harness)
        os.start(fresh, null, "claude --session", 9_000)
        os.start(freshChild, fresh, "bash.exe -c ls", 9_100)
        sessions["s1"] = sessions.getValue("s1").copy(keptProcess = KeptProcess(fresh, 9_000, "claude"))
        w.tick() // the new run lands in the ledger under the same session id
        w.reapSession("s1", closedAt, reason = "session restarted")
        assertTrue(fresh in os.procs && freshChild in os.procs)
        assertTrue(bash !in os.procs && node !in os.procs && gradleDaemon !in os.procs)
    }

    @Test fun `the daemon's own process is never killed even when recorded`() {
        val me = ProcessHandle.current().pid()
        os.start(me, harness, "java InfantryDaemon", 3_000)
        gradleUnder("s1")
        val w = watch()
        w.tick()
        close()
        w.reapSession("s1", now)
        assertTrue(me !in os.killed)
    }

    @Test fun `reapEnded takes ended and removed sessions only`() {
        busyTree()
        val other = 900_500L
        val otherChild = 900_501L
        os.start(other, null, "claude --other", 1_500)
        os.start(otherChild, other, "bash.exe -c sleep", 8_000)
        session("s2", other)
        val w = watch()
        w.tick()
        os.exit(harness)
        sessions.remove("s1")
        w.reapEnded("daemon shutdown")
        assertTrue(listOf(bash, node, conhost, gradlew, gradleDaemon).none { it in os.procs })
        assertTrue(other in os.procs && otherChild in os.procs, "a live session is untouched")
    }

    // STACK-NEUTRAL-1: the watch reads what a build daemon is from the tool profiles, and has an off switch.

    @Test fun `watch off - nothing is recorded, nothing is killed, the report carries free RAM only`() {
        busyTree()
        val ledger = ProcessLedger(os, clock = { now })
        val w = watch(ledger, config = ProcessWatchConfig(autoReap = true), enabled = { false })
        w.tick()
        assertTrue(ledger.entries().isEmpty(), "off: the ledger records nothing")
        close()
        assertTrue(w.reapSession("s1", now).isEmpty())
        assertTrue(w.reapEnded("daemon shutdown").isEmpty())
        assertTrue(w.reapRogue().isEmpty())
        assertIs<ReapResult.Refused>(w.reap(node))
        assertTrue(os.killed.isEmpty(), "off: nothing killed")
        val r = w.report()
        assertEquals(16_000L, r.freeMb)
        assertTrue(r.tracked.isEmpty() && r.unattributed.isEmpty())
    }

    @Test fun `watch switched on later picks the tree up from then on`() {
        busyTree()
        var on = false
        val ledger = ProcessLedger(os, clock = { now })
        val w = watch(ledger, enabled = { on })
        w.tick()
        assertTrue(ledger.entries().isEmpty())
        on = true
        w.tick()
        assertTrue(ledger.get(node) != null && ledger.get(gradleDaemon) != null)
    }

    @Test fun `no profile names gradle - its daemon is a plain tree member, killed with its session even for a live gate holder`() {
        gradleUnder("s1")
        val holder = 900_720L
        os.start(holder, null, "bash gate.sh", 500)
        val w = watch(lockHolder = holder, profiles = emptyList())
        w.tick()
        os.exit(gradlew)
        close()
        w.reapSession("s1", now)
        assertFalse(gradleDaemon in os.procs, "generic behaviour: no shared-daemon sparing without a profile")
    }

    @Test fun `a non-gradle shared daemon from a data profile is spared for a live gate holder and handed to its client`() {
        val acme = ProcessToolProfile(
            name = "acme", daemons = listOf("acme-build-server"), sharedDaemon = true,
            clients = listOf("""\bacme build\b"""), heavyImages = listOf("acme.*"),
        )
        val server = 900_730L
        val client = 900_731L
        os.start(harness, null, "claude --session", 1_000)
        os.start(client, harness, "acme build all", 5_000)
        os.start(server, client, "acme-build-server --port 9", 6_000, memMb = 700)
        session("s1", harness)
        val holder = 900_732L
        os.start(holder, null, "bash gate.sh", 500)
        val w = watch(lockHolder = holder, profiles = listOf(acme))
        w.tick()
        os.exit(client)
        close()
        w.reapSession("s1", now)
        assertTrue(server in os.procs, "spared: the gate-lock holder may use the shared acme daemon")

        // without the gate holder, handed to another live session that runs an acme client
        val ledger = ProcessLedger(os, clock = { now })
        val w2 = watch(ledger, profiles = listOf(acme))
        val h2 = 900_740L
        os.start(h2, null, "claude --s2", 1_000)
        os.start(900_741L, h2, "acme build web", 9_000)
        session("s2", h2)
        os.start(harness, null, "claude --session", 1_000)
        os.procs[server] = os.procs.getValue(server).copy(parentPid = harness)
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.IDLE, keptProcess = KeptProcess(harness, 1_000, "claude"))
        w2.tick()
        close()
        w2.reapSession("s1", now)
        assertTrue(server in os.procs)
        assertEquals("s2", ledger.get(server)!!.sessionId)
    }

    @Test fun `an unattributed orphan daemon is named by its profile in the reason`() {
        os.start(gradleDaemon, 777_777L, "java GradleDaemon 8.5", 6_000, memMb = 900)
        val r = watch().report().unattributed.single { it.pid == gradleDaemon }
        assertTrue(r.rogue)
        assertEquals("orphan build daemon (gradle): no owning session, no live gate-lock holder", r.reason)
    }
}

/** Ticket 66153313: CPU share per tracked process and the sustained-CPU alert. */
class ProcessWatchCpuTest {
    private class Os : FakeOs()

    private var now = 10_000_000L
    private val os = Os()
    private val harness = 910_100L
    private val gradle = 910_101L
    init {
        os.cpuNs[harness] = 0; os.cpuNs[gradle] = 0
    }
    private val sessions = listOf(
        SessionRecord(
            id = "s1", provider = "pi", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = SessionState.BUSY,
            keptProcess = KeptProcess(harness, 1_000, "pi"),
        ),
    )
    private var cfg = CpuAlertConfig(percent = 50, minutes = 2)
    private val alerts = mutableListOf<ProcessReport>()

    private fun watch(): ProcessWatch {
        os.sessionList = { sessions.map { it.watched() } }
        return ProcessWatch(ProcessLedger(os, clock = { now }), os, clock = { now }, cores = 4, cpuAlert = { cfg }, log = {})
            .also { it.onCpuSustained = { r -> alerts += r } }
    }

    /** Advance [seconds]; the harness burns [harnessCores] cores and gradle [gradleCores] meanwhile. */
    private fun pass(w: ProcessWatch, seconds: Long, harnessCores: Double, gradleCores: Double) {
        now += seconds * 1000
        os.cpuNs[harness] = (os.cpuNs[harness] ?: 0) + (harnessCores * seconds * 1e9).toLong()
        os.cpuNs[gradle] = (os.cpuNs[gradle] ?: 0) + (gradleCores * seconds * 1e9).toLong()
        w.tick()
    }

    @Test fun `each tracked process shows its share of all cores and the report sums them`() {
        os.procs[harness] = ProcInfo(harness, null, "pi --mode rpc", 1_000)
        os.procs[gradle] = ProcInfo(gradle, harness, "java GradleDaemon", 2_000)
        val w = watch()
        w.tick()
        assertNull(w.report().agentCpuPercent, "one sample is no share yet")
        pass(w, 10, harnessCores = 0.4, gradleCores = 2.0)
        val r = w.report()
        assertEquals(10.0, r.tracked.single { it.pid == harness }.cpuPercent)
        assertEquals(50.0, r.tracked.single { it.pid == gradle }.cpuPercent)
        assertEquals(60.0, r.agentCpuPercent)
        assertEquals(50, r.cpuAlertPercent)
        assertEquals(2, r.cpuAlertMinutes)
    }

    @Test fun `sustained high CPU alerts once per episode, and only after the configured minutes`() {
        os.procs[harness] = ProcInfo(harness, null, "pi --mode rpc", 1_000)
        os.procs[gradle] = ProcInfo(gradle, harness, "java GradleDaemon", 2_000)
        val w = watch()
        w.tick()
        pass(w, 10, 0.0, 3.6) // 90 %: the episode starts
        val since = w.report().cpuHighSince
        assertEquals(now, since)
        repeat(10) { pass(w, 10, 0.0, 3.6) } // 100 s in: under 2 min
        assertTrue(alerts.isEmpty())
        repeat(3) { pass(w, 10, 0.0, 3.6) } // 130 s in
        assertEquals(1, alerts.size)
        assertEquals(90.0, alerts.single().agentCpuPercent)
        repeat(20) { pass(w, 10, 0.0, 3.6) }
        assertEquals(1, alerts.size, "one alert per episode")

        pass(w, 10, 0.0, 0.4) // drops to 10 %: rearmed
        assertNull(w.report().cpuHighSince)
        repeat(14) { pass(w, 10, 0.0, 3.6) }
        assertEquals(2, alerts.size, "a new episode alerts again")
    }

    @Test fun `zero minutes turns the alert off`() {
        cfg = CpuAlertConfig(percent = 10, minutes = 0)
        os.procs[harness] = ProcInfo(harness, null, "pi --mode rpc", 1_000)
        val w = watch()
        w.tick()
        repeat(100) { pass(w, 10, 4.0, 0.0) }
        assertTrue(alerts.isEmpty())
        assertNull(w.report().cpuHighSince)
    }

    @Test fun `a pid whose counter goes backwards (reused) has no share`() {
        val m = CpuMeter({ pid -> if (pid == 1L) 5_000_000_000L else null }, cores = 2)
        m.sample(listOf(1L), 0)
        assertEquals(mapOf(1L to 0.0), m.sample(listOf(1L), 1_000))
        var n = 10_000_000_000L
        val back = CpuMeter({ n.also { n -= 1_000_000_000L } }, cores = 2)
        back.sample(listOf(1L), 0)
        assertTrue(back.sample(listOf(1L), 1_000).isEmpty())
    }
}
