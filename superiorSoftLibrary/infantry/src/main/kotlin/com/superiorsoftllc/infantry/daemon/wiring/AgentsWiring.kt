package com.superiorsoftllc.infantry.daemon.wiring

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.agents.registry.AgentRegistry
import com.superiorsoftllc.infantry.daemon.AssistantService
import com.superiorsoftllc.infantry.daemon.plugins.AgentModules
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.daemon.RolesLookup
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.stateDir

/** Roles, agent registry and agent profiles wiring (CORE-SPLIT-4a2). Moved verbatim from DaemonMain, same construction order. */
object AgentsWiring {
    /** What [build] builds. */
    class Built(val registry: AgentRegistry, val modules: AgentModules, val profiles: AgentProfiles)

    fun build(
        store: InfantryStore, manager: SessionManager, roles: RolesLookup,
        /** AR-4b-ii-a: bindings and trait lists are read through their ports (looked up per call); absent = none. */
        bindings: com.superiorsoftllc.infantry.daemon.RoleLists = com.superiorsoftllc.infantry.daemon.RoleLists({ null }, { null }),
    ): Built {
        // AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md): the ONE agent registry. The role catalog is its first contributor (each role
        // one agent), modules' `agents` blocks are synced in later; with no user override every agent is enabled, as before.
        val agentRegistry = AgentRegistry(
            roleAgents = { RoleAgents.allRecords(roles.all()) },
            rolePersona = { id -> roles.role(id)?.persona },
            // Owner O5: the interim override store; the unified config JSON replaces it here (AgentOverrides is the swap point).
            overrides = com.superiorsoftllc.infantry.agents.registry.FileAgentOverrides.inStateDir(stateDir()),
            // Owner O4: an agent's `profile` names an agent profile, a behaviour preset (instructions, provider, model).
            profile = { id -> store.agents.get(id)?.let { p -> com.superiorsoftllc.infantry.plugin.api.AgentDefinition(id = p.role, instructions = p.instructions.orEmpty(), harness = p.provider, model = p.model) } },
        )
        val agentModules = AgentModules(agentRegistry)
        // Contract 1.4.0: profiles are validated against the providers [manager] was configured with.
        val agents = AgentProfiles(
            // AGENT-REGISTRY-1: a module's agent is a role id a session may start as (additive: catalog ids resolve as before).
            store.agents, manager, roleBindings = bindings::binding, roleExists = { roles.role(it) != null || agentRegistry.contains(it) },
            traitExists = { store.traits.get(it) != null }, roleTraits = bindings::traits, trait = store.traits::get,
            requireKnownSkills = { ids -> com.superiorsoftllc.infantry.settings.requireKnownSkills(store, ids, "skillIds") },
            // ROLE-EDIT-1: a role's (possibly edited) instructions reach every session started for it after the save.
            rolePersona = { id -> (roles.role(id)?.persona ?: agentRegistry.instructionsOf(id)).let { p -> if (id == AssistantService.ROLE) AssistantService.instructions(p) else p } },
        )
        return Built(agentRegistry, agentModules, agents)
    }
}

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 4), AR-4b-ii-b: the role catalog as agent-registry contributions, the Roles-to-agents
 * seam. Each role is one agent whose instructions are its role's persona, referenced (not copied), so a role edit reaches the next
 * session exactly as before. Lives in core because the Infantry_Roles plugin cannot compile against the plugin SDK's AgentDefinition.
 */
object RoleAgents {
    private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

    fun ofRecord(role: com.superiorsoftllc.infantry.daemon.api.roles.RoleRecord): com.superiorsoftllc.infantry.plugin.api.AgentDefinition =
        com.superiorsoftllc.infantry.plugin.api.AgentDefinition(
            id = role.id,
            name = role.name,
            description = role.description,
            role = role.id,
            ui = com.superiorsoftllc.infantry.plugin.api.AgentUiHints(color = role.color.takeIf { HEX_COLOR.matches(it) }, icon = role.icon.ifBlank { null }),
        )

    fun allRecords(roles: List<com.superiorsoftllc.infantry.daemon.api.roles.RoleRecord>): List<com.superiorsoftllc.infantry.plugin.api.AgentDefinition> =
        roles.map(::ofRecord)
}
