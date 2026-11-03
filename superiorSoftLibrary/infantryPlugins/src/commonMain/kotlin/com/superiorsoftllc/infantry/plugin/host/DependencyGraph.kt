// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.DependencyDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier

/** PLUGINS-2: one installed module as the dependency rules see it. */
data class ModuleNode(val name: String, val tier: Tier, val manifest: ModuleManifest, val enabled: Boolean)

/** PLUGINS-2: the outcome of asking to enable, disable or remove a module. */
sealed interface ModuleChange {
    val message: String

    /** Allowed: [config] is modules.json after the change; [changed] the modules it switched, in the order it did. */
    data class Applied(val config: ModulesConfig, val changed: List<String>, override val message: String, val log: List<String> = emptyList()) : ModuleChange

    /** Refused, with a plain [message]; [mustEnableFirst] in enable order, or the enabled [dependents] that block it. */
    data class Refused(override val message: String, val mustEnableFirst: List<String> = emptyList(), val dependents: List<String> = emptyList()) : ModuleChange
}

/**
 * PLUGINS-2: the dependency rules, pure, shared by the loader ([ModuleResolver]) and by Settings when it changes
 * modules.json:
 * - a dependency must be installed, enabled and inside its version range;
 * - a core module may depend on core modules only;
 * - no cycles;
 * - enabling a module whose dependencies are not all enabled is refused, naming what to enable first, in the one
 *   deterministic order (dependencies before dependents, then by name);
 * - disabling or removing a module that enabled modules depend on is refused, naming them, unless cascade is asked,
 *   which disables them too (dependents first) and logs each one.
 */
class DependencyGraph(nodes: List<ModuleNode>) {
    private val byName: Map<String, ModuleNode> = nodes.groupBy { it.name }.mapValues { it.value.first() }

    fun node(name: String): ModuleNode? = byName[name]

    /** What [name] declares it needs. */
    fun needs(name: String): List<DependencyDeclaration> = byName[name]?.manifest?.dependencies.orEmpty()

    /** The enabled modules that list [name] as a dependency, by name. */
    fun requiredBy(name: String): List<String> =
        byName.values.filter { n -> n.enabled && n.name != name && n.manifest.dependencies.any { it.id == name } }.map { it.name }.sorted()

    fun enable(name: String, config: ModulesConfig): ModuleChange {
        val target = byName[name] ?: return ModuleChange.Refused("Cannot enable $name: no module of that name is installed.")
        val problems = mutableListOf<String>()
        val order = mutableListOf<String>()
        val done = HashSet<String>()
        // Depth first, dependencies sorted by name, each module placed after everything it needs: a topological order
        // that is the same on every run.
        fun visit(n: ModuleNode, path: List<String>) {
            if (n.name in done) return
            for (d in n.manifest.dependencies.sortedBy { it.id }) {
                val dep = byName[d.id]
                if (d.id in path) { problems += "dependency cycle: ${(path.dropWhile { it != d.id } + d.id).joinToString(" -> ")}"; continue }
                val problem = edgeProblem(n.name, n.tier, d, dep?.tier, dep?.manifest)
                if (problem != null || dep == null) { problems += problem.orEmpty(); continue }
                visit(dep, path + dep.name)
            }
            done += n.name
            if (!n.enabled && n.name != name) order += n.name
        }
        visit(target, listOf(name))
        if (problems.isNotEmpty()) return ModuleChange.Refused("Cannot enable $name: ${problems.distinct().joinToString("; ")}.", order)
        if (order.isNotEmpty()) {
            val why = order.joinToString(", ") { "$it (${if (byName.getValue(it).tier == Tier.COMMUNITY && config.modules[it]?.enabled == null) "off until enabled" else "disabled"})" }
            return ModuleChange.Refused("Cannot enable $name: it needs modules that are not enabled. Enable these first, in this order: $why.", order)
        }
        if (target.enabled) return ModuleChange.Applied(config, emptyList(), "$name is already enabled.")
        return ModuleChange.Applied(config.with(name, true), listOf(name), "$name enabled. Restart to load it.")
    }

    fun disable(name: String, config: ModulesConfig, cascade: Boolean = false): ModuleChange =
        reverse(name, config, cascade, "disable") { c -> c.with(name, false) }

    /** The modules.json side of removing [name]'s folder: its entry goes; the caller deletes the folder. */
    fun remove(name: String, config: ModulesConfig, cascade: Boolean = false): ModuleChange =
        reverse(name, config, cascade, "remove") { c -> c.copy(modules = c.modules - name) }

    private fun reverse(name: String, config: ModulesConfig, cascade: Boolean, verb: String, change: (ModulesConfig) -> ModulesConfig): ModuleChange {
        if (byName[name] == null) return ModuleChange.Refused("Cannot $verb $name: no module of that name is installed.")
        val dependents = dependentsInDisableOrder(name)
        if (dependents.isNotEmpty() && !cascade) {
            return ModuleChange.Refused(
                "Cannot $verb $name: enabled modules depend on it: ${dependents.joinToString(", ")}. Disable them first, or choose cascade to disable them too.",
                dependents = dependents,
            )
        }
        val log = dependents.map { "[plugins] cascade: disabled $it because it depends on $name, which is being ${if (verb == "remove") "removed" else "disabled"}" }
        val after = change(dependents.fold(config) { c, d -> c.with(d, false) })
        val done = if (verb == "remove") "$name can be removed" else if (byName.getValue(name).enabled) "$name disabled" else "$name is already disabled"
        val message = done + (if (dependents.isEmpty()) "" else "; cascade also disabled ${dependents.joinToString(", ")}") + ". Restart to apply."
        return ModuleChange.Applied(after, dependents + name, message, log)
    }

    /** Every enabled module that needs [name], directly or not: the furthest first, then by name. */
    private fun dependentsInDisableOrder(name: String): List<String> {
        val depth = HashMap<String, Int>()
        fun walk(n: String, d: Int, path: Set<String>) {
            for (m in requiredBy(n)) {
                if (m in path) continue
                if ((depth[m] ?: -1) < d) { depth[m] = d; walk(m, d + 1, path + m) }
            }
        }
        walk(name, 0, setOf(name))
        return depth.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }

    companion object {
        /** The graph of what is installed, enabled as modules.json (and each tier's default) says. */
        fun of(candidates: List<ModuleCandidate>, config: ModulesConfig): DependencyGraph = DependencyGraph(
            candidates.mapNotNull { c ->
                c.manifest?.let { m -> ModuleNode(m.name, c.tier, m, config.setting(m.name).enabled ?: (c.tier != Tier.COMMUNITY)) }
            },
        )

        /** The graph of a finished load: a module counts as enabled unless it was off in modules.json. */
        fun of(statuses: List<ModuleStatus>): DependencyGraph = DependencyGraph(
            statuses.mapNotNull { s -> s.manifest?.let { ModuleNode(s.name, s.tier, it, s.state != ModuleState.DISABLED) } },
        )

        /**
         * What is wrong with module [from] (of tier [fromTier]) depending on [dep], given what is installed under that
         * name ([targetManifest] null = nothing); null when the dependency is satisfied apart from being enabled.
         */
        fun edgeProblem(from: String, fromTier: Tier, dep: DependencyDeclaration, targetTier: Tier?, targetManifest: ModuleManifest?): String? {
            val range = VersionRange.parseOrNull(dep.version) ?: return "$from declares an invalid version range '${dep.version}' for ${dep.id}"
            if (targetManifest == null || targetTier == null) return "$from needs ${label(dep)}, which is not installed"
            if (targetTier.ordinal > fromTier.ordinal) return "${fromTier.name.lowercase()} module $from cannot depend on ${targetTier.name.lowercase()} module ${dep.id}"
            val v = SemVer.parseOrNull(targetManifest.version) ?: return "$from needs ${label(dep)}, but ${dep.id} has version '${targetManifest.version}', which is not a version"
            if (!range.contains(v)) return "$from needs ${label(dep)}, but ${dep.id} is $v"
            return null
        }

        /** `Acme_Base >=1.2 <2`, or just `Acme_Base` for any version. */
        fun label(dep: DependencyDeclaration): String = if (dep.version.isBlank() || dep.version.trim() == "*") dep.id else "${dep.id} ${dep.version.trim()}"

        private fun ModulesConfig.with(name: String, enabled: Boolean): ModulesConfig =
            copy(modules = modules + (name to (modules[name] ?: ModuleSetting()).copy(enabled = enabled)))
    }
}
