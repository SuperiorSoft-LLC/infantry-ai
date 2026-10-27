package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.PLUGIN_API_VERSION
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlinx.serialization.json.Json

/** PLUGINS-1: the one JSON reader of module.json and modules.json (unknown keys ignored, so newer files still parse). */
val PluginJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

/** A module found in a plugin folder, or compiled into the app ([dir] null). [manifest] null = [parseError] says why. */
data class ModuleCandidate(
    val folderName: String,
    val tier: Tier,
    val dir: String?,
    val manifest: ModuleManifest?,
    val parseError: String? = null,
)

enum class ModuleState { LOADED, DISABLED, LOCKED, REFUSED }

/** What became of one module; [reason] says why it is not loaded (or a note about a loaded one). */
data class ModuleStatus(
    val name: String,
    val tier: Tier,
    val state: ModuleState,
    val reason: String? = null,
    val manifest: ModuleManifest? = null,
    val dir: String? = null,
) {
    val logLine: String get() = "[plugins] $name (${tier.name.lowercase()}): ${state.name.lowercase()}" + (reason?.let { " - $it" } ?: "")
}

/** A preference the rules let through: [module] replaces [replace] with its own [with]. */
data class AcceptedPreference(val module: String, val replace: String, val with: String)

/**
 * The outcome of one load: every module's status (in load order for the loaded ones, then the rest), the preferences
 * that apply, and the log lines (one per module, plus one per ignored claim or refused preference).
 */
data class Resolution(
    val statuses: List<ModuleStatus>,
    val preferences: List<AcceptedPreference>,
    val log: List<String>,
) {
    val loaded: List<ModuleStatus> get() = statuses.filter { it.state == ModuleState.LOADED }
    fun status(name: String): ModuleStatus? = statuses.firstOrNull { it.name == name }

    /** The same resolution with [name] refused for [reason] (a loader step after the rules, e.g. its jar would not load). */
    fun refuse(name: String, reason: String): Resolution {
        val s = status(name) ?: return this
        val refused = s.copy(state = ModuleState.REFUSED, reason = reason)
        return copy(statuses = statuses.map { if (it.name == name) refused else it }, log = log + refused.logLine)
    }
}

/**
 * PLUGINS-1: the loading rules, as a pure function so every rule is tested without a file system:
 * - a module that does not parse, is misnamed, sits in a folder of another name, or repeats a name is refused;
 * - the folder decides the tier; a tier claimed in module.json is logged and ignored;
 * - a community module needs an author and a license, and stays off until modules.json enables it;
 * - under the demo lock no community module loads;
 * - load order puts each module after its `dependencies` and its `sequence` (order only: an absent name is ignored);
 * - PLUGINS-2: a dependency that is missing, off, refused or outside its version range refuses the module, and so on
 *   down the chain; a cycle refuses the modules on it; a core module may not depend on a community one (core never
 *   imports a module). The rules are [DependencyGraph]'s, the same ones Settings applies before it changes modules.json;
 * - a community module may not replace a core contribution unless modules.json allows that override.
 */
object ModuleResolver {
    private val NAME = Regex("^[A-Z][A-Za-z0-9]*_[A-Z][A-Za-z0-9]*$")

    fun resolve(
        candidates: List<ModuleCandidate>,
        config: ModulesConfig = ModulesConfig(),
        demoLock: Boolean = false,
        apiVersion: Int = PLUGIN_API_VERSION,
    ): Resolution {
        val log = mutableListOf<String>()
        val final = LinkedHashMap<String, ModuleStatus>()
        val eligible = mutableListOf<ModuleCandidate>()
        val seen = HashMap<String, ModuleCandidate>()

        for (c in candidates) {
            val m = c.manifest
            val name = m?.name ?: c.folderName
            fun refuse(reason: String) {
                // A repeat must not overwrite the status of the module that already holds the name.
                val key = if (name in final || name in seen) "${c.tier.name.lowercase()}/${c.folderName}" else name
                final[key] = ModuleStatus(name, c.tier, ModuleState.REFUSED, reason, m, c.dir)
            }
            when {
                m == null -> refuse("invalid module.json: ${c.parseError ?: "unreadable"}")
                !NAME.matches(m.name) -> refuse("name '${m.name}' is not Vendor_Module")
                c.dir != null && c.folderName != m.name -> refuse("folder '${c.folderName}' declares '${m.name}'; they must match")
                m.name in seen -> refuse("'${m.name}' is already provided by ${seen.getValue(m.name).tier.name.lowercase()}/${seen.getValue(m.name).folderName}")
                m.apiVersion != apiVersion -> refuse("needs plugin API ${m.apiVersion}, this build has $apiVersion")
                c.tier == Tier.COMMUNITY && m.author.isNullOrBlank() -> refuse("community module without an author")
                c.tier == Tier.COMMUNITY && m.license.isNullOrBlank() -> refuse("community module without a license")
                else -> {
                    seen[m.name] = c
                    m.tier?.let { claimed ->
                        if (!claimed.equals(c.tier.name, ignoreCase = true)) {
                            log += "[plugins] ${m.name}: claims tier '$claimed', ignored; the folder makes it ${c.tier.name.lowercase()}"
                        }
                    }
                    val setting = config.setting(m.name)
                    val on = setting.enabled ?: (c.tier == Tier.CORE)
                    when {
                        !on -> final[m.name] = ModuleStatus(
                            m.name, c.tier, ModuleState.DISABLED,
                            if (c.tier == Tier.COMMUNITY && setting.enabled == null) "community, off until enabled in modules.json" else "disabled in modules.json",
                            m, c.dir,
                        )
                        demoLock && c.tier == Tier.COMMUNITY ->
                            final[m.name] = ModuleStatus(m.name, c.tier, ModuleState.LOCKED, "community modules do not load under the demo lock", m, c.dir)
                        else -> eligible += c
                    }
                }
            }
        }

        // PLUGINS-2 dependencies: drop, until nothing changes, every module with a dependency that will not load, is
        // out of its version range, or is community under a core module.
        val byName = eligible.associateBy { it.manifest!!.name }.toMutableMap()
        val installed = candidates.filter { it.manifest != null }.associateBy { it.manifest!!.name }
        var changed = true
        while (changed) {
            changed = false
            for (c in byName.values.toList()) {
                val m = c.manifest!!
                val reason = m.dependencies.firstNotNullOfOrNull { dep ->
                    val target = byName[dep.id]
                    val known = target ?: installed[dep.id]
                    val problem = DependencyGraph.edgeProblem(m.name, c.tier, dep, known?.tier, known?.manifest)
                    when {
                        known == null && VersionRange.parseOrNull(dep.version) != null ->
                            final[dep.id]?.let { "dependency ${dep.id} is ${it.state.name.lowercase()}" } ?: "missing dependency ${DependencyGraph.label(dep)}"
                        problem != null -> problem
                        target == null -> final[dep.id]?.let { "dependency ${dep.id} is ${it.state.name.lowercase()}" }
                            ?: "dependency ${dep.id} is not loading"
                        else -> null
                    }
                }
                if (reason != null) {
                    byName.remove(m.name)
                    final[m.name] = ModuleStatus(m.name, c.tier, ModuleState.REFUSED, reason, m, c.dir)
                    changed = true
                }
            }
        }

        // Topological order (Kahn), stable: folder order (core first) then name among the ready ones.
        val tierRank = { c: ModuleCandidate -> if (c.tier == Tier.CORE) 0 else 1 }
        val order = mutableListOf<ModuleCandidate>()
        val remaining = byName.values.sortedWith(compareBy<ModuleCandidate>({ tierRank(it) }, { it.manifest!!.name })).toMutableList()
        val placed = HashSet<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { c -> before(c.manifest!!, byName).all { it in placed } } ?: break
            remaining.remove(next)
            order += next
            placed += next.manifest!!.name
        }
        // Whatever is left waits on itself through a cycle (or on a module of the cycle).
        for (c in remaining) {
            val m = c.manifest!!
            final[m.name] = ModuleStatus(m.name, c.tier, ModuleState.REFUSED, "dependency cycle: ${cyclePath(m.name, byName)}", m, c.dir)
        }

        val loaded = order.map { ModuleStatus(it.manifest!!.name, it.tier, ModuleState.LOADED, null, it.manifest, it.dir) }
        val statuses = loaded + final.values
        statuses.forEach { log += it.logLine }

        val preferences = preferences(loaded, config, log)
        return Resolution(statuses, preferences, log)
    }

    /** The preferences of the loaded modules that the trust rules allow, in load order (the last one wins). */
    private fun preferences(loaded: List<ModuleStatus>, config: ModulesConfig, log: MutableList<String>): List<AcceptedPreference> {
        // Who declares each contribution id. HOOKS-1: tabs and panel sections; a tab replaces a tab, a section a section.
        val owner = HashMap<String, ModuleStatus>()
        val ownerSection = HashMap<String, ModuleStatus>()
        for (s in loaded) for (t in s.manifest!!.tabs) if (t.id !in owner) owner[t.id] = s
        for (s in loaded) for (p in s.manifest!!.panels) if (p.id !in ownerSection) ownerSection[p.id] = s
        val out = mutableListOf<AcceptedPreference>()
        for (s in loaded) for (p: PreferenceDeclaration in s.manifest!!.preferences) {
            val myTab = s.manifest.tabs.any { it.id == p.with }
            val mySection = s.manifest.panels.any { it.id == p.with }
            val target = if (myTab) owner[p.replace] else ownerSection[p.replace]
            when {
                !myTab && !mySection -> log += "[plugins] ${s.name}: preference ${p.replace} -> ${p.with} refused: ${p.with} is not a tab or panel section it declares"
                target == null -> log += "[plugins] ${s.name}: preference ${p.replace} -> ${p.with} refused: no loaded module provides " +
                    "${if (myTab) "tab" else "panel section"} ${p.replace}"
                s.tier == Tier.COMMUNITY && target.tier == Tier.CORE && p.replace !in config.setting(s.name).allowOverride ->
                    log += "[plugins] ${s.name}: preference ${p.replace} -> ${p.with} refused: a community module cannot replace core ${p.replace} (${target.name}) unless modules.json allows it"
                else -> {
                    out.lastOrNull { it.replace == p.replace }?.let { log += "[plugins] ${s.name}: replaces ${p.replace}, overriding ${it.module}" }
                    out += AcceptedPreference(s.name, p.replace, p.with)
                    log += "[plugins] ${s.name}: replaces ${p.replace} with ${p.with}"
                }
            }
        }
        return out
    }

    /** What must load before [m]: its dependencies and the `sequence` names that are loading (an absent one is ignored). */
    private fun before(m: ModuleManifest, byName: Map<String, ModuleCandidate>): List<String> =
        (m.dependencies.map { it.id } + m.sequence).distinct().filter { it in byName }

    private fun cyclePath(start: String, byName: Map<String, ModuleCandidate>): String {
        val path = mutableListOf(start)
        var at = start
        repeat(byName.size) {
            val next = byName[at]?.manifest?.let { before(it, byName) }?.firstOrNull() ?: return path.joinToString(" -> ")
            path += next
            if (next == start) return path.joinToString(" -> ")
            at = next
        }
        return path.joinToString(" -> ")
    }
}
