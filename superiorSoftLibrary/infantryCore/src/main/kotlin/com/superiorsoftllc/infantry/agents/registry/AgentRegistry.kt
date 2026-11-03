package com.superiorsoftllc.infantry.agents.registry

import com.superiorsoftllc.infantry.daemon.api.agents.AgentEntry
import com.superiorsoftllc.infantry.plugin.api.AgentDefinition
import com.superiorsoftllc.infantry.plugin.api.AgentDefinitions

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3): the daemon's ONE agent registry, a platform-core mechanism like the
 * harness registry. It holds definitions only; it spawns nothing, and it names no ticket, board, worktree or orchestrator
 * (AgentRegistryLayerGuardTest). Three contributors, in this precedence (the first holder of an id keeps it):
 *
 * 1. the role catalog ([roleAgents], read live): today's built-in and custom roles, each one agent whose persona is its role's;
 * 2. module.json `agents` blocks ([setModuleAgents], from [com.superiorsoftllc.infantry.daemon.plugins.AgentModules]);
 * 3. plugin code ([contribute], DaemonApi `agents.contribute`): may only (re)define an id its own module declared.
 *
 * An agent may name a [profile] (owner O4: a behaviour preset); its own fields win, its blank ones take the profile's.
 * Over them sits ONE user override layer, [overrides] (enabled / disabled per id). Every agent is enabled until the user disables
 * it, so a daemon with no override behaves exactly as before the registry existed.
 */
class AgentRegistry(
    /** The role catalog's agents, in catalog order; read on every call so a role edit shows at once. */
    private val roleAgents: () -> List<AgentDefinition> = { emptyList() },
    /** A role's persona (system prompt), for an agent that names a role and has no instructions of its own. */
    private val rolePersona: (String) -> String? = { null },
    /** The user's override layer (owner O5: interim file store; the unified config JSON replaces it behind this interface). */
    private val overrides: AgentOverrides = MemoryAgentOverrides(),
    /** A behaviour preset (agent profile) by id, as a partial definition; null = unknown (the agent then uses its own fields only). */
    private val profile: (String) -> AgentDefinition? = { null },
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private class Contribution(val module: String, val source: String, val definition: AgentDefinition)

    private val lock = Any()
    /** module -> its manifest agents, by id. */
    private val manifest = LinkedHashMap<String, Map<String, AgentDefinition>>()
    /** module -> its code-contributed agents, by id (each replaces the manifest entry of the same id). */
    private val service = LinkedHashMap<String, LinkedHashMap<String, AgentDefinition>>()

    /** Every agent, enabled or not: the role catalog's first (catalog order), then the modules' sorted by id. */
    fun list(): List<AgentEntry> {
        val roles = roleAgents()
        val taken = roles.map { it.id }.toMutableSet()
        val fromModules = synchronized(lock) { moduleContributions() }
            .filter { c -> taken.add(c.definition.id).also { if (!it) log("[agents] ${c.module}'s agent '${c.definition.id}' is shadowed by an earlier contributor") } }
            .sortedBy { it.definition.id }
        val enabledNow = overrides.disabled()
        return roles.map { entry(it, ROLES, ROLES, enabledNow) } + fromModules.map { entry(it.definition, it.module, it.source, enabledNow) }
    }

    fun get(id: String): AgentEntry? = list().firstOrNull { it.definition.id == id }

    /** True when [id] is an agent the registry holds (enabled or not). */
    fun contains(id: String): Boolean = get(id) != null

    /** The instructions a NEW session of [id] gets: its own, else its role's persona; null = none or unknown. */
    fun instructionsOf(id: String): String? = get(id)?.effectiveInstructions?.takeIf { it.isNotBlank() }

    /** Why a new session of [id] may not start, or null. Only a disabled agent is refused; an id the registry lacks passes. */
    fun spawnRefusal(id: String?): String? {
        val agent = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (agent in overrides.disabled() && contains(agent)) "agent '$agent' is disabled (infantry agent-def enable $agent)" else null
    }

    /** The user's override: disable or re-enable [id]. Null when no agent has that id. */
    fun setEnabled(id: String, enabled: Boolean): AgentEntry? {
        if (!contains(id)) return null
        overrides.setDisabled(id, !enabled)
        log("[agents] agent '$id' ${if (enabled) "enabled" else "disabled"}")
        return get(id)
    }

    /** Replace [module]'s manifest agents with [definitions] (empty = remove them). Invalid rows are the caller's to refuse. */
    fun setModuleAgents(module: String, definitions: List<AgentDefinition>) = synchronized(lock) {
        if (definitions.isEmpty()) manifest.remove(module) else manifest[module] = definitions.associateBy { it.id }
    }

    /** The ids [module]'s manifest declares. */
    fun declaredBy(module: String): Set<String> = synchronized(lock) { manifest[module]?.keys.orEmpty() }

    /** Drop everything [module] contributed (it unloaded or was disabled). */
    fun removeModule(module: String) = synchronized(lock) {
        manifest.remove(module)
        service.remove(module)
    }

    /**
     * [module]'s code contributes [definition], for as long as the handle stays open. Its id must be one the module's manifest
     * declares (declarations are the contract) and it must pass [AgentDefinitions.refusal].
     */
    fun contribute(module: String, community: Boolean, definition: AgentDefinition): AutoCloseable {
        check(definition.id in declaredBy(module)) { "agent '${definition.id}' is not declared in $module's module.json agents" }
        AgentDefinitions.refusal(definition, module, community)?.let { error(it) }
        synchronized(lock) { service.getOrPut(module) { LinkedHashMap() }[definition.id] = definition }
        return AutoCloseable { synchronized(lock) { service[module]?.let { m -> if (m[definition.id] === definition) m.remove(definition.id) } } }
    }

    private fun moduleContributions(): List<Contribution> = manifest.flatMap { (module, defs) ->
        defs.values.map { d -> service[module]?.get(d.id)?.let { Contribution(module, SERVICE, it) } ?: Contribution(module, MANIFEST, d) }
    }

    private fun entry(declared: AgentDefinition, contributor: String, source: String, disabledNow: Set<String>): AgentEntry {
        val d = AgentDefinitions.withProfile(declared, declared.profile?.let { runCatching { profile(it) }.getOrNull() })
        val instructions = d.instructions.ifBlank { d.role?.let(rolePersona).orEmpty() }
        return AgentEntry(d.copy(name = d.name.ifBlank { d.id }), contributor, source, enabled = d.id !in disabledNow, effectiveInstructions = instructions)
    }

    companion object {
        /** The role catalog's contributor and source name. */
        const val ROLES = "roles"
        const val MANIFEST = "manifest"
        const val SERVICE = "service"
    }
}
