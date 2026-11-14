// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.protocol.MCP_TARGET_INFANTRY
import com.superiorsoftllc.infantry.protocol.McpGrantView
import com.superiorsoftllc.infantry.protocol.McpPlanView

/** MCP-REGISTRY-1: who a session is, as the selection needs it. [remote] = it runs on a remote runner (no `localOnly` server). */
data class McpSessionContext(
    val harnessId: String,
    val projectId: String? = null,
    val role: String? = null,
    val agentId: String? = null,
    val ticketId: String? = null,
    val remote: Boolean = false,
)

/** MCP-REGISTRY-1: the plan and one reason per server name (what `mcp_plan` shows). */
data class McpSelectionResult(val plan: McpPlan, val reasons: Map<String, String>) {
    fun view(): McpPlanView = McpPlanView(plan.delivered.map { it.name }, plan.deny.sorted(), reasons)
}

/**
 * MCP-REGISTRY-1: which servers ONE session gets, a pure function of the registry's entries, the grants and the session.
 *
 * WALLED BY DEFAULT (owner 2026-11-14: "They should not be able to use them unless approved since they are costly"): the session
 * gets the daemon's own server, and any other server only when a grant names it for the session's role, agent or ticket (and the
 * grant's project, when it names one, is the session's). There is no "all" grant. Then:
 *  - a CLI's own server with no grant (or disabled, shadowed, or local-only on a remote runner) is DENIED, since the CLI would load
 *    it by itself; a granted one is left to the CLI when it loads the user's servers itself ([loadsUserMcp]), else delivered;
 *  - an Infantry-owned or plugin server is delivered only when granted, enabled, buildable (its secrets set) and, on a remote
 *    runner, not `localOnly`;
 *  - a shadowed server is never delivered (the winner of its name is judged instead).
 */
object McpSelection {
    fun select(
        entries: List<McpEntry>,
        grants: List<McpGrantView>,
        ctx: McpSessionContext,
        infantry: McpServerSpec?,
        loadsUserMcp: Boolean,
    ): McpSelectionResult {
        val add = mutableListOf<McpServerSpec>()
        val deny = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()
        infantry?.let { add += it; reasons[it.name] = "the daemon's own server, always delivered" }
        val mine = entries.filter { it.source != McpSource.INFANTRY && (it.target == MCP_TARGET_INFANTRY || it.target == ctx.harnessId) }
        val cliOwn = mutableListOf<McpEntry>() // the CLI's own servers this session must not get
        for (e in mine) {
            val grant = grants.firstOrNull { it.server == e.name && matches(it, ctx) }
            val why: String? = when {
                e.shadowedBy != null -> "shadowed by the ${e.shadowedBy.name.lowercase()} server of the same name"
                grant == null -> "walled: no grant for this ${grantTargets(ctx)}"
                !e.enabled -> "disabled"
                ctx.remote && e.localOnly -> "local only: never sent to a remote runner"
                e.spec == null -> "cannot be built: ${e.problem ?: "no spec"}"
                else -> null
            }
            if (why != null) {
                if (e.source == McpSource.HARNESS) cliOwn += e
                reasons.putIfAbsent(e.name, why)
                continue
            }
            if (e.source == McpSource.HARNESS && loadsUserMcp) {
                reasons[e.name] = granted(grant!!) + "; the CLI loads it itself"
                continue
            }
            add += e.spec!!
            reasons[e.name] = granted(grant!!)
        }
        val added = add.map { it.name }.toSet()
        // Deny a CLI's own server unless a delivered server of the same name would be denied with it (deny wins in McpPlan).
        cliOwn.filter { it.name !in added }.forEach { deny += it.name }
        return McpSelectionResult(McpPlan(add, deny), reasons)
    }

    /** [grant] applies to [ctx]: its project (when it names one) is the session's, and its target is the session's role, agent or ticket. */
    fun matches(grant: McpGrantView, ctx: McpSessionContext): Boolean {
        if (grant.projectId != null && grant.projectId != ctx.projectId) return false
        val want = when (grant.scope) {
            McpGrantScope.ROLE.wire -> ctx.role
            McpGrantScope.AGENT.wire -> ctx.agentId
            McpGrantScope.TICKET.wire -> ctx.ticketId
            else -> null
        }
        return want != null && want == grant.target
    }

    private fun granted(g: McpGrantView) = "granted to ${g.scope} ${g.target} (${g.id}, by ${g.approvedBy}): ${g.purpose}"

    private fun grantTargets(ctx: McpSessionContext) = listOfNotNull(
        ctx.role?.let { "role $it" }, ctx.agentId?.let { "agent $it" }, ctx.ticketId?.let { "ticket $it" },
    ).ifEmpty { listOf("session") }.joinToString(" / ")
}
