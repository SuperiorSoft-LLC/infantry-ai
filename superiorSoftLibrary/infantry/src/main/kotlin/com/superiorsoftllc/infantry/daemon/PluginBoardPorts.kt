// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * DP-5h-3e-b: the core's [BoardPorts] over the [BoardCoreAccess] the board plugin attached (no board class in core). The moves are wire JSON;
 * the answer type of [startMoves] is the wire [Ticket] itself.
 */
class PluginBoardPorts(private val access: BoardCoreAccess, private val ticketFiles: () -> com.superiorsoftllc.infantry.ticketfiles.TicketFiles? = { null }) : BoardPorts {
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
    override fun saveFilesRead(ticketId: String, line: String) { ticketFiles()?.saveFilesRead(ticketId, line) }
    override fun clearQueueMarks() { access.clearQueueMarks() }
    override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit) = access.sweepWorkers(graceMs, flag)
    override fun restartElsewhere(ticketId: String, lostRunner: String, problem: String): String? {
        val payload = JsonArray(listOf(ticketId, lostRunner, problem).map(::JsonPrimitive)).toString()
        val answer = runCatching { access.invoke("restartElsewhere", payload, null) }.getOrElse { return it.message ?: "restart refused" }
        return if (answer.startsWith("S:")) null else answer.removePrefix("R:").ifBlank { "restart refused" }
    }
    override fun ticketOfWorker(sessionId: String): String? {
        if (sessionId !in access.titlesBySession()) return null
        val tickets = json.decodeFromString(ListSerializer(Ticket.serializer()), access.invoke("list", "{}", null))
        return tickets.firstOrNull { it.sessionId == sessionId && it.status == TicketStatus.IN_PROGRESS }?.id
    }
    override fun epicBaseBranchOf(epicId: String) = access.epicBaseBranchOf(epicId)
    override fun ticketIdsByEpic() = access.ticketIdsByEpic()
    override fun epicJson(epicId: String) = access.epicJson(epicId)
    override fun cutRepo(projectId: String?) = access.cutRepo(projectId)
    override fun laneCapsChanged(projectId: String) = access.laneCapsChanged(projectId)
    override fun ops(): com.superiorsoftllc.infantry.daemon.plugins.BoardOps = com.superiorsoftllc.infantry.daemon.plugins.PluginBoardOps(access)
    override fun stopQueueDrain() = access.stopQueueDrain()
    override fun close() = access.close()
}
