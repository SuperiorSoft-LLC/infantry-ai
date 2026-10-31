package com.superiorsoftllc.agenthq.daemon

import java.util.concurrent.atomic.AtomicReference

/** What core consumers need to know of one ticket (no board type). */
data class BoardTicketRef(val sessionId: String?, val projectId: String?, val status: String)

/**
 * DP-5h-3e-a: the small core-owned port the daemon's own consumers (start charge, orchestrator link, session reaper, roster titles,
 * handover, first-turn brief, worker sweep) use to reach the ticket board. It names no board class; the adapter lives with the
 * board construction ([BoardWiring.ports]) and goes away with it (DP-5h-4), when a plugin-backed port takes its place.
 */
interface BoardPorts {
    /** The ticket [id] names, null when unknown. */
    fun ticket(id: String): BoardTicketRef?
    /** The session that started the worker [workerSessionId], null when unknown. */
    fun starterOf(workerSessionId: String): String?
    /** Ticket title by worker session id. */
    fun titlesBySession(): Map<String, String>
    /** The ticket start brief of a ticketed session, null when it has none. */
    fun brief(sessionId: String): String?
    /** The plain moves [TicketStartCharge] runs. */
    fun startMoves(): StartMoves<Any>
    /** Record the `Files read:` line of a review report. */
    fun saveFilesRead(ticketId: String, line: String)
    /** Clear the queue marks, as a re-adopt does. */
    fun clearQueueMarks()
    /** The in-progress worker sweep; the ids it flagged. */
    fun sweepWorkers(graceMs: Long, flag: (ticketId: String, sessionId: String?, detail: String) -> Unit = { _, _, _ -> }): List<String>
}

/** The port consumers hold: resolves the attached [BoardPorts] at CALL time; with none attached it answers "no board". */
class BoardPortRef : BoardPorts {
    private val ref = AtomicReference<BoardPorts?>(null)
    val attached: Boolean get() = ref.get() != null
    fun attach(ports: BoardPorts) = ref.set(ports)
    fun detach() = ref.set(null)

    override fun ticket(id: String) = ref.get()?.ticket(id)
    override fun starterOf(workerSessionId: String) = ref.get()?.starterOf(workerSessionId)
    override fun titlesBySession() = ref.get()?.titlesBySession().orEmpty()
    override fun brief(sessionId: String) = ref.get()?.brief(sessionId)
    override fun startMoves(): StartMoves<Any> = ref.get()?.startMoves() ?: error("this daemon has no board")
    override fun saveFilesRead(ticketId: String, line: String) { ref.get()?.saveFilesRead(ticketId, line) }
    override fun clearQueueMarks() { ref.get()?.clearQueueMarks() }
    override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit) = ref.get()?.sweepWorkers(graceMs, flag).orEmpty()
}

/** The 60 s ticket-worker sweep (GAP-006b), over the port: with no board it does nothing. */
class WorkerSweep(ports: BoardPorts, graceMs: () -> Long, flag: (String, String?, String) -> Unit) : AutoCloseable {
    private val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "agenthq-ticket-worker-sweep").also { it.isDaemon = true }
    }
    init {
        exec.scheduleWithFixedDelay({
            runCatching { ports.sweepWorkers(graceMs(), flag) }
                .onFailure { System.err.println("[agentHq] ticket worker sweep failed: ${it.message}") }
        }, 60L, 60L, java.util.concurrent.TimeUnit.SECONDS)
    }
    override fun close() { exec.shutdownNow() }
}
