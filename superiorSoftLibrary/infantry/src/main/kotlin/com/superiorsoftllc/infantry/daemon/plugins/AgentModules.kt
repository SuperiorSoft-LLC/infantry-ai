package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.agents.registry.AgentRegistry
import com.superiorsoftllc.infantry.plugin.api.AgentDefinitions
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.Resolution
import java.util.concurrent.ConcurrentHashMap

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.2): the `agents` block of every module the resolver let through, into the
 * daemon's ONE [AgentRegistry]. Data only, so a module needs no jar to contribute an agent. Default deny for community modules:
 * the `agents.contribute` grant, and ids under the module's own vendor prefix. One bad row refuses the module's whole block
 * (fail closed), never the daemon. [sync] is idempotent: a module no longer loaded loses its agents.
 */
class AgentModules(
    private val registry: AgentRegistry,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val statuses = ConcurrentHashMap<String, String>()
    private val held = ConcurrentHashMap.newKeySet<String>()

    /** `loaded: N agent(s) | refused: why` for a module with an `agents` block; null for any other module. */
    fun status(module: String): String? = statuses[module]

    fun sync(resolution: Resolution, config: ModulesConfig) {
        val wanted = resolution.loaded.filter { it.manifest?.agents?.isNotEmpty() == true }
        val names = wanted.map { it.name }.toSet()
        held.filter { it !in names }.forEach { registry.removeModule(it); held.remove(it); statuses[it] = "refused: no longer loaded" }
        for (s in wanted) {
            val m = s.manifest ?: continue
            val community = s.tier == Tier.COMMUNITY
            val why = when {
                community && GRANT !in config.setting(m.name).allow -> "community agents need the '$GRANT' grant in modules.json"
                m.agents.map { it.id }.toSet().size != m.agents.size -> "agents lists an id twice"
                else -> m.agents.firstNotNullOfOrNull { AgentDefinitions.refusal(it, m.name, community) }
            }
            if (why != null) {
                registry.removeModule(m.name); held.remove(m.name)
                statuses[m.name] = "refused: $why"
                log("[agents] agents of ${m.name} refused: $why")
                continue
            }
            registry.setModuleAgents(m.name, m.agents)
            held += m.name
            statuses[m.name] = "loaded: ${m.agents.size} agent(s)"
            log("[agents] agents of ${m.name} (${s.tier.name.lowercase()}): ${m.agents.map { it.id }}")
        }
    }

    companion object {
        /** A community module's `agents` block needs this grant. */
        const val GRANT = "agents.contribute"
    }
}
