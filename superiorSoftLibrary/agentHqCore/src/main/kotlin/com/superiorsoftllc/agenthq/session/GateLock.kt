package com.superiorsoftllc.agenthq.session

import java.io.File

/**
 * Ticket 7e9bed2a: the machine-wide gate lock, `%TEMP%\agenthq-gate.lock\` (see scripts/agenthq-gate.sh), read from the daemon.
 *
 * The directory holds `pid` (the holder, written by the gate script) and `owner.txt`, one line of `key=value` pairs:
 * `ticket=<id> session=<id> pid=<n>`. Either file may be missing (an older script writes only `pid`).
 *
 * Rule for every caller: when in doubt the lock is kept. A lock is only ever released ([releaseIfOwnedBy]) when its owner
 * is named by `owner.txt` AND that owner is the closing session/ticket, or when its holder is provably dead.
 */
class GateLock(
    private val dir: () -> File? = ::defaultDir,
    private val pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    data class Owner(val pid: Long?, val ticket: String?, val session: String?) {
        /** True when owner.txt names a ticket or session at all. */
        val named: Boolean get() = ticket != null || session != null
    }

    /** The lock's owner, or null when there is no lock directory. */
    fun read(): Owner? {
        val d = dir()?.takeIf { it.isDirectory } ?: return null
        val fields = runCatching { File(d, OWNER_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
            ?.split(Regex("\\s+"))?.mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }
            ?.toMap().orEmpty()
        val pid = fields["pid"]?.toLongOrNull()
            ?: runCatching { File(d, "pid").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
        return Owner(pid, fields["ticket"]?.ifBlank { null }, fields["session"]?.ifBlank { null })
    }

    fun holderAlive(owner: Owner): Boolean = owner.pid != null && pidAlive(owner.pid)

    /** [owner] names this session (by id) or its ticket (ids compare without the `ticket-` prefix). */
    fun names(owner: Owner, sessionId: String, ticketId: String?): Boolean =
        owner.session == sessionId || (ticketId != null && owner.ticket != null && bare(owner.ticket) == bare(ticketId))

    /**
     * The watchdog's question: is a live gate run working for this session? A live holder whose owner.txt names ANOTHER
     * ticket/session is not; a live holder with no owner information counts (keep on doubt).
     */
    fun liveFor(sessionId: String, ticketId: String?): Boolean {
        val owner = read() ?: return false
        if (!holderAlive(owner)) return false
        return !owner.named || names(owner, sessionId, ticketId)
    }

    /**
     * Close-time release. Removes the lock directory only when owner.txt names [sessionId]/[ticketId] (the holder, if still
     * alive, was just killed with the session, or is in [killedPids]) or when the holder is dead. A lock with no owner.txt, one
     * naming someone else, or one held by a live process we did not kill stays. Returns a log line when it released, else null.
     */
    fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long> = emptySet()): String? {
        val d = dir()?.takeIf { it.isDirectory } ?: return null
        val owner = read() ?: return null
        if (!owner.named || !names(owner, sessionId, ticketId)) return null
        val holderGone = owner.pid == null || !pidAlive(owner.pid) || owner.pid in killedPids
        if (!holderGone) return null
        if (!d.deleteRecursively()) return null
        return "gate lock ${d.path} released: owner ticket=${owner.ticket ?: "-"} session=${owner.session ?: "-"} pid=${owner.pid ?: "-"} closed"
    }

    private fun bare(id: String) = id.removePrefix("ticket-")

    companion object {
        const val OWNER_FILE = "owner.txt"

        fun defaultDir(): File? {
            val tmp = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir") ?: return null
            return File(tmp, "agenthq-gate.lock")
        }
    }
}
