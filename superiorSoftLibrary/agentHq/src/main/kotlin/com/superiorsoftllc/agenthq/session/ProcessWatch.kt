package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.daemon.api.processes.*

import com.superiorsoftllc.agenthq.session.host.ProcessTable
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/*
 * ROGUE-1 (ticket 8a40b481, contract 1.120.0): the rogue background process detector.
 *
 * ROGUE = a [ProcessLedger] entry (so provably started by one of this daemon's sessions) whose owner session was removed
 * or has ended, OR whose owner sits idle (no turn, no bg job, no output for [ProcessWatchConfig.idleMinutes]) while the
 * process holds at least [ProcessWatchConfig.heavyMb]. A live session's own harness process is never rogue by idleness.
 * UNATTRIBUTED = heavy runtimes (java, node, docker, WSL...) the ledger cannot prove are ours: shown, never reaped.
 */

/** The detector's settings. Auto-reap is opt-in. */
@Serializable
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

/**
 * Ticket 66153313: per-process CPU share from two samples of cumulative CPU time: delta CPU / (delta wall x cores) x 100,
 * so 100 means every logical core was busy. A pid seen for the first time (or whose counter went backwards: a reused pid)
 * has no share until its next sample.
 */
class CpuMeter(private val source: CpuSource, private val cores: Int = Runtime.getRuntime().availableProcessors()) {
    private var last: Map<Long, Long> = emptyMap()
    private var lastAt: Long? = null

    /** The latest shares by pid. */
    var shares: Map<Long, Double> = emptyMap()
        private set

    /** Sample [pids] at [nowMs]; returns (and keeps) the shares since the previous sample. */
    fun sample(pids: Collection<Long>, nowMs: Long): Map<Long, Double> {
        val now = pids.associateWith { runCatching { source.cpuNanos(it) }.getOrNull() }.filterValues { it != null }.mapValues { it.value!! }
        val prevAt = lastAt
        val wallNs = prevAt?.let { (nowMs - it) * 1_000_000.0 } ?: 0.0
        shares = if (wallNs <= 0.0) emptyMap() else now.mapNotNull { (pid, ns) ->
            val before = last[pid] ?: return@mapNotNull null
            val delta = ns - before
            if (delta < 0) null else pid to (delta / (wallNs * cores.coerceAtLeast(1)) * 100.0).coerceIn(0.0, 100.0)
        }.toMap()
        last = now
        lastAt = nowMs
        return shares
    }
}

/** Ticket 66153313: true once per episode when the total stays at or above the threshold for the configured minutes. */
class CpuSustain {
    var highSince: Long? = null
        private set
    private var alerted = false

    fun update(total: Double?, cfg: CpuAlertConfig, nowMs: Long): Boolean {
        if (total == null) return false
        if (cfg.minutes <= 0 || total < cfg.percent) {
            highSince = null
            alerted = false
            return false
        }
        val since = highSince ?: nowMs.also { highSince = it }
        if (alerted || nowMs - since < cfg.minutes * 60_000L) return false
        alerted = true
        return true
    }
}

/** One process's memory as the OS lists it. */
data class ProcMem(val pid: Long, val image: String, val mb: Long)

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

    private fun nullDevice(windows: Boolean) = java.io.File(if (windows) "NUL" else "/dev/null")

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

/** Ticket b785f383: one session closing, as [SessionManager.onSessionClosed] reports it. */
data class SessionClosed(val sessionId: String, val at: Long, val keepPids: Set<Long>, val reason: String)

/** What a reap request came to. */
sealed interface ReapResult {
    data class Reaped(val process: TrackedProcess) : ReapResult
    data class Refused(val reason: String) : ReapResult
}

/**
 * The detector: [tick] on a slow timer feeds the ledger from [sessions]; [report] classifies; [reap] / [reapRogue] kill
 * only ledger entries whose pid and start instant still match. [onPressure] hears a report when free RAM drops under the
 * floor, at most once per [pressureEveryMs]; [onReaped] hears each kill (auto or asked).
 */
class ProcessWatch(
    private val ledger: ProcessLedger,
    private val sessions: () -> List<SessionRecord>,
    private val memory: MemorySource,
    private val table: ProcessTable,
    private val config: () -> ProcessWatchConfig = { ProcessWatchConfig() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPressure: (ProcessReport) -> Unit = {},
    private val onReaped: (TrackedProcess) -> Unit = {},
    private val pressureEveryMs: Long = 10 * 60_000L,
    private val autoReapEveryMs: Long = 60_000L,
    /** True while the gate lock's holder pid is alive: its Gradle daemons are not orphans. */
    private val lockHolderAlive: () -> Boolean = ::gateLockHolderAlive,
    /** The gate lock holder's pid, null when there is no lock; a shared Gradle daemon it may use is never closed with a session. */
    private val lockHolderPid: () -> Long? = ::gateLockHolderPid,
    /** Ticket 7e9bed2a: the gate lock a closing session's owner.txt may name; released in the same close-time cleanup. */
    private val gateLock: GateLock = GateLock(),
    /** Ticket 66153313: cumulative CPU per pid, sampled on every [tick]. */
    cpu: CpuSource = OsCpuSource,
    cores: Int = Runtime.getRuntime().availableProcessors(),
    /** Ticket 66153313: the sustained-CPU rule, read on every [tick]. */
    private val cpuAlert: () -> CpuAlertConfig = { CpuAlertConfig() },
) {
    private val cpuMeter = CpuMeter(cpu, cores)
    private val cpuSustain = CpuSustain()

    /** Ticket 66153313: told once per episode when agent CPU stays high (the daemon raises an attention item). */
    @Volatile
    var onCpuSustained: (ProcessReport) -> Unit = {}

    private var lastPressureAt: Long? = null
    private var lastAutoReapAt: Long? = null

    /** The roots of every session that may still own a live process: its kept harness pid and its running child pid. */
    private fun roots(records: List<SessionRecord>): Map<String, Collection<Long>> =
        records.associate { r -> r.id to listOfNotNull(r.keptProcess?.pid, r.childPid).distinct() }.filterValues { it.isNotEmpty() }

    /**
     * Ticket 7e9bed2a: the catch-up for a lock the close-time release could not take (its holder was still dying then, or the
     * daemon was down): the lock's owner.txt names a session this daemon knows as ended and the holder pid is dead.
     * An unknown session, a live holder, or a lock without owner.txt is left alone.
     */
    private fun releaseEndedOwnersLock(records: List<SessionRecord>) {
        val owner = runCatching { gateLock.read() }.getOrNull() ?: return
        val s = owner.session?.let { id -> records.firstOrNull { it.id == id } } ?: return
        if (s.state !in ENDED) return
        runCatching { gateLock.releaseIfOwnedBy(s.id, s.ticketId) }
            .onSuccess { if (it != null) println("[agentHq] gate lock catch-up: $it (session ${s.id} ended)") }
    }

    /** One timer pass: observe; warn under pressure; auto-reap when on. Cheap unless pressure or auto-reap fires. */
    @Synchronized
    fun tick() {
        val records = sessions()
        ledger.observe(roots(records))
        ledger.adopt(records.mapNotNull { r -> r.worktreePath?.let { r.id to it } }.toMap())
        releaseEndedOwnersLock(records)
        val cfg = config()
        val now = clock()
        val shares = cpuMeter.sample(ledger.entries().map { it.pid }, now)
        val total = if (shares.isEmpty() && ledger.entries().isNotEmpty()) null else shares.values.sum()
        val cpuCfg = runCatching(cpuAlert).getOrDefault(CpuAlertConfig())
        if (cpuSustain.update(total, cpuCfg, now)) runCatching { onCpuSustained(report()) }
        val free = runCatching { memory.freeMb() }.getOrNull()
        if (free != null && free < cfg.floorMb && lastPressureAt.let { it == null || now - it >= pressureEveryMs }) {
            lastPressureAt = now
            runCatching { onPressure(report()) }
        }
        if (cfg.autoReap && ledger.entries().isNotEmpty() && lastAutoReapAt.let { it == null || now - it >= autoReapEveryMs }) {
            lastAutoReapAt = now
            reapRogue()
        }
    }

    /** Classify every ledger entry against the current sessions, with memory figures. */
    @Synchronized
    fun report(): ProcessReport {
        val cfg = config()
        val cpuCfg = runCatching(cpuAlert).getOrDefault(CpuAlertConfig())
        val now = clock()
        val bySession = sessions().associateBy { it.id }
        val mem = runCatching { memory.processes() }.getOrDefault(emptyList())
        val mbByPid = mem.associate { it.pid to it.mb }
        val tracked = ledger.entries().map { e ->
            val mb = mbByPid[e.pid]
            val reason = rogueReason(e, bySession[e.sessionId], mb, cfg, now)
            TrackedProcess(
                e.pid, forSession(e.command, e.sessionId), e.sessionId, e.startedAt, mb, reason != null, reason, e.inferred,
                cpuPercent = cpuMeter.shares[e.pid]?.let(::round1),
            )
        }
        val ledgerPids = tracked.mapTo(HashSet()) { it.pid }
        val candidates = mem.filter {
            it.pid !in ledgerPids && it.pid !in protectedPids && HEAVY.matches(it.image) && it.mb >= UNATTRIBUTED_MIN_MB
        }.sortedByDescending { it.mb }.take(UNATTRIBUTED_MAX)
        val candidatePids = candidates.mapTo(HashSet()) { it.pid }
        val nodes = mem.filter { it.pid !in ledgerPids && it.pid !in protectedPids && it.pid !in candidatePids && NODE.matches(it.image) }
        val info = if (candidates.isEmpty() && nodes.isEmpty()) emptyMap() else ledger.allProcesses().associateBy { it.pid }
        val lockAlive = if (candidates.isEmpty()) true else runCatching(lockHolderAlive).getOrDefault(true)
        val unattributed = candidates.map { c ->
            val p = info[c.pid]
            val orphan = p != null && !lockAlive && ORPHAN_DAEMON.containsMatchIn(p.command)
            val manual = p != null && ProcessLedger.isManualReapable(p.command)
            TrackedProcess(
                c.pid, p?.command?.let(ProcessLedger::describe) ?: c.image, null, p?.startedAt, c.mb,
                orphan,
                if (orphan) "orphan Gradle/Kotlin daemon: no owning session, no live gate-lock holder" else if (manual) MANUAL_REASON else null,
            )
        } + nodes.mapNotNull { c ->
            // An MCP server whatever its size (the 200 MB gate hid these). Shown, never rogue, reaped only by hand.
            val p = info[c.pid]?.takeIf { ProcessLedger.MCP_CMD.containsMatchIn(it.command) } ?: return@mapNotNull null
            TrackedProcess(c.pid, ProcessLedger.describe(p.command), null, p.startedAt, c.mb, false, MANUAL_REASON)
        }
        val free = runCatching { memory.freeMb() }.getOrNull()
        return ProcessReport(
            freeMb = free, totalMb = runCatching { memory.totalMb() }.getOrNull(), floorMb = cfg.floorMb,
            underPressure = free != null && free < cfg.floorMb, autoReap = cfg.autoReap,
            tracked = tracked, unattributed = unattributed,
            agentCpuPercent = cpuMeter.shares.takeIf { it.isNotEmpty() }?.values?.sum()?.let(::round1),
            cpuAlertPercent = cpuCfg.percent, cpuAlertMinutes = cpuCfg.minutes, cpuHighSince = cpuSustain.highSince,
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** "Playwright MCP | ..." becomes "Playwright MCP for session abcd1234 | ...". */
    private fun forSession(command: String, sessionId: String): String =
        if (command.startsWith("Playwright MCP | ") || command.startsWith("MCP server | "))
            command.replaceFirst(" | ", " for session ${sessionId.take(8)} | ") else command

    /**
     * Manual reap of a process no session owns: only an MCP server or an `opencode serve`, only while the pid still has the
     * start instant the listing showed; its whole tree goes. Never automatic, never part of a close cleanup.
     */
    private fun reapUnowned(pid: Long): ReapResult {
        val p = ledger.allProcesses().firstOrNull { it.pid == pid }
        val started = p?.startedAt
        if (p == null || started == null || !ProcessLedger.isManualReapable(p.command) || pid in protectedPids) {
            return ReapResult.Refused("pid $pid was not started by an Infantry AI session; it is never reaped")
        }
        if (runCatching { table.startInstant(pid) }.getOrNull() != started) return ReapResult.Refused("pid $pid is gone (or now another process); nothing killed")
        val mb = runCatching { memory.processes() }.getOrDefault(emptyList()).firstOrNull { it.pid == pid }?.mb
        val view = TrackedProcess(pid, ProcessLedger.describe(p.command), null, started, mb, false, "reaped by hand")
        runCatching { table.killTree(pid, started) }.onFailure { return ReapResult.Refused("killing pid $pid failed: ${it.message}") }
        println("[agentHq] reaped unowned pid $pid (${view.command}) by hand")
        runCatching { onReaped(view) }
        return ReapResult.Reaped(view)
    }

    /** Reap one ledger entry (or, by hand, an unowned MCP server / opencode serve). Refused for anything else, a recycled pid, or a live session's own harness. */
    @Synchronized
    fun reap(pid: Long): ReapResult {
        val entry = ledger.get(pid) ?: return reapUnowned(pid)
        val owner = sessions().firstOrNull { it.id == entry.sessionId }
        if (entry.root && owner != null && owner.state !in ENDED) {
            return ReapResult.Refused("pid $pid is session ${entry.sessionId}'s own harness; kill the session instead")
        }
        val view = report().tracked.firstOrNull { it.pid == pid } ?: TrackedProcess(pid, entry.command, entry.sessionId, entry.startedAt)
        return kill(entry, view)
    }

    /** Reap every rogue entry; returns the ones killed. */
    @Synchronized
    fun reapRogue(): List<TrackedProcess> {
        val rogue = report().rogue
        return rogue.mapNotNull { view ->
            val entry = ledger.get(view.pid) ?: return@mapNotNull null
            (kill(entry, view) as? ReapResult.Reaped)?.process
        }
    }

    /**
     * Ticket b785f383: the deterministic close-time cleanup, separate from (and regardless of) the opt-in auto reap.
     * Kill every ledger entry of [sessionId] that started at or before [closedAt] (so a restart's fresh processes live),
     * leaf first, each pid + start instant verified. Spared: anything not in this session's ledger; the daemon and its
     * ancestors; [keepPids] and everything under them (kept `bg run --keep` jobs, a harness the host still holds); a
     * shared Gradle/Kotlin daemon while a live gate-lock holder outside this session exists, or handed to another live
     * session that runs a Gradle client. A process with a spared descendant is killed alone, never as a tree.
     */
    @Synchronized
    fun reapSession(sessionId: String, closedAt: Long, keepPids: Set<Long> = emptySet(), reason: String = "session closed"): List<TrackedProcess> {
        val killed = reapProcesses(sessionId, closedAt, keepPids, reason)
        // Ticket 7e9bed2a: the same close also lets go of the gate lock this session's run owned (never a lock owner.txt does not name it for).
        val ticketId = runCatching { sessions().firstOrNull { it.id == sessionId }?.ticketId }.getOrNull()
        runCatching { gateLock.releaseIfOwnedBy(sessionId, ticketId, killed.mapTo(HashSet()) { it.pid }) }
            .onSuccess { line -> if (line != null) println("[agentHq] close cleanup: $line (session $sessionId)") }
            .onFailure { System.err.println("[agentHq] close cleanup: gate lock release failed for $sessionId: ${it.message}") }
        return killed
    }

    private fun reapProcesses(sessionId: String, closedAt: Long, keepPids: Set<Long>, reason: String): List<TrackedProcess> {
        val records = sessions()
        runCatching { ledger.observe(roots(records)) } // last-moment children, and drop what is already gone
        val mine = ledger.entries().filter { it.sessionId == sessionId }
        if (mine.isEmpty()) return emptyList()
        val all = ledger.allProcesses().associateBy { it.pid }
        fun ancestors(pid: Long): Sequence<Long> =
            generateSequence(all[pid]?.parentPid) { all[it]?.parentPid }.take(64)
        val minePids = mine.mapTo(HashSet()) { it.pid }
        val holder = runCatching(lockHolderPid).getOrNull()
        val holderUses = holder != null && holder !in minePids && holder !in protectedPids &&
            runCatching { table.startInstant(holder) }.getOrNull() != null
        val liveOthers = records.filter { it.id != sessionId && it.state !in ENDED }.mapTo(HashSet()) { it.id }
        val otherClient = ledger.entries().firstOrNull { it.sessionId in liveOthers && GRADLE_CLIENT.containsMatchIn(it.command) }?.sessionId

        val doomed = ArrayList<LedgerEntry>()
        for (e in mine) {
            when {
                e.startedAt > closedAt -> Unit // started after the close: a restart's new run
                e.pid in protectedPids -> Unit
                e.pid in keepPids || ancestors(e.pid).any { it in keepPids } ->
                    println("[agentHq] close cleanup: kept pid ${e.pid} (${e.command}) of session $sessionId")
                ORPHAN_DAEMON.containsMatchIn(e.command) && holderUses ->
                    println("[agentHq] close cleanup: spared shared daemon pid ${e.pid} of session $sessionId (gate-lock holder $holder alive)")
                ORPHAN_DAEMON.containsMatchIn(e.command) && otherClient != null -> {
                    ledger.reassign(e.pid, otherClient)
                    println("[agentHq] close cleanup: shared daemon pid ${e.pid} handed from session $sessionId to $otherClient")
                }
                else -> doomed += e
            }
        }
        val doomedPids = doomed.mapTo(HashSet()) { it.pid }
        // Leaf first: the deepest process, then the latest started.
        val order = doomed.sortedWith(compareByDescending<LedgerEntry> { ancestors(it.pid).count() }.thenByDescending { it.startedAt })
        val mem = if (order.isEmpty()) emptyMap() else runCatching { memory.processes() }.getOrDefault(emptyList()).associate { it.pid to it.mb }
        return order.mapNotNull { e ->
            // A live descendant we do not kill (kept, foreign, newer, shared) must survive: then the process goes alone.
            val spareBelow = all.values.any { p -> p.pid !in doomedPids && ancestors(p.pid).any { it == e.pid } }
            val view = TrackedProcess(e.pid, e.command, e.sessionId, e.startedAt, mem[e.pid], true, reason, e.inferred)
            (kill(e, view, tree = !spareBelow) as? ReapResult.Reaped)?.process
        }
    }

    /**
     * [reapSession] for every session the ledger still names that has ended or was removed: the catch-up at daemon start
     * (a crash skipped the close-time cleanup) and the sweep at daemon shutdown. A live session's processes, its
     * registered background jobs included, are left alone: they are designed to outlive a daemon restart.
     */
    @Synchronized
    fun reapEnded(reason: String): List<TrackedProcess> {
        val bySession = sessions().associateBy { it.id }
        val now = clock()
        return ledger.entries().map { it.sessionId }.distinct().flatMap { id ->
            val owner = bySession[id]
            if (owner != null && owner.state !in ENDED) return@flatMap emptyList()
            // A FAILED session is resumable: its registered bg jobs stay, as at the failed turn's own close.
            val keep = owner?.let { o -> keepPidsOf(o) + (if (o.state == SessionState.FAILED) o.bgJobs.map { it.pid } else emptyList()) }.orEmpty()
            reapSession(id, now, keep, owner?.let { "$reason: session ended (${it.state})" } ?: "$reason: session removed")
        }
    }

    private fun kill(entry: LedgerEntry, view: TrackedProcess, tree: Boolean = true): ReapResult {
        if (entry.pid in protectedPids) return ReapResult.Refused("pid ${entry.pid} is Infantry AI itself")
        val actual = runCatching { table.startInstant(entry.pid) }.getOrNull()
        if (actual != entry.startedAt) {
            ledger.forget(entry.pid)
            return ReapResult.Refused("pid ${entry.pid} is gone (or now another process); nothing killed")
        }
        runCatching { if (tree) table.killTree(entry.pid, entry.startedAt) else table.killOne(entry.pid, entry.startedAt) }
            .onFailure { return ReapResult.Refused("killing pid ${entry.pid} failed: ${it.message}") }
        ledger.forget(entry.pid)
        println("[agentHq] reaped pid ${entry.pid} (${entry.command}) of session ${entry.sessionId}: ${view.reason ?: "asked"}")
        runCatching { onReaped(view) }
        return ReapResult.Reaped(view)
    }

    private fun rogueReason(e: LedgerEntry, owner: SessionRecord?, mb: Long?, cfg: ProcessWatchConfig, now: Long): String? {
        if (owner == null) return "session removed"
        if (owner.state in ENDED) return "session ended (${owner.state})"
        if (e.root || owner.isBusy() || owner.bgJobs.isNotEmpty()) return null
        if (mb == null || mb < cfg.heavyMb) return null
        val quietMs = now - (owner.lastOutputAt ?: owner.lastHeartbeat)
        val idleMs = cfg.idleMinutes * 60_000L
        return if (quietMs >= idleMs) "session idle ${quietMs / 60_000} min, holding $mb MB" else null
    }

    companion object {
        private val ENDED = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

        /** Runtimes worth showing when unattributed. */
        private val HEAVY = Regex("""(?i)(java|javaw|node|opencode|docker.*|com\.docker.*|vmmem.*|wsl.*|kotlin.*|gradle.*|bun|pi)(\.exe)?""")
        private val NODE = Regex("""(?i)node(\.exe)?""")
        private const val MANUAL_REASON = "not owned by an Infantry AI session: shown, reaped only by hand"
        private val ORPHAN_DAEMON = Regex("""GradleDaemon|KotlinCompileDaemon""")
        private val GRADLE_CLIENT = Regex("""(?i)gradlew|GradleWrapperMain|GradleMain""")

        /** What a closing session keeps alive: its `bg run --keep` wrappers and a harness the host still holds. */
        fun keepPidsOf(record: SessionRecord): Set<Long> =
            (record.bgJobs.filter { it.keep }.map { it.pid } + listOfNotNull(record.keptProcess?.pid)).toSet()

        /** `%TEMP%\agenthq-gate.lock\pid`, or null when there is no lock. */
        fun gateLockHolderPid(): Long? {
            val dir = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir") ?: return null
            return java.io.File(dir, "agenthq-gate.lock/pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        }
        private const val UNATTRIBUTED_MIN_MB = 200L

        /** `%TEMP%\agenthq-gate.lock\pid` names the gate's holder; true when that pid is alive. */
        fun gateLockHolderAlive(): Boolean {
            val dir = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir") ?: return false
            val pid = java.io.File(dir, "agenthq-gate.lock/pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return false
            return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
        private const val UNATTRIBUTED_MAX = 10

        /** The daemon and every ancestor (the app that launched it): never recorded, listed or killed. */
        val protectedPids: Set<Long> by lazy {
            generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.map { it.pid() }.toSet()
        }

        /**
         * The guard the daemon hands [ProcessLedger]: the daemon, its ancestors, and anything run by the app's own bundled
         * runtime (the daemon's `java.home`), such as the agenthq CLI and MCP servers.
         */
        fun isProtected(p: ProcInfo): Boolean {
            if (p.pid in protectedPids) return true
            val home = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return false
            val cmd = p.command.trim().removePrefix("\"")
            return cmd.startsWith(home, ignoreCase = true)
        }
    }
}



/** The daemon's `processes` frame, kept apart from the socket so a test drives it with a fake [ProcessWatch]. */
object ProcessFrames {
    /** Throws [IllegalArgumentException] for an unknown op or a `reap` with no pid. */
    fun answer(watch: ProcessWatch, request: ProcessesRequest): ProcessesAnswer = when (request.op) {
        "list" -> ProcessesAnswer(watch.report())
        "reap" -> {
            val pid = requireNotNull(request.pid) { "reap expects a pid" }
            when (val r = watch.reap(pid)) {
                is ReapResult.Reaped -> ProcessesAnswer(watch.report(), listOf(r.process))
                is ReapResult.Refused -> ProcessesAnswer(watch.report(), refused = r.reason)
            }
        }
        "reap_rogue" -> {
            val reaped = watch.reapRogue()
            ProcessesAnswer(watch.report(), reaped)
        }
        else -> throw IllegalArgumentException("unknown op '${request.op.take(40)}' (list, reap, reap_rogue)")
    }
}
