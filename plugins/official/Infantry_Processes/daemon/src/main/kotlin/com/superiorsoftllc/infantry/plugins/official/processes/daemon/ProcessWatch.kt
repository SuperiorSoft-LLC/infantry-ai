// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.CpuAlertConfig
import com.superiorsoftllc.infantry.daemon.api.processes.KillResult
import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessReport
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessToolProfile
import com.superiorsoftllc.infantry.daemon.api.processes.Processes
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesAnswer
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessesRequest
import com.superiorsoftllc.infantry.daemon.api.processes.TrackedProcess
import com.superiorsoftllc.infantry.daemon.api.processes.WatchedSession
import java.io.File

/*
 * ROGUE-1 (ticket 8a40b481, contract 1.120.0), moved out of core by STACK-NEUTRAL-2: the rogue background process detector.
 *
 * ROGUE = a [ProcessLedger] entry (so provably started by one of this daemon's sessions) whose owner session was removed
 * or has ended, OR whose owner sits idle (no turn, no bg job, no output for [ProcessWatchConfig.idleMinutes]) while the
 * process holds at least [ProcessWatchConfig.heavyMb]. A live session's own harness process is never rogue by idleness.
 * UNATTRIBUTED = heavy runtimes (java, node, docker, WSL...) the ledger cannot prove are ours: shown, never reaped.
 *
 * Everything the plugin does to the OS goes through `api.processes` ([Processes]): the process table, the memory figures, the
 * session roots, the gate lock and the start-instant-checked kill.
 */

/** The detector's settings (`processes.*`). Auto-reap is opt-in. */
data class ProcessWatchConfig(
    val idleMinutes: Int = 30,
    val heavyMb: Long = 500,
    /** Free RAM below this raises a pressure warning (the same 4 GB floor as the gate rule). */
    val floorMb: Long = 4096,
    val autoReap: Boolean = false,
    /** PROC-LEAK-1 `processes.reapKinds`: the kinds a session's close reaps (tool profile names, or `other`); empty = every kind. */
    val reapKinds: Set<String> = emptySet(),
    /** PROC-LEAK-1 `limits.processes.stopGraceSeconds`: how long daemons get after their profile's graceful stop; 0 = no graceful step. */
    val stopGraceSeconds: Int = 10,
    /** PROC-LEAK-1 `limits.processes.stuckMinutes`: a profile's `stuck` client with no CPU for this long is stuck; 0 = off. */
    val stuckMinutes: Int = 15,
)

/** Ticket 66153313: per-process CPU share from two samples of cumulative CPU time (100 = every logical core busy). */
class CpuMeter(private val cpuNanos: (Long) -> Long?, private val cores: Int = Runtime.getRuntime().availableProcessors()) {
    private var last: Map<Long, Long> = emptyMap()
    private var lastAt: Long? = null

    /** PROC-LEAK-1: the cumulative CPU nanos of the last sample, by pid. */
    val lastNanos: Map<Long, Long> get() = last

    /** The latest shares by pid. */
    var shares: Map<Long, Double> = emptyMap()
        private set

    /** Sample [pids] at [nowMs]; returns (and keeps) the shares since the previous sample. */
    fun sample(pids: Collection<Long>, nowMs: Long): Map<Long, Double> {
        val now = pids.associateWith { runCatching { cpuNanos(it) }.getOrNull() }.filterValues { it != null }.mapValues { it.value!! }
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

/** What a reap request came to. */
sealed interface ReapResult {
    data class Reaped(val process: TrackedProcess) : ReapResult
    data class Refused(val reason: String) : ReapResult
}

/**
 * The detector: [tick] on a slow timer feeds the ledger from the sessions; [report] classifies; [reap] / [reapRogue] kill
 * only ledger entries whose pid and start instant still match. [onPressure] hears a report when free RAM drops under the
 * floor, at most once per [pressureEveryMs]; [onReaped] hears each kill (auto or asked).
 */
class ProcessWatch(
    private val ledger: ProcessLedger,
    private val os: Processes,
    private val config: () -> ProcessWatchConfig = { ProcessWatchConfig() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPressure: (ProcessReport) -> Unit = {},
    private val onReaped: (TrackedProcess) -> Unit = {},
    private val pressureEveryMs: Long = 10 * 60_000L,
    private val autoReapEveryMs: Long = 60_000L,
    cores: Int = Runtime.getRuntime().availableProcessors(),
    /** Ticket 66153313: the sustained-CPU rule, read on every [tick]. */
    private val cpuAlert: () -> CpuAlertConfig = { CpuAlertConfig() },
    /** The watch's off switch (`limits.watch.enabled`), read at every entry point. Off = nothing recorded, nothing reaped. */
    private val enabled: () -> Boolean = { true },
    private val log: (String) -> Unit = { println("[Infantry_Processes] $it") },
    /** PROC-LEAK-1: runs a profile's graceful `stop` argv in a worktree, waiting at most the given millis. Injected for tests. */
    private val stopRunner: (argv: List<String>, dir: java.io.File, timeoutMs: Long) -> Unit = ::runStop,
    /** PROC-LEAK-1: waits until the given pids (pid to start instant) are gone, at most the given millis. Injected for tests. */
    private val waitGone: ((pids: Map<Long, Long>, timeoutMs: Long) -> Unit)? = null,
) {
    private fun on(): Boolean = runCatching(enabled).getOrDefault(false)
    private fun tools() = runCatching { os.toolProfiles() }.getOrDefault(emptyList())
    private fun sessions(): List<WatchedSession> = runCatching { os.sessions() }.getOrDefault(emptyList())
    private fun protectedPid(pid: Long) = os.isProtected(ProcInfo(pid, null, "", null))

    private val cpuMeter = CpuMeter(os::cpuNanos, cores)
    private val cpuSustain = CpuSustain()

    /** Ticket 66153313: told once per episode when agent CPU stays high (the host raises an attention item). */
    @Volatile
    var onCpuSustained: (ProcessReport) -> Unit = {}

    private var lastPressureAt: Long? = null
    private var lastAutoReapAt: Long? = null

    /** PROC-LEAK-1: what each session's OS container holds now (nothing where the host has no containers). */
    private fun members(records: List<WatchedSession>): Map<String, List<ProcInfo>> =
        records.associate { r -> r.id to runCatching { os.sessionMembers(r.id) }.getOrDefault(emptyList()) }.filterValues { it.isNotEmpty() }

    /** PROC-LEAK-1: pid to (its CPU nanos, since when they have not moved), for the stuck-client rule. */
    private val cpuQuietSince = HashMap<Long, Pair<Long, Long>>()
    private val stuckAlerted = HashSet<Long>()

    /** The roots of every session that may still own a live process: its kept harness pid and its running child pid. */
    private fun roots(records: List<WatchedSession>): Map<String, Collection<Long>> =
        records.associate { r -> r.id to r.rootPids }.filterValues { it.isNotEmpty() }

    /**
     * Ticket 7e9bed2a: the catch-up for a lock the close-time release could not take (its holder was still dying then, or the
     * daemon was down): the lock's owner.txt names a session this daemon knows as ended and the holder pid is dead.
     * An unknown session, a live holder, or a lock without owner.txt is left alone.
     */
    private fun releaseEndedOwnersLock(records: List<WatchedSession>) {
        val owner = runCatching { os.gateLock.read() }.getOrNull() ?: return
        val s = owner.session?.let { id -> records.firstOrNull { it.id == id } } ?: return
        if (!s.ended) return
        runCatching { os.gateLock.releaseIfOwnedBy(s.id, s.ticketId) }
            .onSuccess { if (it != null) log("gate lock catch-up: $it (session ${s.id} ended)") }
    }

    /** One timer pass: observe; warn under pressure; auto-reap when on. Cheap unless pressure or auto-reap fires. */
    @Synchronized
    fun tick() {
        if (!on()) return
        val records = sessions()
        ledger.observe(roots(records), members(records))
        // PROC-REAP-COPY-1: only a session this daemon ran may claim a process by its worktree path (a copied record names real worktrees).
        ledger.adopt(records.filter { it.owned }.mapNotNull { r -> r.worktreePath?.let { r.id to it } }.toMap())
        releaseEndedOwnersLock(records)
        val cfg = config()
        val now = clock()
        val shares = cpuMeter.sample(ledger.entries().map { it.pid }, now)
        trackQuiet(now)
        alertStuck(records, cfg, now)
        val total = if (shares.isEmpty() && ledger.entries().isNotEmpty()) null else shares.values.sum()
        val cpuCfg = runCatching(cpuAlert).getOrDefault(CpuAlertConfig())
        if (cpuSustain.update(total, cpuCfg, now)) runCatching { onCpuSustained(report()) }
        val free = runCatching { os.freeMb() }.getOrNull()
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
        if (!on()) {
            val free = runCatching { os.freeMb() }.getOrNull()
            return ProcessReport(
                freeMb = free, totalMb = runCatching { os.totalMb() }.getOrNull(), floorMb = cfg.floorMb,
                underPressure = free != null && free < cfg.floorMb, autoReap = false,
                cpuAlertPercent = cpuCfg.percent, cpuAlertMinutes = cpuCfg.minutes,
            )
        }
        val tools = tools()
        val heavy = ProfileRx.heavyImages(tools)
        val daemons = ProfileRx.daemons(tools)
        val now = clock()
        val bySession = sessions().associateBy { it.id }
        val stuckRx = ProfileRx.stuck(tools)
        val mem = runCatching { os.memory() }.getOrDefault(emptyList())
        val mbByPid = mem.associate { it.pid to it.mb }
        val tracked = ledger.entries().map { e ->
            val mb = mbByPid[e.pid]
            val reason = rogueReason(e, bySession[e.sessionId], mb, cfg, now)
            TrackedProcess(
                e.pid, forSession(e.command, e.sessionId), e.sessionId, e.startedAt, mb, reason != null, reason, e.inferred,
                cpuPercent = cpuMeter.shares[e.pid]?.let(::round1),
                stuck = if (reason == null) stuckReason(e, bySession[e.sessionId], stuckRx, cfg, now) else null,
            )
        }
        val ledgerPids = tracked.mapTo(HashSet()) { it.pid }
        val candidates = mem.filter {
            it.pid !in ledgerPids && !protectedPid(it.pid) && heavy.matches(it.image) && it.mb >= UNATTRIBUTED_MIN_MB
        }.sortedByDescending { it.mb }
        val info = if (candidates.isEmpty()) emptyMap() else ledger.allProcesses().associateBy { it.pid }
        val lockAlive = if (candidates.isEmpty()) true else runCatching { os.gateLock.read()?.let(os.gateLock::holderAlive) ?: false }.getOrDefault(true)
        // An MCP server or `opencode serve` no session of ours started is the owner's own (Claude Desktop, opencode): never
        // listed, never reaped. Ours are ledger entries and show under their session.
        val unattributed = candidates.filter { c -> info[c.pid]?.let { ProcessLedger.isHarnessHelper(it.command) } != true }
            .take(UNATTRIBUTED_MAX).map { c ->
                val p = info[c.pid]
                val orphan = p != null && !lockAlive && daemons.containsMatchIn(p.command)
                val tool = if (orphan) ProfileRx.daemonProfile(p!!.command, tools) else null
                TrackedProcess(
                    c.pid, p?.command?.let(ProcessLedger::describe) ?: c.image, null, p?.startedAt, c.mb,
                    orphan,
                    if (orphan) "orphan build daemon${tool?.let { " ($it)" }.orEmpty()}: no owning session, no live gate-lock holder" else null,
                )
            }
        val free = runCatching { os.freeMb() }.getOrNull()
        return ProcessReport(
            freeMb = free, totalMb = runCatching { os.totalMb() }.getOrNull(), floorMb = cfg.floorMb,
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

    /** Reap one ledger entry. Refused for anything else (no process outside the ledger, ever), a recycled pid, or a live session's own harness. */
    @Synchronized
    fun reap(pid: Long): ReapResult {
        if (!on()) return ReapResult.Refused(OFF_REASON)
        val entry = ledger.get(pid) ?: return ReapResult.Refused("pid $pid was not started by an Infantry AI session; it is never reaped")
        val owner = sessions().firstOrNull { it.id == entry.sessionId }
        if (entry.root && owner != null && !owner.ended) {
            return ReapResult.Refused("pid $pid is session ${entry.sessionId}'s own harness; kill the session instead")
        }
        val view = report().tracked.firstOrNull { it.pid == pid } ?: TrackedProcess(pid, entry.command, entry.sessionId, entry.startedAt)
        return kill(entry, view)
    }

    /** Reap every rogue entry; returns the ones killed. */
    @Synchronized
    fun reapRogue(): List<TrackedProcess> {
        if (!on()) return emptyList()
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
     * shared build daemon (a `sharedDaemon` tool profile) while a live gate-lock holder outside this session exists, or
     * handed to another live session that runs that profile's client. A process with a spared descendant is killed alone, never as a tree.
     */
    @Synchronized
    fun reapSession(sessionId: String, closedAt: Long, keepPids: Set<Long> = emptySet(), reason: String = "session closed"): List<TrackedProcess> {
        if (!on()) return emptyList()
        val killed = reapProcesses(sessionId, closedAt, keepPids, reason)
        // Ticket 7e9bed2a: the same close also lets go of the gate lock this session's run owned (never a lock owner.txt does not name it for).
        val ticketId = runCatching { sessions().firstOrNull { it.id == sessionId }?.ticketId }.getOrNull()
        runCatching { os.gateLock.releaseIfOwnedBy(sessionId, ticketId, killed.mapTo(HashSet()) { it.pid }) }
            .onSuccess { line -> if (line != null) log("close cleanup: $line (session $sessionId)") }
            .onFailure { System.err.println("[Infantry_Processes] close cleanup: gate lock release failed for $sessionId: ${it.message}") }
        return killed
    }

    private fun reapProcesses(sessionId: String, closedAt: Long, keepPids: Set<Long>, reason: String): List<TrackedProcess> {
        val records = sessions()
        runCatching { ledger.observe(roots(records), members(records)) } // last-moment children, and drop what is already gone
        val mine = ledger.entries().filter { it.sessionId == sessionId }
        if (mine.isEmpty()) return emptyList()
        val all = ledger.allProcesses().associateBy { it.pid }
        fun ancestors(pid: Long): Sequence<Long> =
            generateSequence(all[pid]?.parentPid) { all[it]?.parentPid }.take(64)
        val minePids = mine.mapTo(HashSet()) { it.pid }
        val holder = runCatching { os.gateLock.read()?.pid }.getOrNull()
        val holderUses = holder != null && holder !in minePids && !protectedPid(holder) &&
            runCatching { os.startInstant(holder) }.getOrNull() != null
        val liveOthers = records.filter { it.id != sessionId && !it.ended }.mapTo(HashSet()) { it.id }
        val tools = tools()
        val shared = ProfileRx.sharedDaemons(tools)
        val clients = ProfileRx.clients(tools)
        val otherClient = ledger.entries().firstOrNull { it.sessionId in liveOthers && clients.containsMatchIn(it.command) }?.sessionId
        val cfg = config()

        val doomed = ArrayList<LedgerEntry>()
        for (e in mine) {
            when {
                e.startedAt > closedAt -> Unit // started after the close: a restart's new run
                protectedPid(e.pid) -> Unit
                e.pid in keepPids || ancestors(e.pid).any { it in keepPids } ->
                    log("close cleanup: kept pid ${e.pid} (${e.command}) of session $sessionId")
                shared.containsMatchIn(e.command) && holderUses ->
                    log("close cleanup: spared shared daemon pid ${e.pid} of session $sessionId (gate-lock holder $holder alive)")
                shared.containsMatchIn(e.command) && otherClient != null -> {
                    ledger.reassign(e.pid, otherClient)
                    log("close cleanup: shared daemon pid ${e.pid} handed from session $sessionId to $otherClient")
                }
                cfg.reapKinds.isNotEmpty() && kindOf(e.command, tools) !in cfg.reapKinds ->
                    log("close cleanup: spared pid ${e.pid} of session $sessionId (kind ${kindOf(e.command, tools)} is not in processes.reapKinds)")
                else -> doomed += e
            }
        }
        val doomedPids = doomed.mapTo(HashSet()) { it.pid }
        val stopped = stopGracefully(sessionId, doomed, doomedPids, all.values, tools, cfg, records)
        // Leaf first: the deepest process, then the latest started.
        val order = doomed.sortedWith(compareByDescending<LedgerEntry> { ancestors(it.pid).count() }.thenByDescending { it.startedAt })
        val mem = if (order.isEmpty()) emptyMap() else runCatching { os.memory() }.getOrDefault(emptyList()).associate { it.pid to it.mb }
        return order.mapNotNull { e ->
            if (e.pid in stopped && runCatching { os.startInstant(e.pid) }.getOrNull() != e.startedAt) {
                // PROC-LEAK-1: its profile's graceful stop ended it: counted as reaped, nothing killed.
                val view = TrackedProcess(e.pid, e.command, e.sessionId, e.startedAt, mem[e.pid], true, "$reason (stopped gracefully)", e.inferred)
                ledger.forget(e.pid)
                log("stopped pid ${e.pid} (${e.command}) of session ${e.sessionId} gracefully")
                runCatching { onReaped(view) }
                return@mapNotNull view
            }
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
        if (!on()) return emptyList()
        val bySession = sessions().associateBy { it.id }
        val now = clock()
        return ledger.entries().map { it.sessionId }.distinct().flatMap { id ->
            val owner = bySession[id]
            if (owner != null && !owner.ended) return@flatMap emptyList()
            // A FAILED session is resumable: its registered bg jobs stay, as at the failed turn's own close.
            val keep = owner?.let { o -> o.keepPids + (if (o.state == "FAILED") o.bgJobPids else emptyList()) }.orEmpty()
            reapSession(id, now, keep, owner?.let { "$reason: session ended (${it.state})" } ?: "$reason: session removed")
        }
    }

    private fun kill(entry: LedgerEntry, view: TrackedProcess, tree: Boolean = true): ReapResult {
        if (protectedPid(entry.pid)) return ReapResult.Refused("pid ${entry.pid} is Infantry AI itself")
        val actual = runCatching { os.startInstant(entry.pid) }.getOrNull()
        if (actual != entry.startedAt) {
            ledger.forget(entry.pid)
            return ReapResult.Refused("pid ${entry.pid} is gone (or now another process); nothing killed")
        }
        when (val r = runCatching { os.kill(entry.pid, entry.startedAt, tree) }.getOrElse { KillResult.Refused("killing pid ${entry.pid} failed: ${it.message}") }) {
            is KillResult.Refused -> return ReapResult.Refused(r.reason)
            is KillResult.Killed -> Unit
        }
        ledger.forget(entry.pid)
        log("reaped pid ${entry.pid} (${entry.command}) of session ${entry.sessionId}: ${view.reason ?: "asked"}")
        runCatching { onReaped(view) }
        return ReapResult.Reaped(view)
    }

    /**
     * PROC-LEAK-1: the graceful step before the kill. For every tool profile with a `stop` argv whose daemons are among [doomed],
     * and only when NO other live process matches that profile's daemons (a tool's stop command reaches every daemon of its
     * kind, the owner's own included), run the argv in the session's worktree and wait up to the grace for those daemons to end.
     * Returns the pids the step was tried on; whatever is still alive afterwards is killed as before.
     */
    private fun stopGracefully(
        sessionId: String, doomed: List<LedgerEntry>, doomedPids: Set<Long>, all: Collection<ProcInfo>,
        tools: List<ProcessToolProfile>, cfg: ProcessWatchConfig, records: List<WatchedSession>,
    ): Set<Long> {
        val graceMs = cfg.stopGraceSeconds.coerceAtLeast(0) * 1000L
        if (graceMs == 0L || doomed.isEmpty()) return emptySet()
        val dir = records.firstOrNull { it.id == sessionId }?.worktreePath?.let(::File)?.takeIf { it.isDirectory } ?: return emptySet()
        val tried = HashSet<Long>()
        for (profile in tools.filter { it.stop.isNotEmpty() && it.daemons.isNotEmpty() }) {
            val rx = ProfileRx.any(profile.daemons)
            val theirs = doomed.filter { rx.containsMatchIn(it.command) }
            if (theirs.isEmpty()) continue
            if (all.any { p -> p.pid !in doomedPids && rx.containsMatchIn(p.command) }) {
                log("close cleanup: no graceful ${profile.name} stop for session $sessionId (another ${profile.name} daemon is alive); killing its own")
                continue
            }
            // The stop command must be the worktree's own (a wrapper script there); never a PATH lookup of some other install.
            val command = resolveIn(dir, profile.stop.first()) ?: continue
            val argv = listOf(command) + profile.stop.drop(1)
            log("close cleanup: graceful ${profile.name} stop in $dir for session $sessionId (${theirs.size} daemon(s))")
            runCatching { stopRunner(argv, dir, graceMs) }.onFailure { log("close cleanup: ${profile.name} stop failed: ${it.message}") }
            val pids = theirs.associate { it.pid to it.startedAt }
            runCatching { (waitGone ?: { p, ms -> pollGone(p, ms) })(pids, graceMs) }
            tried += pids.keys
        }
        return tried
    }

    private fun pollGone(pids: Map<Long, Long>, timeoutMs: Long) {
        val until = System.currentTimeMillis() + timeoutMs
        while (pids.any { (pid, at) -> runCatching { os.startInstant(pid) }.getOrNull() == at } && System.currentTimeMillis() < until) {
            Thread.sleep(POLL_MS)
        }
    }

    /** PROC-LEAK-1: a kind for [command]: the first profile whose daemons, clients or stuck list match, then heavyCommands; else `other`. */
    private fun kindOf(command: String, tools: List<ProcessToolProfile>): String =
        tools.firstOrNull { p -> listOf(p.daemons, p.clients, p.stuck).any { it.isNotEmpty() && ProfileRx.any(it).containsMatchIn(command) } }?.name?.lowercase()
            ?: tools.firstOrNull { p -> p.heavyCommands.isNotEmpty() && ProfileRx.any(p.heavyCommands).containsMatchIn(command) }?.name?.lowercase()
            ?: OTHER_KIND

    /** PROC-LEAK-1: note when each ledger pid's CPU time last moved. */
    private fun trackQuiet(now: Long) {
        val nanos = cpuMeter.lastNanos
        cpuQuietSince.keys.retainAll(nanos.keys)
        stuckAlerted.retainAll(nanos.keys)
        for ((pid, ns) in nanos) {
            val prev = cpuQuietSince[pid]
            if (prev == null || prev.first != ns) cpuQuietSince[pid] = ns to now
        }
    }

    /** PROC-LEAK-1: why [e] (of a live session) is a stuck client: it matches a profile's `stuck`, has no live child, used no CPU for the limit. */
    private fun stuckReason(e: LedgerEntry, owner: WatchedSession?, rx: Regex, cfg: ProcessWatchConfig, now: Long): String? {
        if (cfg.stuckMinutes <= 0 || e.root || owner == null || owner.ended) return null
        if (!rx.containsMatchIn(e.command)) return null
        val since = cpuQuietSince[e.pid]?.second ?: return null
        val quietMs = now - since
        if (quietMs < cfg.stuckMinutes * 60_000L) return null
        if (runCatching { os.descendants(e.pid) }.getOrDefault(emptyList()).isNotEmpty()) return null // a wrapper waiting on its command
        return "no CPU for ${quietMs / 60_000} min"
    }

    /** PROC-LEAK-1: raise each newly stuck client once to the owner. */
    private fun alertStuck(records: List<WatchedSession>, cfg: ProcessWatchConfig, now: Long) {
        if (cfg.stuckMinutes <= 0) return
        val rx = ProfileRx.stuck(tools())
        val bySession = records.associateBy { it.id }
        for (e in ledger.entries()) {
            if (e.pid in stuckAlerted) continue
            val why = stuckReason(e, bySession[e.sessionId], rx, cfg, now) ?: continue
            stuckAlerted += e.pid
            log("stuck client pid ${e.pid} (${e.command}) of session ${e.sessionId}: $why")
            runCatching {
                os.raiseAlert(
                    "client_stuck",
                    "Session ${e.sessionId.take(8)} has a stuck client: pid ${e.pid} ${e.command.take(STUCK_COMMAND_MAX)} ($why; limits.processes.stuckMinutes, 0 = off)",
                )
            }
        }
    }

    private fun rogueReason(e: LedgerEntry, owner: WatchedSession?, mb: Long?, cfg: ProcessWatchConfig, now: Long): String? {
        if (owner == null) return "session removed"
        if (owner.ended) return "session ended (${owner.state})"
        if (e.root || owner.busy || owner.hasBgJobs) return null
        if (mb == null || mb < cfg.heavyMb) return null
        val quietMs = now - owner.lastActivityAt
        val idleMs = cfg.idleMinutes * 60_000L
        return if (quietMs >= idleMs) "session idle ${quietMs / 60_000} min, holding $mb MB" else null
    }

    companion object {
        const val OFF_REASON = "the process watch is off (limits.watch.enabled = 0): nothing is tracked or reaped"
        /** PROC-LEAK-1: the `processes.reapKinds` name of a process no tool profile names. */
        const val OTHER_KIND = "other"
        private const val POLL_MS = 250L
        private const val STUCK_COMMAND_MAX = 80

        /** [name] as a file in [dir] (with `.bat` / `.cmd` / `.exe` first on Windows), or null when [dir] has none. */
        internal fun resolveIn(dir: File, name: String): String? {
            val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            val suffixes = if (windows) listOf(".bat", ".cmd", ".exe", "") else listOf("")
            return suffixes.map { File(dir, name + it) }.firstOrNull { it.isFile }?.absolutePath
        }

        /** The real graceful stop: [argv] in [dir], output discarded, force-ended when it outlives [timeoutMs]. */
        internal fun runStop(argv: List<String>, dir: File, timeoutMs: Long) {
            val p = ProcessBuilder(argv).directory(dir).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        private const val UNATTRIBUTED_MIN_MB = 200L
        private const val UNATTRIBUTED_MAX = 10
    }
}

/** The `processes` frame, kept apart from the socket so a test drives it with a fake [ProcessWatch]. */
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
