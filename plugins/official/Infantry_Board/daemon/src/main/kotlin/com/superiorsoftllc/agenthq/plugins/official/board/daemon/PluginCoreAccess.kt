package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreTicket
import com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketListQuery
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.backlog.PromptLaunch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json

/**
 * DP-5h-3e-b: the plugin's [BoardCoreAccess] over the board it built and the ONLY [EpicStore]: what core's consumers need, as plain data
 * (no board type crosses the class loader). Mapping only; every answer is the board's / the store's own.
 */
class PluginCoreAccess(private val board: TicketBoard, private val epics: EpicStore, private val epicInputs: BoardEpicInputs = BoardEpicInputs.NONE) : BoardCoreAccess {
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
    override fun invoke(op: String, payload: String, startedBy: String?): String = when (op) {
        "list" -> {
            val q = json.decodeFromString(TicketListQuery.serializer(), payload)
            json.encodeToString(ListSerializer(Ticket.serializer()), board.list(q.projectId, q.epicId))
        }
        "get" -> json.encodeToString(Ticket.serializer(), board.get(payload))
        "save" -> json.encodeToString(Ticket.serializer(), board.save(json.decodeFromString(TicketSave.serializer(), payload)).ticket)
        "move" -> moveTicketJson(payload, startedBy)
        "delete" -> { board.delete(payload); "" }
        "link" -> json.encodeToString(Ticket.serializer(), board.link(json.decodeFromString(TicketLinkRequest.serializer(), payload)).ticket)
        "unlink" -> json.encodeToString(Ticket.serializer(), board.unlink(json.decodeFromString(TicketLinkRequest.serializer(), payload)).ticket)
        "reorder" -> { board.reorder(json.decodeFromString(TicketReorder.serializer(), payload)); "" }
        "startNext" -> board.startNextUnattended(payload.ifBlank { null })?.let { json.encodeToString(Ticket.serializer(), it.ticket) }.orEmpty()
        "startPrompt" -> {
            val a = json.parseToJsonElement(payload).jsonArray
            fun s(i: Int) = (a[i] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (val l = board.startPromptUnattended(s(0)!!, s(1)!!, s(2))) {
                is PromptLaunch.Started -> "S:${l.sessionId}"
                is PromptLaunch.Refused -> "R:${l.detail}"
            }
        }
        "epicGet" -> epics.get(payload)?.let { json.encodeToString(Epic.serializer(), it) }.orEmpty()
        "epicList" -> json.encodeToString(ListSerializer(Epic.serializer()), epics.list(payload))
        "epicSave" -> {
            val a = json.parseToJsonElement(payload).jsonArray
            json.encodeToString(Epic.serializer(), epics.save((a[0] as JsonPrimitive).content, (a[1] as JsonPrimitive).content, (a[2] as JsonPrimitive).content))
        }
        "epicSetBase" -> {
            val a = json.parseToJsonElement(payload).jsonArray
            val id = (a[0] as JsonPrimitive).content
            val branch = (a[1] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val updated = if (branch == null) epics.setBaseBranch(id, null)
            else epics.setBaseBranch(id, epicInputs.branchName(branch)) { e, b -> epicInputs.checkBase(e.projectId, b) }
            json.encodeToString(Epic.serializer(), updated)
        }
        else -> throw IllegalArgumentException("unknown board operation: $op")
    }
    override fun stopQueueDrain() = board.stopQueueDrain()
    override fun close() = board.close()
}
