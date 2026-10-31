package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreTicket
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import kotlinx.serialization.json.Json

/**
 * DP-5h-3e-b: the plugin's [BoardCoreAccess] over the board it built and the ONLY [EpicStore]: what core's consumers need, as plain data
 * (no board type crosses the class loader). Mapping only; every answer is the board's / the store's own.
 */
class PluginCoreAccess(private val board: TicketBoard, private val epics: EpicStore) : BoardCoreAccess {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun ticket(id: String): BoardCoreTicket? = runCatching { board.get(id) }.getOrNull()?.let { BoardCoreTicket(it.sessionId, it.projectId, it.status) }
    override fun starterOf(workerSessionId: String): String? = board.starterOf(workerSessionId)
    override fun titlesBySession(): Map<String, String> = board.list().mapNotNull { t -> t.sessionId?.let { it to t.title } }.toMap()
    override fun brief(sessionId: String): String? = board.brief(sessionId)
    override fun cutRepo(projectId: String?): String? = board.cutRepo(projectId)?.path
    override fun epicBaseBranchOf(epicId: String): String? = epics.baseBranchOf(epicId)
    override fun ticketIdsByEpic(): Map<String, List<String>> = board.list().filter { it.epicId != null }.groupBy({ it.epicId!! }, { it.id })
    override fun epicJson(epicId: String): String? = epics.get(epicId)?.let { json.encodeToString(Epic.serializer(), it) }
    override fun laneCapsChanged(projectId: String) = board.laneCapsChanged(projectId)
    override fun clearQueueMarks(): Int = board.clearQueueMarks()
    override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit): List<String> = board.sweepWorkers(graceMs, flag)
    override fun getTicketJson(id: String): String = json.encodeToString(Ticket.serializer(), board.get(id))
    override fun moveTicketJson(moveJson: String, startedBy: String?): String =
        json.encodeToString(Ticket.serializer(), board.move(json.decodeFromString(TicketMove.serializer(), moveJson), startedBy = startedBy).ticket)
    override fun stopQueueDrain() = board.stopQueueDrain()
    override fun close() = board.close()
}
