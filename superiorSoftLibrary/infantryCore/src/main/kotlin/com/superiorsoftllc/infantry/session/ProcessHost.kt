// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.daemon.api.processes.GateLockView
import com.superiorsoftllc.infantry.daemon.api.processes.GateOwner
import com.superiorsoftllc.infantry.daemon.api.processes.KillResult
import com.superiorsoftllc.infantry.daemon.api.processes.LaunchInfo
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessEnvTemplate
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessToolProfile
import com.superiorsoftllc.infantry.daemon.api.processes.Processes
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesHandler
import com.superiorsoftllc.infantry.daemon.api.processes.WatchedSession
import com.superiorsoftllc.infantry.runner.ToolProfile
import com.superiorsoftllc.infantry.runner.ToolProfiles
import com.superiorsoftllc.infantry.session.host.OsProcessTable
import com.superiorsoftllc.infantry.session.host.ProcessTable
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * STACK-NEUTRAL-2: what core keeps of the process watch: the MECHANISMS behind `api.processes` (the OS process table and source, the
 * start-instant-checked kill, the memory and CPU readers, the session roots, the close event, the gate lock) and the per-launch env
 * seam. The policy (the ledger, what is rogue, what is spared, the CPU rule, the build limits env) is Infantry_Processes' daemon half.
 */

typealias ProcMem = com.superiorsoftllc.infantry.daemon.api.processes.ProcMem
typealias SessionClosed = com.superiorsoftllc.infantry.daemon.api.processes.SessionClosed

/** The detector's settings (`processes.*`), as the settings service reads them. The plugin reads the same keys. */
data class ProcessWatchConfig(
    val idleMinutes: Int = 30,
    val heavyMb: Long = 500,
    /** Free RAM below this raises a pressure warning (the same 4 GB floor as the gate rule). */
    val floorMb: Long = 4096,
    val autoReap: Boolean = false,
)

/** Cumulative CPU time of a process, in nanoseconds; null when it is gone or the OS will not say. Injected for tests. */
fun interface CpuSource {
    fun cpuNanos(pid: Long): Long?
}

/** The real one: [ProcessHandle.Info.totalCpuDuration], no subprocess and no native code. */
object OsCpuSource : CpuSource {
    override fun cpuNanos(pid: Long): Long? =
        ProcessHandle.of(pid).flatMap { it.info().totalCpuDuration() }.map { it.toNanos() }.orElse(null)
}

/** Free RAM and per-process memory. Injected so a test can stage pressure. */
interface MemorySource {
    fun freeMb(): Long?
    fun totalMb(): Long?

    /** Every process with its resident memory. Costs a subprocess on the real one: call only on demand. */
    fun processes(): List<ProcMem>
}

/** The real one: the JVM's OS bean for free RAM (no subprocess); `tasklist` (Windows) or `ps` for per-process memory. */
object OsMemorySource : MemorySource {
    private val bean get() = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    override fun freeMb(): Long? = bean?.freeMemorySize?.let { it / MB }
    override fun totalMb(): Long? = bean?.totalMemorySize?.let { it / MB }

    override fun processes(): List<ProcMem> {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val argv = if (windows) listOf("tasklist", "/FO", "CSV", "/NH") else listOf("ps", "-A", "-o", "pid=,rss=,comm=")
        val text = runCatching {
            val p = ProcessBuilder(argv).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullDevice(windows))).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly()
            out
        }.getOrElse { return emptyList() }
        return if (windows) parseTasklist(text) else parsePs(text)
    }

    private fun nullDevice(windows: Boolean) = File(if (windows) "NUL" else "/dev/null")

    /** `"java.exe","1234","Console","1","1,234,567 K"`: the separator in the size depends on the locale. */
    internal fun parseTasklist(text: String): List<ProcMem> = text.lineSequence().mapNotNull { line ->
        val cells = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()
        if (cells.size < 5) return@mapNotNull null
        val pid = cells[1].toLongOrNull() ?: return@mapNotNull null
        val kb = cells[4].filter { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
        ProcMem(pid, cells[0], kb / 1024)
    }.toList()

    /** `  1234 567890 java` (rss in KB). */
    internal fun parsePs(text: String): List<ProcMem> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val pid = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kb = parts[1].toLongOrNull() ?: return@mapNotNull null
        ProcMem(pid, parts[2].substringAfterLast('/'), kb / 1024)
    }.toList()

    private const val MB = 1024L * 1024L
}

/** What a closing session keeps alive, and who may never be killed: core's rules, not the watch's. */
object ProcessGuard {
    /** What a closing session keeps alive: its `bg run --keep` wrappers and a harness the host still holds. */
    fun keepPidsOf(record: SessionRecord): Set<Long> =
        (record.bgJobs.filter { it.keep }.map { it.pid } + listOfNotNull(record.keptProcess?.pid)).toSet()

    /** The daemon and every ancestor (the app that launched it): never recorded, listed or killed. */
    val protectedPids: Set<Long> by lazy {
        generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.map { it.pid() }.toSet()
    }

    /** The daemon, its ancestors, and anything run by the app's own bundled runtime (`java.home`): the infantry CLI, MCP servers. */
    fun isProtected(p: ProcInfo): Boolean {
        if (p.pid in protectedPids) return true
        val home = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return false
        val cmd = p.command.trim().removePrefix("\"")
        return cmd.startsWith(home, ignoreCase = true)
    }
}

/** Session record to the watch's view of it. */
fun SessionRecord.watched(): WatchedSession = WatchedSession(
    id = id,
    state = state.name,
    busy = isBusy(),
    hasBgJobs = bgJobs.isNotEmpty(),
    lastActivityAt = lastOutputAt ?: lastHeartbeat,
    rootPids = listOfNotNull(keptProcess?.pid, childPid).distinct(),
    worktreePath = worktreePath,
    ticketId = ticketId,
    keepPids = ProcessGuard.keepPidsOf(this),
    bgJobPids = bgJobs.map { it.pid },
)

/**
 * The host side of `api.processes`: one per daemon, over the OS and the daemon's own managers. A plugin sees it through a wrapper
 * that scopes it to its tier and its grants (see the host's Api); [kill] is the ONLY path to [ProcessTable.killTree] / `killOne`.
 */
class LocalProcesses(
    private val source: ProcessSource = OsProcessSource,
    private val table: ProcessTable = OsProcessTable,
    private val memory: MemorySource = OsMemorySource,
    private val cpu: CpuSource = OsCpuSource,
    private val sessionList: () -> List<SessionRecord> = { emptyList() },
    private val lock: GateLock = GateLock(),
    private val profiles: () -> List<ToolProfile> = { ToolProfiles.active },
    private val shimDirectory: () -> File? = { null },
    private val inherited: (String) -> String? = System::getenv,
    private val pressure: (String) -> Unit = {},
    private val alert: (String, String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { println("[infantry] $it") },
    private val instanceId: String? = null,
    /** PROC-LEAK-1: the sessions' OS containers (the same the session manager assigns harness pids to). */
    private val containers: com.superiorsoftllc.infantry.runner.SessionContainers = com.superiorsoftllc.infantry.runner.SessionContainers.None,
) {
    private val closedListeners = CopyOnWriteArrayList<(SessionClosed) -> Unit>()
    private val shutdownListeners = CopyOnWriteArrayList<() -> Unit>()
    private val handler = AtomicReference<ProcessesHandler?>(null)

    /** Called by the daemon when a session closes: every listener hears it, one failing never stops the others. */
    fun fireSessionClosed(c: SessionClosed) {
        for (l in closedListeners) runCatching { l(c) }.onFailure { System.err.println("[infantry] session-closed listener failed: ${it.message}") }
    }

    /** Called by the daemon at shutdown, after its sessions stopped. */
    fun fireShutdown() {
        for (l in shutdownListeners) runCatching { l() }.onFailure { System.err.println("[infantry] shutdown listener failed: ${it.message}") }
    }

    /** The plugin's `processes` frame handler, or null (the frame answers "no process watch"). */
    fun frameHandler(): ProcessesHandler? = handler.get()

    /** The service as one plugin [who] sees it. [mayKill] false (a community plugin) refuses [Processes.kill]. */
    fun view(who: String, mayKill: Boolean = true): Processes = object : Processes {
        override fun snapshot() = runCatching { source.all() }.getOrDefault(emptyList())
        override fun info(pid: Long) = runCatching { source.info(pid) }.getOrNull()
        override fun descendants(rootPid: Long) = runCatching { source.descendants(rootPid) }.getOrDefault(emptyList())
        override fun startInstant(pid: Long) = runCatching { table.startInstant(pid) }.getOrNull()
        override fun memory() = runCatching { memory.processes() }.getOrDefault(emptyList())
        override fun freeMb() = runCatching { memory.freeMb() }.getOrNull()
        override fun totalMb() = runCatching { memory.totalMb() }.getOrNull()
        override fun cpuNanos(pid: Long) = runCatching { cpu.cpuNanos(pid) }.getOrNull()

        override fun kill(pid: Long, startedAt: Long, tree: Boolean): KillResult {
            check(mayKill) { "a community plugin may not kill processes" }
            if (pid in ProcessGuard.protectedPids) return KillResult.Refused("pid $pid is Infantry AI itself")
            val live = runCatching { source.info(pid) }.getOrNull()
            if (live != null && ProcessGuard.isProtected(live)) return KillResult.Refused("pid $pid belongs to Infantry AI's own runtime")
            val actual = runCatching { table.startInstant(pid) }.getOrNull()
            if (actual != startedAt) return KillResult.Refused("pid $pid is gone (or now another process); nothing killed")
            val result = runCatching { if (tree) table.killTree(pid, startedAt) else table.killOne(pid, startedAt) }
            result.onFailure { return KillResult.Refused("killing pid $pid failed: ${it.message}") }
            log("process kill: $who asked for pid $pid (${if (tree) "tree" else "alone"})")
            return KillResult.Killed(pid, tree)
        }

        override val ownerId: String? get() = instanceId
        override fun isProtected(p: ProcInfo) = ProcessGuard.isProtected(p)
        override fun sessions(): List<WatchedSession> = sessionList().map { it.watched() }

        override fun sessionMembers(sessionId: String): List<ProcInfo> =
            runCatching { containers.members(sessionId).mapNotNull { pid -> source.info(pid) } }.getOrDefault(emptyList())

        override val containment: String get() = containers.kind

        override fun onSessionClosed(listener: (SessionClosed) -> Unit): AutoCloseable {
            closedListeners += listener
            return AutoCloseable { closedListeners -= listener }
        }

        override fun onDaemonShutdown(listener: () -> Unit): AutoCloseable {
            shutdownListeners += listener
            return AutoCloseable { shutdownListeners -= listener }
        }

        override val gateLock: GateLockView = object : GateLockView {
            override fun read(): GateOwner? = lock.read()?.let { GateOwner(it.pid, it.ticket, it.session) }
            override fun holderAlive(owner: GateOwner) = owner.pid?.let { pid -> runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false) } == true
            override fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long>): String? {
                check(mayKill) { "a community plugin may not release the gate lock" }
                return lock.releaseIfOwnedBy(sessionId, ticketId, killedPids)
            }
        }

        override fun toolProfiles(): List<ProcessToolProfile> = runCatching(profiles).getOrDefault(emptyList()).map { p ->
            ProcessToolProfile(
                p.name, p.daemons, p.sharedDaemon, p.clients, p.heavyImages, p.heavyCommands,
                p.env.map { ProcessEnvTemplate(it.name, it.value, it.onlyIf, it.join, it.inherit) },
                stop = p.stop, stuck = p.stuck,
            )
        }

        override fun toolProfilesEnv(): Pair<String, String> = ToolProfiles.ENV to ToolProfiles.encode(runCatching(profiles).getOrDefault(emptyList()))
        override fun shimDir(): String? = runCatching(shimDirectory).getOrNull()?.absolutePath
        override fun inheritedEnv(name: String): String? = inherited(name)

        override fun serve(handler: ProcessesHandler): AutoCloseable {
            this@LocalProcesses.handler.set(handler)
            return AutoCloseable { this@LocalProcesses.handler.compareAndSet(handler, null) }
        }

        override fun publishPressure(reportJson: String) = pressure(reportJson)
        override fun raiseAlert(outcome: String, detail: String) = alert(outcome, detail)
    }
}

/**
 * The per-launch env seam behind `api.launchEnv`: [base] first (core's own, always applied), then every contributor in
 * registration order. A contributor that throws is skipped and logged once.
 */
class LaunchEnvHost(private val base: (LaunchInfo) -> Map<String, String> = { emptyMap() }) {
    private class Entry(val name: String, val owner: String, val fn: (LaunchInfo) -> Map<String, String>)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun contribute(owner: String, name: String, contributor: (LaunchInfo) -> Map<String, String>): AutoCloseable {
        val e = Entry(name, owner, contributor)
        entries += e
        return AutoCloseable { entries -= e }
    }

    fun collect(info: LaunchInfo): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching { out.putAll(base(info)) }.onFailure { System.err.println("[infantry] launch env (core) failed: ${it.message}") }
        for (e in entries) {
            runCatching { out.putAll(e.fn(info)) }.onFailure {
                if (failed.add("${e.owner}/${e.name}")) System.err.println("[infantry] launch env contributor ${e.owner}/${e.name} skipped: ${it.message}")
            }
        }
        return out
    }
}
