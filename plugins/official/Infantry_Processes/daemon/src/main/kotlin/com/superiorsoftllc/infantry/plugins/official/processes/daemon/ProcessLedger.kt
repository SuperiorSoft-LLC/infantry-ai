// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.daemon.api.processes.Processes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * ROGUE-1 (ticket 8a40b481): which OS processes this daemon's sessions started, proven by pid AND start instant.
 *
 * Why a ledger and not a live tree walk: on Windows a process whose parent exits (the `gradlew` client ends, its Gradle
 * daemon stays) is no longer anybody's descendant, so it can only be proven ours if we saw it while its parent was alive.
 * The ledger walks every live session root, then every still-live entry (so a Kotlin daemon forked later by an already
 * orphaned Gradle daemon is captured too), and remembers what it saw. Nothing outside this closure is ever reapable.
 */

/** One process the ledger proved a session started. */
@Serializable
data class LedgerEntry(
    val pid: Long,
    /** OS start instant, epoch millis: the identity check before any kill (a recycled pid has another one). */
    val startedAt: Long,
    /** The command line, cut to [ProcessLedger.COMMAND_MAX] characters. */
    val command: String,
    /** The session whose tree it was found in. */
    val sessionId: String,
    /** Epoch millis the ledger first saw it. */
    val firstSeen: Long,
    /** True for the session's own harness process (kept or running pid), as opposed to something it launched. */
    val root: Boolean = false,
    /** True when the owner was inferred from a live ancestor entry or a session worktree path in the command line. */
    val inferred: Boolean = false,
)

@Serializable
private data class LedgerFile(val ownerId: String, val entries: List<LedgerEntry> = emptyList())

/**
 * The ledger. [file] (the daemon state dir) keeps it across a daemon restart, stamped with [ownerId] (the state dir's
 * InstanceId); a file with another owner is ignored, so a copied state dir never names another daemon's processes.
 * [isProtected] guards the daemon, its ancestors and the app's own runtime: such a process is never recorded.
 */
class ProcessLedger(
    private val os: Processes,
    private val file: File? = null,
    private val ownerId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isProtected: (ProcInfo) -> Boolean = os::isProtected,
    /** STACK-NEUTRAL-1: the tool profiles in force; their `heavyCommands` say what [adopt] may adopt by worktree path. */
    private val profiles: () -> List<com.superiorsoftllc.infantry.daemon.api.processes.ProcessToolProfile> = os::toolProfiles,
    /** PROC-LEAK-2: reads where an orphan build daemon last built (a profile's `daemonDir`). Injected for tests. */
    private val daemonDirs: DaemonDirReader = DaemonDirReader(),
) {
    private val entries = LinkedHashMap<Long, LedgerEntry>()

    init {
        load()
    }

    @Synchronized
    fun entries(): List<LedgerEntry> = entries.values.toList()

    @Synchronized
    fun get(pid: Long): LedgerEntry? = entries[pid]

    /** Every live OS process with its command line, for naming what the ledger does not own. */
    fun allProcesses(): List<ProcInfo> = runCatching { os.snapshot() }.getOrDefault(emptyList())

    /**
     * Adopt heavy runtimes the tree walk missed (their parent died before a tick saw them). Ownership is inferred only
     * from (i) a live parent chain reaching an entry already proven ours, or (ii) STACK-NEUTRAL-1: an ORPHAN (its parent
     * is gone) whose command line names a session's worktree path at a path boundary (the longest path wins) and matches a
     * profile's `heavyCommands`. A process with a live parent outside the ledger (one the owner started by hand, from a
     * shell or an IDE in that worktree) is never adopted. No start-time guessing. [sessionDirs] = session id to worktree path.
     *
     * (iii) PROC-LEAK-2: an ORPHAN matching a profile's `daemons` whose `daemonDir` source (the daemon's own log) names a
     * session's worktree as the directory it last built in. This claims a detached build daemon whose client exited while
     * no tick saw it (a daemon restart in between, or a build shorter than the tick); ended sessions are in [sessionDirs]
     * too, so their daemons are adopted and then show as rogue.
     */
    @Synchronized
    fun adopt(sessionDirs: Map<String, String>) {
        val dirs = sessionDirs.mapValues { norm(it.value) }.filterValues { it.length >= MIN_DIR }.entries.sortedByDescending { it.value.length }
        // Ticket 3b30398d: no entry to chain to and no worktree to match = nothing can be adopted; skip the OS-wide scan.
        if (entries.isEmpty() && dirs.isEmpty()) return
        val all = allProcesses()
        if (all.isEmpty()) return
        val byPid = all.associateBy { it.pid }
        val tools = runCatching(profiles).getOrDefault(emptyList())
        val heavy = ProfileRx.heavyCommand(tools)
        val dirSources = tools.mapNotNull { t -> t.daemonDir?.takeIf { t.daemons.isNotEmpty() && it.paths.isNotEmpty() && it.match.isNotEmpty() }?.let { ProfileRx.any(t.daemons) to it } }
        daemonDirs.retain(byPid.keys)
        fullCommands.keys.retainAll(byPid.keys)
        var changed = false
        for (p in all) {
            val dirSource = dirSources.firstOrNull { it.first.containsMatchIn(p.command) }?.second
            if (dirSource == null && !heavy.containsMatchIn(p.command)) continue
            val started = p.startedAt ?: continue
            if (entries[p.pid]?.startedAt == started) continue
            var owner: String? = null
            var up = p.parentPid
            var hops = 0
            while (up != null && hops++ < 32 && owner == null) {
                val e = entries[up]
                if (e != null && os.startInstant(up) == e.startedAt) owner = e.sessionId
                up = byPid[up]?.parentPid
            }
            var rec = p
            if (owner == null && (p.parentPid == null || p.parentPid !in byPid)) {
                // PROC-LEAK-2: the OS-wide snapshot holds only the image path on Windows (the JDK gives no command line for a
                // process this JVM did not start), so an orphan's real command line is read once here, per pid + start.
                rec = p.copy(command = fullCommand(p))
                val cmd = norm(rec.command)
                owner = dirs.firstOrNull { atBoundary(cmd, it.value) }?.key
                val source = dirSource ?: dirSources.firstOrNull { it.first.containsMatchIn(rec.command) }?.second
                if (owner == null && source != null && !runCatching { isProtected(rec) }.getOrDefault(true)) {
                    val built = daemonDirs.dirOf(rec, source)?.let(::norm)
                    if (built != null) owner = dirs.firstOrNull { atBoundary(built, it.value) && built.startsWith(it.value) }?.key
                }
            }
            if (owner != null) changed = record(rec, owner, root = false, inferred = true) || changed
        }
        if (changed) save()
    }

    /** pid to (start instant, full command line) of the orphans [adopt] looked at; pruned to the live pids every pass. */
    private val fullCommands = HashMap<Long, Pair<Long, String>>()

    private fun fullCommand(p: ProcInfo): String {
        val started = p.startedAt ?: return p.command
        fullCommands[p.pid]?.takeIf { it.first == started }?.let { return it.second }
        val full = runCatching { os.info(p.pid) }.getOrNull()?.takeIf { it.startedAt == null || it.startedAt == started }
            ?.command?.takeIf { it.isNotBlank() } ?: p.command
        fullCommands[p.pid] = started to full
        return full
    }

    private fun norm(s: String) = s.replace('\\', '/').trimEnd('/').lowercase()

    /** [dir] occurs in [cmd] as a whole path: followed by the end, a separator, a quote or a space (`ticket-a` is not `ticket-ab`). */
    private fun atBoundary(cmd: String, dir: String): Boolean {
        var i = cmd.indexOf(dir)
        while (i >= 0) {
            val next = cmd.getOrNull(i + dir.length)
            if (next == null || next in PATH_END) return true
            i = cmd.indexOf(dir, i + 1)
        }
        return false
    }

    /** Hand entry [pid] to [sessionId] (a shared build daemon outliving the session that started it). */
    @Synchronized
    fun reassign(pid: Long, sessionId: String) {
        val e = entries[pid] ?: return
        if (e.sessionId == sessionId) return
        entries[pid] = e.copy(sessionId = sessionId, root = false, inferred = true)
        save()
    }

    @Synchronized
    fun forget(pid: Long) {
        if (entries.remove(pid) != null) save()
    }

    /**
     * One pass: drop entries that are gone (or whose pid now belongs to another process), record [roots] (session id to
     * its harness pid) and everything under them, then (PROC-LEAK-1) every process in a session's OS container ([members]:
     * session id to the live processes of its Job Object, orphans included, so a detached child whose parent already exited
     * is still the session's), then everything under the surviving entries.
     */
    @Synchronized
    fun observe(roots: Map<String, Collection<Long>>, members: Map<String, Collection<ProcInfo>> = emptyMap()) {
        var changed = prune()
        val walked = HashSet<Long>()
        for ((sessionId, pids) in roots) {
            for (pid in pids) {
                os.info(pid)?.let { changed = record(it, sessionId, root = true) || changed }
                walked += pid
                for (p in os.descendants(pid)) {
                    changed = record(p, sessionId, root = false) || changed
                    walked += p.pid
                }
            }
        }
        for ((sessionId, procs) in members) {
            for (p in procs) changed = record(p, sessionId, root = false) || changed
        }
        // An entry already covered by a root walk needs no walk of its own; the rest are orphans (dead parent).
        for (entry in entries.values.toList()) {
            if (entry.pid in walked) continue
            walked += entry.pid
            for (p in os.descendants(entry.pid)) {
                changed = record(p, entry.sessionId, root = false) || changed
                walked += p.pid
            }
        }
        if (changed) save()
    }

    private fun record(p: ProcInfo, sessionId: String, root: Boolean, inferred: Boolean = false): Boolean {
        val started = p.startedAt ?: return false // no start instant = nothing proves it is ours later
        val known = entries[p.pid]
        if (known != null && known.startedAt == started) return false // first owner wins
        if (runCatching { isProtected(p) }.getOrDefault(true)) return false
        entries[p.pid] = LedgerEntry(p.pid, started, describe(p.command), sessionId, clock(), root, inferred)
        return true
    }

    private fun prune(): Boolean {
        val gone = entries.values.filter { e -> runCatching { os.startInstant(e.pid) }.getOrNull() != e.startedAt }
        gone.forEach { entries.remove(it.pid) }
        return gone.isNotEmpty()
    }

    private fun load() {
        val f = file ?: return
        val me = ownerId ?: return
        if (!f.isFile) return
        runCatching {
            val parsed = JSON.decodeFromString(LedgerFile.serializer(), f.readText())
            if (parsed.ownerId == me) parsed.entries.forEach { entries[it.pid] = it }
        }.onFailure { System.err.println("[Infantry_Processes] process ledger unreadable, starting empty: ${it.message}") }
    }

    private fun save() {
        val f = file ?: return
        val me = ownerId ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSON.encodeToString(LedgerFile.serializer(), LedgerFile(me, entries.values.toList())))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { System.err.println("[Infantry_Processes] process ledger write failed: ${it.message}") }
    }

    companion object {
        const val FILE_NAME = "process-ledger.json"
        const val COMMAND_MAX = 80
        private const val MIN_DIR = 8
        private val JSON = Json { ignoreUnknownKeys = true }

        /** An MCP server a harness starts per session (Playwright, the reference servers), matched on the FULL command line. */
        val MCP_CMD = Regex("""(?i)@playwright/mcp|playwright-mcp|@modelcontextprotocol""")
        private val OPENCODE_SERVE = Regex("""(?i)opencode(\.exe)?"?\s+serve\b""")
        private val PORT = Regex("""--port\s+(\d+)""")

        private const val PATH_END = "/\"' ;,"

        /** STACK-NEUTRAL-1: an MCP server or an `opencode serve`: a harness helper, listed and reaped only when the ledger proves it ours. */
        fun isHarnessHelper(command: String) = MCP_CMD.containsMatchIn(command) || OPENCODE_SERVE.containsMatchIn(command)

        /**
         * What the list shows for [command]: an MCP server is named ("Playwright MCP | ..."; ProcessWatch adds the session),
         * an `opencode serve` keeps its port (the path in front would push it past the cut), anything else is shortened.
         */
        fun describe(command: String): String = when {
            MCP_CMD.containsMatchIn(command) -> (if (command.contains("playwright", true)) "Playwright MCP" else "MCP server") +
                " | " + shorten(command).takeLast(48)
            OPENCODE_SERVE.containsMatchIn(command) ->
                "opencode serve" + (PORT.find(command)?.let { " --port ${it.groupValues[1]}" } ?: "")
            else -> shorten(command)
        }

        fun shorten(command: String): String = command.trim().replace(Regex("""\s+"""), " ").let {
            if (it.length <= COMMAND_MAX) it else it.take(COMMAND_MAX - 1) + "…"
        }
    }
}
