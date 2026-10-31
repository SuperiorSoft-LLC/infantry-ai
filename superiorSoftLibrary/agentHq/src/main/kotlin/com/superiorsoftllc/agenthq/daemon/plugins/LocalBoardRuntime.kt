package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardPacing
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRelatedDraft
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.daemon.api.board.BoardStoryInputs
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTuning
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents
import com.superiorsoftllc.agenthq.daemon.api.usage.UsageMeters
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.SettingsService
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDraft
import com.superiorsoftllc.agenthq.ticketfiles.TicketInfo
import com.superiorsoftllc.agenthq.ticketfiles.TicketLinkInfo
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import com.superiorsoftllc.agenthq.usage.UsagePacing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * DP-5h-3a2: the daemon's [BoardRuntime] for a board plugin: [LocalBoardHost] for the host, and the settings / registries
 * `BoardWiring.create` handed the core-built board, as [BoardTuning] (plain values and JSON, no core type crosses). Mapping only.
 * DaemonMain does not hand it over yet (DP-5h-3b): until then the core `BoardWiring` builds the board and plugins see null.
 */
class LocalBoardRuntime(
    override val host: BoardHost,
    override val ticketEvents: TicketEvents,
    override val stories: BoardStoryInputs,
    override val epics: BoardEpicInputs,
    override val tuning: BoardTuning,
) : BoardRuntime {
    companion object {
        /** The tuning over the settings, registries and attention store, as `BoardWiring.create` wired them. */
        fun tuning(
            settings: SettingsService,
            manager: SessionManager,
            usagePacing: UsagePacing,
            attention: AttentionStore,
            roles: RoleService,
            projectStore: ProjectStore,
            relatedDescriptionRef: AtomicReference<RelatedDescription>,
            skillsKnown: (String) -> Boolean = { false },
            profilesAvailable: Boolean = true,
        ): BoardTuning = object : BoardTuning {
            override fun profilesAvailable() = profilesAvailable
            override fun unknownSkills(ids: List<String>) = ids.filter { !skillsKnown(it) }
            override fun exitBehavior() = settings.ticketExitBehavior()
            override fun autoDoneMinOutputs() = settings.limit(LimitRegistry.AUTO_DONE_MIN_OUTPUTS)
            override fun roleIds() = roles.all().map { it.id }.filter { it != RoleIds.CUSTOM }
            override fun weightExecutorsJson() = settings.weightExecutors().toJson().toString()
            override fun weightContextJson() = settings.weightContext().toJson().toString()
            override fun laneCap(projectId: String?, role: String) = projectStore.laneCapOf(projectId, role)
            override fun routingRulesJson(projectId: String?) =
                Json.encodeToString(ListSerializer(RoutingRule.serializer()), projectStore.routingRulesOf(projectId))
            override fun routingRefused(ticketId: String, projectId: String?, detail: String) {
                attention.raise(kind = AttentionKind.LAUNCH_BLOCKED, ticketId = ticketId, outcome = "routing_refused", detail = detail)
            }
            override fun pacing(providerId: String?, model: String?): BoardPacing {
                val pid = providerId ?: manager.providers.firstOrNull()?.id ?: UsageMeters.CLAUDE
                return when (val v = usagePacing.check(UsageMeters.meterFor(pid, model ?: manager.providerFor(pid)?.defaultModel()))) {
                    is PacingVerdict.Over -> BoardPacing(over = true, reason = v.reason)
                    is PacingVerdict.Allow -> BoardPacing(warning = v.warning)
                }
            }
            override fun relatedDescription(draft: BoardRelatedDraft): String? = relatedDescriptionRef.get().describe(
                RelatedDraft(
                    draft.ticketId, draft.title, draft.description, draft.projectId,
                    draft.rows.map { TicketInfo(it.id, it.title, it.description, it.status, it.projectId, it.branch, it.base) },
                    draft.links.map { TicketLinkInfo(it.ticketId, it.relatedId) },
                ),
            )
        }
    }
}
