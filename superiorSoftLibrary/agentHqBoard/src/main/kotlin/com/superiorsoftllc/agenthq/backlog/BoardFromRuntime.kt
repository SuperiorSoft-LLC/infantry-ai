package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRelatedDraft
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.daemon.api.board.StoredLink
import com.superiorsoftllc.agenthq.daemon.api.board.StoredTicket
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.settings.WeightContext
import com.superiorsoftllc.agenthq.settings.WeightExecutors
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * DP-5h-3a: builds the [TicketBoard] from the plugin API's [BoardRuntime] alone (no core type): the same settings, defaults and
 * fallbacks the daemon's `SettingsService` / `ProjectStore` gave it, read at every use so a change applies at once. A stored
 * value that cannot be read falls back to its default, as in core.
 */
object BoardFromRuntime {
    const val EXIT_BEHAVIOR = "tickets.exitBehavior"
    const val AUTO_DONE_MIN_OUTPUTS = "limits.tickets.autoDoneMinOutputs"
    private val EXIT_BEHAVIORS = setOf("leave", "done")
    private val json = Json { ignoreUnknownKeys = true }

    /** [context] is the first-turn context composer (a later step moves its sources onto the runtime); [BoardRuntime.push] gets every board event as frames. */
    fun build(runtime: BoardRuntime, context: TicketContext = TicketContext.NONE): TicketBoard {
        val board = TicketBoard(
            host = runtime.host,
            context = context,
            profilesAvailable = true,
            roleIds = { runtime.roleIds() ?: RoleIds.CANONICAL },
            exitBehavior = {
                val parsed = runtime.setting(EXIT_BEHAVIOR)?.let { runCatching { (json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
                parsed?.takeIf { it in EXIT_BEHAVIORS } ?: "leave"
            },
            autoDoneMinOutputs = { runtime.limit(AUTO_DONE_MIN_OUTPUTS) ?: 1L },
            weightExecutors = {
                val stored = runtime.setting(WeightExecutors.SETTING) ?: return@TicketBoard WeightExecutors.DEFAULT
                runCatching { WeightExecutors.parse(json.parseToJsonElement(stored)) }.getOrDefault(WeightExecutors.DEFAULT)
            },
            weightContext = {
                val stored = runtime.setting(WeightContext.SETTING) ?: return@TicketBoard WeightContext.DEFAULT
                runCatching { WeightContext.parse(json.parseToJsonElement(stored)) }.getOrDefault(WeightContext.DEFAULT)
            },
            laneCap = runtime::laneCap,
            routingRules = { projectId ->
                runCatching { json.decodeFromString(ListSerializer(RoutingRule.serializer()), runtime.routingRules(projectId)) }.getOrDefault(emptyList())
            },
            routingRefused = runtime::routingRefused,
            unknownSkills = runtime::unknownSkills,
            relatedDescription = RelatedDescription { d ->
                runtime.relatedDescription(
                    BoardRelatedDraft(
                        d.ticketId, d.title, d.description, d.projectId,
                        d.rows.map { r -> StoredTicket(r.id, r.title, r.description, status = r.status, position = 0, createdAt = 0, updatedAt = 0, projectId = r.projectId, branch = r.branch, base = r.base) },
                        d.links.map { l -> StoredLink(l.ticketId, l.relatedId, "relates_to") },
                    ),
                )
            },
            pacing = { providerId, model ->
                val p = runtime.pacing(providerId, model)
                p.over?.let { PacingVerdict.Over(it) } ?: PacingVerdict.Allow(p.warning)
            },
        )
        board.eventSink = { event ->
            val envelopes = when (event) {
                is BoardEvent.Changed -> event.tickets.map(::ticketEnvelope)
                is BoardEvent.Replaced -> listOf(ticketsEnvelope(event.tickets))
            }
            runtime.push(envelopes.map { Frame(it.type, it.data, it.sessionId, it.requestId) })
        }
        board.events = runtime.host.events
        return board
    }
}
