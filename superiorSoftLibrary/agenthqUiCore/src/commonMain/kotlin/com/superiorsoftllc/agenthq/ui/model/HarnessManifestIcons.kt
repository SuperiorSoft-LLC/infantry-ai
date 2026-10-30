package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus

/**
 * HARNESS-PLUGIN-4 H5a (docs/HARNESS-PLUGINS.md 3.8): the harness icons the loaded modules declare (module.json
 * `harness.icon`, an svg or png inside the module folder), by harness id and alias. Read from this client's own module
 * resolution, so no frame carries it. A host whose modules have no folder (web, Android: compiled in) gets none, and the
 * pill keeps its built-in glyph or the fallback.
 *
 * Trusted tiers claim first; a community module may not take an id another module claimed, nor one [hasBuiltIn] knows,
 * so it cannot repaint a harness it does not own.
 */
object HarnessManifestIcons {
    /** id or alias (lower case) -> absolute icon path. */
    fun of(statuses: List<ModuleStatus>, hasBuiltIn: (String) -> Boolean = { false }): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        statuses.filter { it.state == ModuleState.LOADED }.sortedBy { if (it.tier == Tier.COMMUNITY) 1 else 0 }.forEach { s ->
            val h = s.manifest?.harness ?: return@forEach
            val icon = h.icon?.takeIf { safe(it) } ?: return@forEach
            val dir = s.dir ?: return@forEach
            val path = dir.trimEnd('/', '\\') + "/" + icon
            (h.ids + h.aliases.keys).map { it.trim().lowercase() }.forEach { id ->
                if (id.isEmpty() || id in out) return@forEach
                if (s.tier == Tier.COMMUNITY && hasBuiltIn(id)) return@forEach
                out[id] = path
            }
        }
        return out
    }

    /** Whether [path] is an svg/png file name inside the module folder: relative, forward slashes, no `..`. */
    fun safe(path: String): Boolean {
        val parts = path.split('/')
        val inside = path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
            parts.none { it.isEmpty() || it == "." || it == ".." }
        return inside && (isSvg(path) || isPng(path))
    }

    fun isSvg(path: String): Boolean = path.lowercase().endsWith(".svg")

    fun isPng(path: String): Boolean = path.lowercase().endsWith(".png")
}
