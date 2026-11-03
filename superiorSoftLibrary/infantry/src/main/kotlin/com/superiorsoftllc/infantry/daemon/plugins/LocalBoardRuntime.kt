// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardPacing
import com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.protocol.RoutingRule
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.daemon.RolesLookup
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.SettingsService
import com.superiorsoftllc.infantry.usage.PacingVerdict
import com.superiorsoftllc.infantry.usage.UsagePacing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DP-5h-3a: the LOCAL [BoardRuntime] over the daemon's own classes (settings, project store, roles, usage pacing, attention, the
 * clients' push). Mapping only: each answer is what `DaemonMain` handed `TicketBoard` directly before. `DaemonMain` passes
 * [coreBuiltBoard] = false: the board plugin builds the board (core builds none).
 */
class LocalBoardRuntime(
    /** DP-5h-3c: resolved at call time, so the runtime can be built before the board exists. */
    private val hostSource: () -> BoardHost,
    private val store: InfantryStore,
    private val settings: SettingsService,
    private val projectStore: ProjectStore,
    private val roles: RolesLookup,
    private val pacingCheck: (providerId: String?, model: String?) -> PacingVerdict,
    private val attention: Attention,
    private val pushToClients: (List<Frame>) -> Unit,
    override val coreBuiltBoard: Boolean = true,
    /** DP-5h-3a2: the typed ticket events, the story / epic inputs, the RELATED block and the skill check; the defaults answer none. */
    override val ticketEvents: com.superiorsoftllc.infantry.daemon.api.board.TicketEvents = com.superiorsoftllc.infantry.daemon.api.board.NoTicketEvents,
    override val stories: com.superiorsoftllc.infantry.daemon.api.board.BoardStoryInputs = com.superiorsoftllc.infantry.daemon.api.board.BoardStoryInputs.NONE,
    override val epics: com.superiorsoftllc.infantry.daemon.api.board.BoardEpicInputs = com.superiorsoftllc.infantry.daemon.api.board.BoardEpicInputs.NONE,
    private val related: (com.superiorsoftllc.infantry.daemon.api.board.BoardRelatedDraft) -> String? = { null },
    private val skillKnown: (String) -> Boolean = { true },
    /** DP-5h-3e-b: the plugin that built the board hands core its access (null = ignored), and takes it back. */
    private val onAttachCore: (com.superiorsoftllc.infantry.daemon.api.board.BoardCoreAccess) -> Unit = {},
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
        attention.raise(kind = AttentionKinds.LAUNCH_BLOCKED, ticketId = ticketId, outcome = "routing_refused", detail = detail)
    }
    override fun relatedDescription(draft: com.superiorsoftllc.infantry.daemon.api.board.BoardRelatedDraft): String? = related(draft)
    override fun includeLinkedTasksContext(): Boolean? = settings.includeLinkedTasksContext()
    override fun includeSkillsContext(): Boolean? = settings.includeSkillsContext()
    override fun includeFinishInstructions(): Boolean? = settings.includeFinishInstructions()
    override fun skillIds(): List<String> = settings.skillIds()
    override fun roleNotes(projectId: String, role: String) = roleNotesOf(projectId, role)
    override fun relatedBlock(ticketId: String, description: String) = relatedBlockOf(ticketId, description)
    override fun watchedPrograms(): List<String> =
        if (settings.limit(com.superiorsoftllc.infantry.settings.LimitRegistry.WATCH_ENABLED) == 1L &&
            settings.limit(com.superiorsoftllc.infantry.settings.LimitRegistry.WATCH_FORCED) == 1L
        ) com.superiorsoftllc.infantry.runner.ToolProfiles.shimPrograms(settings.toolProfiles()) else emptyList()
    override fun fixedFirstTurn() = fixedFirstTurnOf()
    override fun unknownSkills(ids: List<String>): List<String> = ids.filter { !skillKnown(it) }
    override fun push(frames: List<Frame>) = pushToClients(frames)
    override fun attachCore(access: com.superiorsoftllc.infantry.daemon.api.board.BoardCoreAccess) = onAttachCore(access)
    override fun detachCore() = onDetachCore()
}
