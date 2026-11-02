package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.registry.AgentRegistry
import com.superiorsoftllc.agenthq.daemon.AssistantService
import com.superiorsoftllc.agenthq.daemon.plugins.AgentModules
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.daemon.RolesLookup
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.stateDir

/** Roles, agent registry and agent profiles wiring (CORE-SPLIT-4a2). Moved verbatim from DaemonMain, same construction order. */
object AgentsWiring {
    /** What [build] builds. */
    class Built(val registry: AgentRegistry, val modules: AgentModules, val profiles: AgentProfiles)

    fun build(
        store: AgentHqStore, manager: SessionManager, roles: RolesLookup,
        /** AR-4b-ii-a: bindings and trait lists are read through their ports (looked up per call); absent = none. */
        bindings: com.superiorsoftllc.agenthq.daemon.RoleLists = com.superiorsoftllc.agenthq.daemon.RoleLists({ null }, { null }),
    ): Built {
        // AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md): the ONE agent registry. The role catalog is its first contributor (each role
        // one agent), modules' `agents` blocks are synced in later; with no user override every agent is enabled, as before.
        val agentRegistry = AgentRegistry(
            roleAgents = { com.superiorsoftllc.agenthq.role.RoleAgents.allRecords(roles.all()) },
            rolePersona = { id -> roles.role(id)?.persona },
            // Owner O5: the interim override store; the unified config JSON replaces it here (AgentOverrides is the swap point).
            overrides = com.superiorsoftllc.agenthq.agents.registry.FileAgentOverrides.inStateDir(stateDir()),
            // Owner O4: an agent's `profile` names an agent profile, a behaviour preset (instructions, provider, model).
            profile = { id -> store.agents.get(id)?.let { p -> com.superiorsoftllc.agenthq.plugin.api.AgentDefinition(id = p.role, instructions = p.instructions.orEmpty(), harness = p.provider, model = p.model) } },
        )
        val agentModules = AgentModules(agentRegistry)
        // Contract 1.4.0: profiles are validated against the providers [manager] was configured with.
        val agents = AgentProfiles(
            // AGENT-REGISTRY-1: a module's agent is a role id a session may start as (additive: catalog ids resolve as before).
            store.agents, manager, roleBindings = bindings::binding, roleExists = { roles.role(it) != null || agentRegistry.contains(it) },
            traitExists = { store.traits.get(it) != null }, roleTraits = bindings::traits, trait = store.traits::get,
            requireKnownSkills = { ids -> com.superiorsoftllc.agenthq.settings.requireKnownSkills(store, ids, "skillIds") },
            // ROLE-EDIT-1: a role's (possibly edited) instructions reach every session started for it after the save.
            rolePersona = { id -> (roles.role(id)?.persona ?: agentRegistry.instructionsOf(id)).let { p -> if (id == AssistantService.ROLE) AssistantService.instructions(p) else p } },
        )
        return Built(agentRegistry, agentModules, agents)
    }
}
