package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.backlog.PromptLaunch
import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketListQuery
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * DP-5h-3e-b: the board operations [LocalBoardServices] applies the host's policy and scope over, WITHOUT naming a board class: the core
 * board ([CoreBoardOps], over a [TicketBoard] and [EpicStore], until the board is the plugin's alone) or the plugin's ([PluginBoardOps],
 * over [BoardCoreAccess.invoke], wire JSON). A refusal is an [IllegalArgumentException] with the board's own text on both.
 */
interface BoardOps {
    fun list(projectId: String?, epicId: String?): List<Ticket>
    fun get(id: String): Ticket
    fun save(request: TicketSave): Ticket
    fun move(request: TicketMove, startedBy: String?): Ticket
    fun delete(id: String)
    fun link(request: TicketLinkRequest): Ticket
    fun unlink(request: TicketLinkRequest): Ticket
    fun reorder(request: TicketReorder)
    fun startNext(agentFilter: String?): Ticket?
    fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptLaunch
    fun brief(sessionId: String): String?
    fun epicGet(id: String): Epic?
    fun epicList(projectId: String): List<Epic>
    fun epicSave(projectId: String, name: String, goal: String): Epic
    /** [branch] null clears the base; a name is checked against the epic's project (a refusal is an [IllegalArgumentException]). */
    fun epicSetBase(id: String, branch: String?): Epic
}

/** The core-built board and epic store, as before DP-5h-3e-b. */
class CoreBoardOps(
    private val board: TicketBoard,
    private val epics: EpicStore,
    private val baseCheck: ((Epic, String) -> Unit)? = null,
) : BoardOps {
    override fun list(projectId: String?, epicId: String?) = board.list(projectId, epicId)
    override fun get(id: String) = board.get(id)
    override fun save(request: TicketSave) = board.save(request).ticket
    override fun move(request: TicketMove, startedBy: String?) = board.move(request, startedBy = startedBy).ticket
    override fun delete(id: String) { board.delete(id) }
    override fun link(request: TicketLinkRequest) = board.link(request).ticket
    override fun unlink(request: TicketLinkRequest) = board.unlink(request).ticket
    override fun reorder(request: TicketReorder) { board.reorder(request) }
    override fun startNext(agentFilter: String?) = board.startNextUnattended(agentFilter)?.ticket
    override fun startPrompt(agentId: String, prompt: String, projectId: String?) = board.startPromptUnattended(agentId, prompt, projectId)
    override fun brief(sessionId: String) = board.brief(sessionId)
    override fun epicGet(id: String) = epics.get(id)
    override fun epicList(projectId: String) = epics.list(projectId)
    override fun epicSave(projectId: String, name: String, goal: String) = epics.save(projectId, name, goal)
    override fun epicSetBase(id: String, branch: String?): Epic {
        if (branch == null) return epics.setBaseBranch(id, null)
        val check = baseCheck ?: throw IllegalArgumentException("this daemon has no repository to check a base branch against")
        return epics.setBaseBranch(id, branch, check)
    }
}

/** The plugin's board, reached through [BoardCoreAccess.invoke]; every value is the wire JSON. */
class PluginBoardOps(private val access: BoardCoreAccess) : BoardOps {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun ticket(s: String) = json.decodeFromString(Ticket.serializer(), s)
    private fun epic(s: String) = json.decodeFromString(Epic.serializer(), s)

    override fun list(projectId: String?, epicId: String?): List<Ticket> =
        json.decodeFromString(ListSerializer(Ticket.serializer()), access.invoke("list", json.encodeToString(TicketListQuery.serializer(), TicketListQuery(projectId = projectId, epicId = epicId)), null))
    override fun get(id: String) = ticket(access.invoke("get", id, null))
    override fun save(request: TicketSave) = ticket(access.invoke("save", json.encodeToString(TicketSave.serializer(), request), null))
    override fun move(request: TicketMove, startedBy: String?) = ticket(access.invoke("move", json.encodeToString(TicketMove.serializer(), request), startedBy))
    override fun delete(id: String) { access.invoke("delete", id, null) }
    override fun link(request: TicketLinkRequest) = ticket(access.invoke("link", json.encodeToString(TicketLinkRequest.serializer(), request), null))
    override fun unlink(request: TicketLinkRequest) = ticket(access.invoke("unlink", json.encodeToString(TicketLinkRequest.serializer(), request), null))
    override fun reorder(request: TicketReorder) { access.invoke("reorder", json.encodeToString(TicketReorder.serializer(), request), null) }
    override fun startNext(agentFilter: String?): Ticket? = access.invoke("startNext", agentFilter.orEmpty(), null).takeIf { it.isNotEmpty() }?.let(::ticket)
    override fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptLaunch {
        val payload = buildJsonArray { add(JsonPrimitive(agentId)); add(JsonPrimitive(prompt)); add(projectId?.let { JsonPrimitive(it) } ?: JsonNull) }.toString()
        val answer = access.invoke("startPrompt", payload, null)
        return if (answer.startsWith("S:")) PromptLaunch.Started(answer.drop(2)) else PromptLaunch.Refused(answer.removePrefix("R:"))
    }
    override fun brief(sessionId: String) = access.brief(sessionId)
    override fun epicGet(id: String): Epic? = access.invoke("epicGet", id, null).takeIf { it.isNotEmpty() }?.let(::epic)
    override fun epicList(projectId: String): List<Epic> = json.decodeFromString(ListSerializer(Epic.serializer()), access.invoke("epicList", projectId, null))
    override fun epicSave(projectId: String, name: String, goal: String) =
        epic(access.invoke("epicSave", buildJsonArray { add(JsonPrimitive(projectId)); add(JsonPrimitive(name)); add(JsonPrimitive(goal)) }.toString(), null))
    override fun epicSetBase(id: String, branch: String?) =
        epic(access.invoke("epicSetBase", buildJsonArray { add(JsonPrimitive(id)); add(branch?.let { JsonPrimitive(it) } ?: JsonNull) }.toString(), null))
}
