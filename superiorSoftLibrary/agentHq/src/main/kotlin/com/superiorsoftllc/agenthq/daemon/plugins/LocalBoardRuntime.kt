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
    override val host: BoardHost,
    private val store: AgentHqStore,
    private val settings: SettingsService,
    private val projectStore: ProjectStore,
    private val roles: RoleService,
    private val pacingCheck: (providerId: String?, model: String?) -> PacingVerdict,
    private val attention: AttentionStore,
    private val pushToClients: (List<Frame>) -> Unit,
    override val coreBuiltBoard: Boolean = true,
) : BoardRuntime {
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
    override fun push(frames: List<Frame>) = pushToClients(frames)
}
