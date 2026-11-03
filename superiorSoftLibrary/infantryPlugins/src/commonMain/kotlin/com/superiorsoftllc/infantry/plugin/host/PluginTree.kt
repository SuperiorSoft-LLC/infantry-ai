// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest

/**
 * PLUGIN-TREE-UI-1: the parent/child relation between modules, from manifest data only. A module is a child of P when
 * it declares P in `parents`, or when one of its `panels` fills a slot that P declares in `slots`. No ids are known here.
 */
class PluginTree(manifests: List<ModuleManifest>) {
    private val byName = manifests.associateBy { it.name }
    private val slotOwners: Map<String, List<String>> =
        manifests.flatMap { m -> m.slots.map { it to m.name } }.groupBy({ it.first }, { it.second })

    /** The direct parents of [name], sorted; only installed modules count. */
    fun parentsOf(name: String): List<String> {
        val m = byName[name] ?: return emptyList()
        val viaSlots = m.panels.flatMap { slotOwners[it.panel].orEmpty() }
        return (m.parents + viaSlots).filter { it != name && it in byName }.distinct().sorted()
    }

    /** The direct children of [name] (not grandchildren), sorted. */
    fun childrenOf(name: String): List<String> = byName.keys.filter { name in parentsOf(it) }.sorted()

    /** Modules with no parent: the top level of the Plugins tab. */
    fun roots(): List<String> = byName.keys.filter { parentsOf(it).isEmpty() }.sorted()

    /** Owner rule: a child shared by several parents may not be switched off while one of them is enabled; null = allowed. */
    fun disableRefusal(name: String, isEnabled: (String) -> Boolean): String? {
        val parents = parentsOf(name)
        if (parents.size < 2) return null
        val on = parents.filter(isEnabled)
        return if (on.isEmpty()) null else "$name is used by ${on.joinToString(", ")}; it stays enabled until none of them is."
    }
}
