// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpGrantView
import kotlinx.serialization.Serializable

/**
 * MCP-SELECT-1: the `mcp` block a role, a ticket (`require`) or an agent profile carries in its own data: the servers it needs and why.
 * It is only a request until [McpDeclarations.declare] approves it; then it is nothing but registry grants.
 */
@Serializable
data class McpBlock(val servers: List<String> = emptyList(), val purpose: String = "")

/** MCP-SELECT-1: who asks for a declaration. [session] = a worker session (its role, agent and ticket); null = the owner, a client or the PM. */
data class McpDeclarer(val label: String, val session: McpSessionContext? = null)

/**
 * MCP-SELECT-1: role / ticket / agent `mcp` blocks resolve into the registry's grants (one source of truth, [McpGrants]); the
 * selection reads only grants. A declaration REPLACES the grants of its scope + target (and project), so a smaller block narrows
 * them and an empty one removes them (walled again). The approval gate: a worker session cannot declare for its own role, agent or
 * ticket; every grant carries the declarer's label as approver and the block's purpose.
 */
class McpDeclarations(private val grants: McpGrants) {
    fun declare(scope: McpGrantScope, target: String, projectId: String?, block: McpBlock, by: McpDeclarer): List<McpGrantView> {
        val t = target.trim()
        require(t.isNotEmpty()) { "an mcp block needs the ${scope.wire} it is for" }
        val own = by.session
        if (own != null) {
            val mine = when (scope) { McpGrantScope.ROLE -> own.role; McpGrantScope.AGENT -> own.agentId; McpGrantScope.TICKET -> own.ticketId }
            require(mine != t) {
                "a worker cannot grant MCP servers to its own ${scope.wire} '$t' (walled by default: the owner approves them)"
            }
        }
        val servers = block.servers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(servers.isEmpty() || block.purpose.isNotBlank()) { "an mcp block needs a purpose: why this ${scope.wire} needs ${servers.joinToString()}" }
        val pid = projectId?.trim()?.takeIf { it.isNotEmpty() }
        grants.list()
            .filter { it.scope == scope.wire && it.target == t && it.projectId == pid && it.server !in servers }
            .forEach { grants.revoke(it.id) }
        return servers.map { grants.grant(McpGrantInput(it, scope.wire, t, pid, block.purpose, by.label)) }
    }

    /** The servers granted to [scope] [target] now (what a screen or a ticket brief shows). */
    fun declared(scope: McpGrantScope, target: String, projectId: String? = null): List<McpGrantView> =
        grants.list().filter { it.scope == scope.wire && it.target == target && it.projectId == projectId?.trim()?.takeIf { p -> p.isNotEmpty() } }
}
