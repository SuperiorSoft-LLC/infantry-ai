package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardPacing
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.SettingsService
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import com.superiorsoftllc.agenthq.usage.UsagePacing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DP-5h-3a: the LOCAL [BoardRuntime] over the daemon's own classes (settings, project store, roles, usage pacing, attention, the
 * clients' push). Mapping only: each answer is what `DaemonMain` handed `TicketBoard` directly before. [coreBuiltBoard] is true
 * while `DaemonMain` still builds the board through `BoardWiring` (DP-5h-3b flips it), so a board plugin leaves construction to core.
 */
class LocalBoardRuntime(
    /** DP-5h-3c: resolved at call time, so the runtime can be built before the board exists. */
    private val hostSource: () -> BoardHost,
    private val store: AgentHqStore,
    private val settings: SettingsService,
    private val projectStore: ProjectStore,
    private val roles: RoleService,
    private val pacingCheck: (providerId: String?, model: String?) -> PacingVerdict,
    private val attention: AttentionStore,
    private val pushToClients: (List<Frame>) -> Unit,
    override val coreBuiltBoard: Boolean = true,
    /** DP-5h-3a2: the typed ticket events, the story / epic inputs, the RELATED block and the skill check; the defaults answer none. */
    override val ticketEvents: com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents = com.superiorsoftllc.agenthq.daemon.api.board.NoTicketEvents,
    override val stories: com.superiorsoftllc.agenthq.daemon.api.board.BoardStoryInputs = com.superiorsoftllc.agenthq.daemon.api.board.BoardStoryInputs.NONE,
    override val epics: com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs = com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs.NONE,
    private val related: (com.superiorsoftllc.agenthq.daemon.api.board.BoardRelatedDraft) -> String? = { null },
    private val skillKnown: (String) -> Boolean = { true },
    /** DP-5h-3e-b: the plugin that built the board hands core its access (null = ignored), and takes it back. */
    private val onAttachCore: (com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess) -> Unit = {},
    private val onDetachCore: () -> Unit = {},
    private val roleNotesOf: (projectId: String, role: String) -> List<Pair<String, String>> = { _, _ -> emptyList() },
    private val relatedBlockOf: (ticketId: String, description: String) -> String? = { _, _ -> null },
    private val fixedFirstTurnOf: () -> Pair<String, List<Pair<String, Int>>>? = { null },
) : BoardRuntime {
    override val host: BoardHost get() = hostSource()
    override fun setting(key: String): String? = store.settings.get(key)?.value
    override fun limit(key: String): Long? = LimitRegistry.ALL.firstOrNull { it.key == key }?.let { settings.limit(it) }
    override fun laneCap(projectId: String?, role: String): Int = projectStore.laneCapOf(projectId, role)
    override fun routingRules(projectId: String?): String =
        Json.encodeToString(ListSerializer(RoutingRule.serializer()), projectStore.routingRulesOf(projectId))
    override fun roleIds(): List<String> = roles.all().map { it.id }.filter { it != RoleIds.CUSTOM }
    override fun pacing(providerId: String?, model: String?): BoardPacing = when (val v = pacingCheck(providerId, model)) {
        is PacingVerdict.Over -> BoardPacing(over = v.reason)
        is PacingVerdict.Allow -> BoardPacing(warning = v.warning)
    }
    override fun routingRefused(ticketId: String, projectId: String?, detail: String) {
        attention.raise(kind = AttentionKind.LAUNCH_BLOCKED, ticketId = ticketId, outcome = "routing_refused", detail = detail)
    }
    override fun relatedDescription(draft: com.superiorsoftllc.agenthq.daemon.api.board.BoardRelatedDraft): String? = related(draft)
    override fun includeLinkedTasksContext(): Boolean? = settings.includeLinkedTasksContext()
    override fun includeSkillsContext(): Boolean? = settings.includeSkillsContext()
    override fun skillIds(): List<String> = settings.skillIds()
    override fun roleNotes(projectId: String, role: String) = roleNotesOf(projectId, role)
    override fun relatedBlock(ticketId: String, description: String) = relatedBlockOf(ticketId, description)
    override fun fixedFirstTurn() = fixedFirstTurnOf()
    override fun unknownSkills(ids: List<String>): List<String> = ids.filter { !skillKnown(it) }
    override fun push(frames: List<Frame>) = pushToClients(frames)
    override fun attachCore(access: com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess) = onAttachCore(access)
    override fun detachCore() = onDetachCore()
}
