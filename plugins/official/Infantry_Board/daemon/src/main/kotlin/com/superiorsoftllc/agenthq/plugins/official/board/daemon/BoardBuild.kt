package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.board.BoardPacing
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicHandler
import com.superiorsoftllc.agenthq.epics.EpicPersistence
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.epics.ProjectDirectory
import com.superiorsoftllc.agenthq.mvp.BoardHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler.Companion.asStory
import com.superiorsoftllc.agenthq.mvp.StoryTickets
import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.settings.WeightContext
import com.superiorsoftllc.agenthq.settings.WeightExecutors
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** DP-5h-3a2: the board, its story shim and the epic handler, built by the plugin from the host's [BoardRuntime]. */
class BuiltBoard(val board: TicketBoard, val stories: StoryHandler, val storyBoard: BoardHandler, val epics: EpicHandler)

object BoardBuild {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Builds exactly what the core `BoardWiring.create` + `DaemonMain` built, from API-level inputs only. */
    fun build(runtime: BoardRuntime): BuiltBoard {
        val t = runtime.tuning
        val board = TicketBoard(
            host = runtime.host,
            profilesAvailable = t.profilesAvailable(),
            roleIds = { t.roleIds().ifEmpty { com.superiorsoftllc.agenthq.role.RoleIds.CANONICAL } },
            exitBehavior = t::exitBehavior,
            autoDoneMinOutputs = t::autoDoneMinOutputs,
            weightExecutors = { t.weightExecutorsJson()?.let { s -> runCatching { WeightExecutors.parse(json.parseToJsonElement(s)) }.getOrNull() } ?: WeightExecutors.DEFAULT },
            weightContext = { t.weightContextJson()?.let { s -> runCatching { WeightContext.parse(json.parseToJsonElement(s)) }.getOrNull() } ?: WeightContext.DEFAULT },
            laneCap = t::laneCap,
            routingRules = { p -> t.routingRulesJson(p)?.let { s -> runCatching { json.decodeFromString(ListSerializer(RoutingRule.serializer()), s) }.getOrNull() }.orEmpty() },
            routingRefused = t::routingRefused,
            pacing = { provider, model -> t.pacing(provider, model).toVerdict() },
            relatedDescription = RelatedDescription { d ->
                t.relatedDescription(
                    com.superiorsoftllc.agenthq.daemon.api.board.BoardRelatedDraft(
                        d.ticketId, d.title, d.description, d.projectId,
                        d.rows.map { r ->
                            com.superiorsoftllc.agenthq.daemon.api.board.StoredTicket(
                                r.id, r.title, r.description, status = r.status, position = 0, createdAt = 0, updatedAt = 0,
                                projectId = r.projectId, branch = r.branch, base = r.base,
                            )
                        },
                        d.links.map { l -> com.superiorsoftllc.agenthq.daemon.api.board.StoredLink(l.ticketId, l.relatedId, "relates_to") },
                    ),
                )
            },
            unknownSkills = t::unknownSkills,
        )
        val storyInputs = runtime.stories
        val stories = StoryHandler(
            board = object : StoryTickets {
                override fun get(id: String): Ticket = board.get(id)
                override fun save(request: TicketSave): Ticket = board.save(request).ticket
                override fun moveWithoutStart(request: TicketMove): Ticket = board.move(request, startOnInProgress = false).ticket
            },
            resolve = storyInputs::ticketFor,
            onEpicChanged = storyInputs::epicChanged,
        )
        val storyBoard = BoardHandler(stories = { board.list().map { it.asStory() } })
        val e = runtime.epics
        val store = EpicStore(
            persistence = object : EpicPersistence {
                override fun loadAll(): List<Epic> = e.loadAll().map { json.decodeFromString(Epic.serializer(), it) }
                override fun upsert(epic: Epic) = e.upsert(json.encodeToString(Epic.serializer(), epic))
            },
            projects = ProjectDirectory { id -> if (e.projectKnown(id)) id else throw IllegalArgumentException("unknown project: $id") },
            ticketIdsByEpic = { board.list().filter { it.epicId != null }.groupBy({ it.epicId!! }, { it.id }) },
        )
        val epics = EpicHandler(store, baseCheck = { epic, branch -> e.checkBase(epic.projectId, branch) }, branchName = e::branchName)
        return BuiltBoard(board, stories, storyBoard, epics)
    }

    private fun BoardPacing.toVerdict(): PacingVerdict =
        if (over) PacingVerdict.Over(reason ?: "over the plan allowance") else PacingVerdict.Allow(warning)
}
