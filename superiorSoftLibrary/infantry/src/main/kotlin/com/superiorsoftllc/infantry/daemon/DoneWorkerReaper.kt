package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.isBusy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * BASICS-KILL-1 (contract 1.118.0, `Project.autoRemoveDoneWorkers`): a ticket moved to done ends its worker session and
 * removes it from the session list, so nobody babysits finished workers.
 *
 * WHEN: never mid-turn. A worker usually moves its own ticket to done and then mails its starter in the same turn, so a
 * worker still in a turn is remembered and reaped when that turn ends ([onSessionEvent]). A record that only CLAIMS a
 * turn (no run in this daemon, no live pid: [SessionManager.isStaleLive]) is reaped at once.
 *
 * HOW: a forced kill (the session host's process tree goes, leftover background processes with it), then the delete.
 * Both run on [executor], off the board's monitor (the transition sink is called under it) and off the session event
 * thread. A failure is logged and never reaches the board or the session.
 */
class DoneWorkerReaper(
    private val manager: SessionManager,
    /** Whether project [projectId] has the setting on. */
    private val enabled: (projectId: String?) -> Boolean,
    /** The worker session id and project of ticket [ticketId]; null when the ticket is unknown. */
    private val ticketOf: (ticketId: String) -> Pair<String?, String?>?,
    private val executor: Executor,
    /** Called after a session was removed, so every client hears the new session list. */
    private val onRemoved: (sessionId: String) -> Unit = {},
) {
    /** Worker sessions of done tickets, waiting for their turn to end. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** The board's transition sink: a move into done. */
    fun onTicketTransition(ticketId: String, status: String) {
        if (status != TicketStatus.DONE) return
        executor.execute {
            runCatching {
                val (sessionId, projectId) = ticketOf(ticketId) ?: return@runCatching
                if (sessionId == null || !enabled(projectId)) return@runCatching
                pending += sessionId
                reap(sessionId)
            }.onFailure { System.err.println("[infantry] done-worker reaper failed for ticket $ticketId: $it") }
        }
    }

    /** A pending worker that came to rest is reaped now. */
    fun onSessionEvent(event: SessionEvent) {
        if (event !is SessionEvent.Status || event.session.id !in pending || event.session.isBusy()) return
        executor.execute { runCatching { reap(event.session.id) } }
    }

    private fun reap(sessionId: String) {
        val session = manager.get(sessionId) ?: run { pending -= sessionId; return }
        if (session.isBusy() && !manager.isStaleLive(sessionId, session)) return // its turn's end calls back here
        if (!pending.remove(sessionId)) return // another thread got here first
        manager.kill(sessionId, force = true, by = "done-worker reaper", reason = "its ticket is done")
        manager.delete(sessionId)
        println("[infantry] removed worker session $sessionId: its ticket is done (autoRemoveDoneWorkers)")
        onRemoved(sessionId)
    }

    /** For tests: the sessions still waiting for their turn to end. */
    internal fun pendingSessions(): Set<String> = pending.toSet()
}
