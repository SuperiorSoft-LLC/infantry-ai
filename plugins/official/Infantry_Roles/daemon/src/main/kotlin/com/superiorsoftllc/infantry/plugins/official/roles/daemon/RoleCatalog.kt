package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.RoleIds

import java.io.File

/**
 * The role catalog: everything the UI needs to render a role badge and to hand a spawned agent its
 * persona, assembled from three sources with a fixed precedence.
 *
 * 1. **Custom role files** (an operator's directory, [CustomRoles]) win: defining `backend.md`
 *    replaces the bundled `backend` for that id only.
 * 2. **The bundled manifest** (`/roles/index.json`) next.
 * 3. **The [RoleIds] contract last**: a canonical id the manifest does not mention is still a known
 *    role, with a generated colour and no persona. This is what makes an empty manifest a working
 *    catalog rather than an empty screen.
 *
 * A lookup for an id nobody defines still returns a [Role] via [rendered] (`source = GENERATED`), so
 * a badge always paints: colour and initials, never an empty frame.
 */
class RoleCatalog(
    builtin: List<Role> = emptyList(),
    custom: List<Role> = emptyList(),
) {
    private val customById: Map<String, Role> = custom.associateBy { it.id }
    private val manifestById: Map<String, Role> = builtin.associateBy { it.id }

    private val ordered: List<Role> = buildList {
        val emitted = HashSet<String>()
        for (id in RoleIds.BUILT_IN) {
            add(resolve(id))
            emitted.add(id)
        }
        for (id in (manifestById.keys + customById.keys).filter { it !in emitted }.sorted()) {
            add(resolve(id))
        }
    }

    private val byId: Map<String, Role> = ordered.associateBy { it.id }

    private fun resolve(id: String): Role = customById[id] ?: manifestById[id] ?: Role(
        id = id,
        name = prettify(id),
        color = RolePalette.colorFor(id),
        persona = "",
        source = RoleSource.BUILT_IN,
    )

    /** Every role, in a stable order: the canonical ids first, then any extra ids sorted. */
    fun all(): List<Role> = ordered

    fun ids(): List<String> = ordered.map { it.id }

    /** The role [id] as defined by a custom file, the manifest, or the canonical contract; null if unknown. */
    fun role(id: String): Role? = byId[id]

    /** Always a role: the definition when there is one, else a synthesised generated one. */
    fun rendered(id: String): Role = role(id) ?: Role(
        id = id,
        name = prettify(id),
        color = RolePalette.colorFor(id),
        persona = "",
        source = RoleSource.GENERATED,
    )

    /** The persona (system prompt) for [id], or null when the role is unknown or has no persona. */
    fun persona(id: String): String? = role(id)?.persona?.takeIf { it.isNotBlank() }

    companion object {
        /** Load the bundled catalog, overlaid with any custom role files in [customDir]. */
        fun load(customDir: File? = null): RoleCatalog = RoleBundle.catalog(customDir)

        /** `chief-of-staff` -> `Chief Of Staff`; the display name for a role with no definition. */
        internal fun prettify(id: String): String = id.split('-', '_', ' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
            .ifBlank { id }
    }
}
