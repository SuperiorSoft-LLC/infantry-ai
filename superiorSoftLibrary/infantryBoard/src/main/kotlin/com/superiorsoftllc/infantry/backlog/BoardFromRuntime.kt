package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.board.BoardRelatedDraft
import com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
import com.superiorsoftllc.infantry.daemon.api.board.StoredLink
import com.superiorsoftllc.infantry.daemon.api.board.StoredTicket
import com.superiorsoftllc.infantry.ticketfiles.RelatedDescription
import com.superiorsoftllc.infantry.protocol.RoutingRule
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.settings.WeightContext
import com.superiorsoftllc.infantry.settings.WeightExecutors
import com.superiorsoftllc.infantry.usage.PacingVerdict
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
    const val HANDOFF_NOTE = "tickets.agentTasks.handoffNote"
    const val UNASSIGN_WAIT_SECONDS = "limits.agentTasks.unassignWaitSeconds"
    const val HANDOFF_COMMITS = "limits.agentTasks.handoffCommits"
    const val HANDOFF_CHARS = "limits.agentTasks.handoffChars"
    private val EXIT_BEHAVIORS = setOf("leave", "done")
    private val json = Json { ignoreUnknownKeys = true }

    /** The first-turn context composer over what the runtime and its host offer (knowledge, skills, role notes, the RELATED block, the project switches). */
    fun contextOf(runtime: BoardRuntime): TicketContext {
        val real = com.superiorsoftllc.infantry.context.TicketContextComposer(
        object : com.superiorsoftllc.infantry.settings.ProjectSettings {
            override fun all() = kotlinx.serialization.json.JsonObject(emptyMap())
            override fun set(key: String, value: kotlinx.serialization.json.JsonElement) = all()
            override fun includeLinkedTasksContext() = runtime.includeLinkedTasksContext()
            override fun includeSkillsContext() = runtime.includeSkillsContext()
            override fun skillIds() = runtime.skillIds()
            override fun includeFinishInstructions() = runtime.includeFinishInstructions()
        },
        com.superiorsoftllc.infantry.context.KnowledgeSection { runtime.host.context.knowledgeSection(it, null) },
        com.superiorsoftllc.infantry.context.SkillsSection { ids, include -> runtime.host.context.skillsSection(ids, include) },
        roleNotes = com.superiorsoftllc.infantry.context.RoleNotesSource { project, role -> runtime.roleNotes(project, role) },
        related = com.superiorsoftllc.infantry.ticketfiles.RelatedWorkSource { id, desc -> runtime.relatedBlock(id, desc) },
        watchedPrograms = { runtime.watchedPrograms() },
        )
        return object : TicketContext {
            override fun compose(input: com.superiorsoftllc.infantry.context.ContextInput) =
                runtime.fixedFirstTurn()?.let { (prompt, sources) -> com.superiorsoftllc.infantry.context.ComposedContext(prompt, sources.map { com.superiorsoftllc.infantry.session.ContextSource(it.first, it.second) }) } ?: real.compose(input)
            override fun effective(override: Boolean?, skillsOverride: Boolean?) = real.effective(override, skillsOverride)
        }
    }

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
            handoffMode = {
                val parsed = runtime.setting(HANDOFF_NOTE)?.let { runCatching { (json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
                parsed?.takeIf { it in setOf("auto", "worker", "off") } ?: "auto"
            },
            unassignWaitSeconds = { runtime.limit(UNASSIGN_WAIT_SECONDS) ?: 120L },
            handoffCommits = { runtime.limit(HANDOFF_COMMITS) ?: 10L },
            handoffChars = { runtime.limit(HANDOFF_CHARS) ?: 1500L },
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
