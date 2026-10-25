package com.superiorsoftllc.agenthq.session

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

/** One process as the detector reports it. */
@Serializable
data class TrackedProcess(
    val pid: Long,
    /** Command line (tracked) or image name (unattributed), cut to 80 characters. */
    val command: String,
    /** The owning session; null = unattributed. */
    val sessionId: String? = null,
    val startedAt: Long? = null,
    /** Resident memory in MB; null when the OS would not say. */
    val memoryMb: Long? = null,
    val rogue: Boolean = false,
    /** Why it is rogue: `session removed`, `session ended (EXITED)`, `session idle 42 min`. */
    val reason: String? = null,
    /** True when the owner was inferred (ancestry or a worktree path in the command line), not seen as a direct child. */
    val inferred: Boolean = false,
)

@Serializable
data class ProcessReport(
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val floorMb: Long,
    val underPressure: Boolean = false,
    val autoReap: Boolean = false,
    /** Every live ledger entry: provably ours. */
    val tracked: List<TrackedProcess> = emptyList(),
    /** Heavy runtimes we cannot prove are ours, largest first. Read-only. */
    val unattributed: List<TrackedProcess> = emptyList(),
) {
    /** Tracked rogue ones (reapable) and unattributed orphan daemons (shown, never reaped). */
    val rogue: List<TrackedProcess> get() = (tracked + unattributed).filter { it.rogue }

    /** The largest processes, tracked and not, for a pressure warning. */
    fun topOffenders(n: Int = 5): List<TrackedProcess> =
        (tracked + unattributed).filter { it.memoryMb != null }.sortedByDescending { it.memoryMb }.take(n)
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
) {
    private var lastPressureAt: Long? = null
    private var lastAutoReapAt: Long? = null

    /** The roots of every session that may still own a live process: its kept harness pid and its running child pid. */
    private fun roots(records: List<SessionRecord>): Map<String, Collection<Long>> =
        records.associate { r -> r.id to listOfNotNull(r.keptProcess?.pid, r.childPid).distinct() }.filterValues { it.isNotEmpty() }

    /** One timer pass: observe; warn under pressure; auto-reap when on. Cheap unless pressure or auto-reap fires. */
    @Synchronized
    fun tick() {
        val records = sessions()
        ledger.observe(roots(records))
        ledger.adopt(records.mapNotNull { r -> r.worktreePath?.let { r.id to it } }.toMap())
        val cfg = config()
        val now = clock()
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
        val now = clock()
        val bySession = sessions().associateBy { it.id }
        val mem = runCatching { memory.processes() }.getOrDefault(emptyList())
        val mbByPid = mem.associate { it.pid to it.mb }
        val tracked = ledger.entries().map { e ->
            val mb = mbByPid[e.pid]
            val reason = rogueReason(e, bySession[e.sessionId], mb, cfg, now)
            TrackedProcess(e.pid, e.command, e.sessionId, e.startedAt, mb, reason != null, reason, e.inferred)
        }
        val ledgerPids = tracked.mapTo(HashSet()) { it.pid }
        val candidates = mem.filter {
            it.pid !in ledgerPids && it.pid !in protectedPids && HEAVY.matches(it.image) && it.mb >= UNATTRIBUTED_MIN_MB
        }.sortedByDescending { it.mb }.take(UNATTRIBUTED_MAX)
        val info = if (candidates.isEmpty()) emptyMap() else ledger.allProcesses().associateBy { it.pid }
        val lockAlive = if (candidates.isEmpty()) true else runCatching(lockHolderAlive).getOrDefault(true)
        val unattributed = candidates.map { c ->
            val p = info[c.pid]
            val orphan = p != null && !lockAlive && ORPHAN_DAEMON.containsMatchIn(p.command)
            TrackedProcess(
                c.pid, p?.command?.let(ProcessLedger::shorten) ?: c.image, null, p?.startedAt, c.mb,
                orphan, if (orphan) "orphan Gradle/Kotlin daemon: no owning session, no live gate-lock holder" else null,
            )
        }
        val free = runCatching { memory.freeMb() }.getOrNull()
        return ProcessReport(
            freeMb = free, totalMb = runCatching { memory.totalMb() }.getOrNull(), floorMb = cfg.floorMb,
            underPressure = free != null && free < cfg.floorMb, autoReap = cfg.autoReap,
            tracked = tracked, unattributed = unattributed,
        )
    }

    /** Reap one ledger entry. Refused for anything not in the ledger, a recycled pid, or a live session's own harness. */
    @Synchronized
    fun reap(pid: Long): ReapResult {
        val entry = ledger.get(pid) ?: return ReapResult.Refused("pid $pid was not started by an Infantry AI session; it is never reaped")
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

    private fun kill(entry: LedgerEntry, view: TrackedProcess): ReapResult {
        if (entry.pid in protectedPids) return ReapResult.Refused("pid ${entry.pid} is Infantry AI itself")
        val actual = runCatching { table.startInstant(entry.pid) }.getOrNull()
        if (actual != entry.startedAt) {
            ledger.forget(entry.pid)
            return ReapResult.Refused("pid ${entry.pid} is gone (or now another process); nothing killed")
        }
        runCatching { table.killTree(entry.pid, entry.startedAt) }
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
        private val ORPHAN_DAEMON = Regex("""GradleDaemon|KotlinCompileDaemon""")
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

/** Contract 1.120.0: one `processes` request. [op] = `list` (default), `reap` (needs [pid]) or `reap_rogue`. */
@Serializable
data class ProcessesRequest(val op: String = "list", val pid: Long? = null)

/** The `processes` answer: the report after the op, what it killed, and why a `reap` was refused (null = not refused). */
@Serializable
data class ProcessesAnswer(
    val report: ProcessReport,
    val reaped: List<TrackedProcess> = emptyList(),
    val refused: String? = null,
)

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
