package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.plugin.api.AgentDefinition
import com.superiorsoftllc.agenthq.plugin.api.AgentUiHints

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 4): the role catalog as agent-registry contributions. Each role is one agent
 * whose instructions are its role's persona, referenced (not copied), so a role edit reaches the next session exactly as it did
 * before the registry. The registry never imports this package; the dependency points from roles to the registry.
 */
object RoleAgents {
    fun of(role: Role): AgentDefinition = AgentDefinition(
        id = role.id,
        name = role.name,
        description = role.description,
        role = role.id,
        ui = AgentUiHints(color = role.color.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }, icon = role.icon.ifBlank { null }),
    )

    fun ofRecord(role: com.superiorsoftllc.agenthq.daemon.api.roles.RoleRecord): AgentDefinition = AgentDefinition(
        id = role.id,
        name = role.name,
        description = role.description,
        role = role.id,
        ui = AgentUiHints(color = role.color.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }, icon = role.icon.ifBlank { null }),
    )

    fun allRecords(roles: List<com.superiorsoftllc.agenthq.daemon.api.roles.RoleRecord>): List<AgentDefinition> = roles.map(::ofRecord)

    fun all(roles: List<Role>): List<AgentDefinition> = roles.map(::of)
}
