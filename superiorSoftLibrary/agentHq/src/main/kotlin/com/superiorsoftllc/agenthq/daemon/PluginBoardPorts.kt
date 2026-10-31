package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import kotlinx.serialization.json.Json

/**
 * DP-5h-3e-b: the core's [BoardPorts] over the [BoardCoreAccess] the board plugin attached (no board class in core). The moves are wire JSON;
 * the answer type of [startMoves] is the wire [Ticket] itself.
 */
class PluginBoardPorts(private val access: BoardCoreAccess) : BoardPorts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun ticket(id: String) = access.ticket(id)?.let { BoardTicketRef(it.sessionId, it.projectId, it.status) }
    override fun starterOf(workerSessionId: String) = access.starterOf(workerSessionId)
    override fun titlesBySession() = access.titlesBySession()
    override fun brief(sessionId: String) = access.brief(sessionId)
    override fun startMoves(): StartMoves<Any> = object : StartMoves<Any> {
        override fun get(id: String): Ticket = json.decodeFromString(Ticket.serializer(), access.getTicketJson(id))
        override fun move(move: TicketMove, startedBy: String?): Any =
            json.decodeFromString(Ticket.serializer(), access.moveTicketJson(json.encodeToString(TicketMove.serializer(), move), startedBy))
        override fun ticketOf(answer: Any): Ticket = answer as Ticket
    }
    /** `Files read:` is recorded by core's ticket files, which attach to the daemon store, not the board. */
    override fun saveFilesRead(ticketId: String, line: String) {}
    override fun clearQueueMarks() { access.clearQueueMarks() }
    override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit) = access.sweepWorkers(graceMs, flag)
}
