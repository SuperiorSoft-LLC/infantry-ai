package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.session.host.ProcessTable
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
)

@Serializable
private data class LedgerFile(val ownerId: String, val entries: List<LedgerEntry> = emptyList())

/**
 * The ledger. [file] (the daemon state dir) keeps it across a daemon restart, stamped with [ownerId] (the state dir's
 * InstanceId); a file with another owner is ignored, so a copied state dir never names another daemon's processes.
 * [isProtected] guards the daemon, its ancestors and the app's own runtime: such a process is never recorded.
 */
class ProcessLedger(
    private val source: ProcessSource,
    private val table: ProcessTable,
    private val file: File? = null,
    private val ownerId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isProtected: (ProcInfo) -> Boolean = { false },
) {
    private val entries = LinkedHashMap<Long, LedgerEntry>()

    init {
        load()
    }

    @Synchronized
    fun entries(): List<LedgerEntry> = entries.values.toList()

    @Synchronized
    fun get(pid: Long): LedgerEntry? = entries[pid]

    @Synchronized
    fun forget(pid: Long) {
        if (entries.remove(pid) != null) save()
    }

    /**
     * One pass: drop entries that are gone (or whose pid now belongs to another process), record [roots] (session id to
     * its harness pid) and everything under them, then everything under the surviving entries.
     */
    @Synchronized
    fun observe(roots: Map<String, Collection<Long>>) {
        var changed = prune()
        val walked = HashSet<Long>()
        for ((sessionId, pids) in roots) {
            for (pid in pids) {
                source.info(pid)?.let { changed = record(it, sessionId, root = true) || changed }
                walked += pid
                for (p in source.descendants(pid)) {
                    changed = record(p, sessionId, root = false) || changed
                    walked += p.pid
                }
            }
        }
        // An entry already covered by a root walk needs no walk of its own; the rest are orphans (dead parent).
        for (entry in entries.values.toList()) {
            if (entry.pid in walked) continue
            walked += entry.pid
            for (p in source.descendants(entry.pid)) {
                changed = record(p, entry.sessionId, root = false) || changed
                walked += p.pid
            }
        }
        if (changed) save()
    }

    private fun record(p: ProcInfo, sessionId: String, root: Boolean): Boolean {
        val started = p.startedAt ?: return false // no start instant = nothing proves it is ours later
        val known = entries[p.pid]
        if (known != null && known.startedAt == started) return false // first owner wins
        if (runCatching { isProtected(p) }.getOrDefault(true)) return false
        entries[p.pid] = LedgerEntry(p.pid, started, shorten(p.command), sessionId, clock(), root)
        return true
    }

    private fun prune(): Boolean {
        val gone = entries.values.filter { e -> runCatching { table.startInstant(e.pid) }.getOrNull() != e.startedAt }
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
        }.onFailure { System.err.println("[agentHq] process ledger unreadable, starting empty: ${it.message}") }
    }

    private fun save() {
        val f = file ?: return
        val me = ownerId ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSON.encodeToString(LedgerFile.serializer(), LedgerFile(me, entries.values.toList())))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { System.err.println("[agentHq] process ledger write failed: ${it.message}") }
    }

    companion object {
        const val FILE_NAME = "process-ledger.json"
        const val COMMAND_MAX = 80
        private val JSON = Json { ignoreUnknownKeys = true }

        fun shorten(command: String): String = command.trim().replace(Regex("""\s+"""), " ").let {
            if (it.length <= COMMAND_MAX) it else it.take(COMMAND_MAX - 1) + "…"
        }
    }
}
