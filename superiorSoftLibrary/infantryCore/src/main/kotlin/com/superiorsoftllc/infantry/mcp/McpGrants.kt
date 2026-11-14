// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpGrantView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** MCP-REGISTRY-1: what a grant is given to. */
enum class McpGrantScope(val wire: String) {
    ROLE("role"), AGENT("agent"), TICKET("ticket");

    companion object {
        fun parse(text: String?): McpGrantScope = entries.firstOrNull { it.wire == text?.trim()?.lowercase() }
            ?: throw IllegalArgumentException("unknown grant scope '${text.orEmpty()}' (one of role, agent, ticket)")
    }
}

/**
 * MCP-REGISTRY-1: the grants store. User MCP servers are walled: an agent gets one only through a grant that names the server, a scope
 * (role / agent / ticket) and its target, a purpose, who approved it and when. There is no "all" grant. Stored in [storage]
 * (document `grants`); mechanism only, it names no server.
 */
class McpGrants(
    private val storage: McpStorage,
    /** The most grants kept at once (`limits.mcp.grantsMax`). */
    private val max: () -> Int,
    /** The longest purpose text (`limits.mcp.purposeChars`). */
    private val purposeChars: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "mcpg-" + UUID.randomUUID().toString().take(8) },
) {
    private val lock = Any()

    fun list(): List<McpGrantView> = synchronized(lock) { load() }

    /** Record [input] as a grant; the same server, scope, target and project again replaces the old grant (new purpose and approver). */
    fun grant(input: McpGrantInput): McpGrantView = synchronized(lock) {
        val scope = McpGrantScope.parse(input.scope)
        val server = input.server.trim()
        val target = input.target.trim()
        val purpose = input.purpose.trim()
        val approvedBy = input.approvedBy.trim()
        require(McpOwnedServers.NAME.matches(server)) { "grant needs a server name (letters, digits, '_' or '-')" }
        require(target.isNotEmpty()) { "grant needs the ${scope.wire} it is for" }
        require(purpose.isNotEmpty()) { "grant needs a purpose: why this ${scope.wire} needs '$server'" }
        require(purpose.length <= purposeChars()) { "purpose is ${purpose.length} chars; the most is ${purposeChars()} (setting 'limits.mcp.purposeChars')" }
        require(approvedBy.isNotEmpty()) { "grant needs who approved it" }
        val projectId = input.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val all = load()
        val rest = all.filterNot { it.server == server && it.scope == scope.wire && it.target == target && it.projectId == projectId }
        require(rest.size < max()) { "${rest.size} MCP grants are kept already (setting 'limits.mcp.grantsMax'); revoke one first" }
        val view = McpGrantView(newId(), server, scope.wire, target, projectId, purpose, approvedBy, clock())
        save(rest + view)
        view
    }

    /** Remove grant [id]; false when there is none. */
    fun revoke(id: String): Boolean = synchronized(lock) {
        val all = load()
        if (all.none { it.id == id }) return false
        save(all.filter { it.id != id })
        true
    }

    private fun load(): List<McpGrantView> =
        storage.read(DOC)?.let { runCatching { JSON.decodeFromString(LIST, it) }.getOrNull() } ?: emptyList()

    private fun save(list: List<McpGrantView>) = storage.write(DOC, JSON.encodeToString(LIST, list))

    private companion object {
        const val DOC = "grants"
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val LIST = ListSerializer(McpGrantView.serializer())
    }
}
