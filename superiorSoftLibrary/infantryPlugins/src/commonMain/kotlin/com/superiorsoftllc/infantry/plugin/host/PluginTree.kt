// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest

/**
 * PLUGIN-TREE-UI-1 / PLUGIN-TREE-2a: the parent/child relation between modules, from manifest data only. A module is a
 * child of P when it declares P in `parents`, when one of its `panels` fills a slot that P declares in `slots`, or when
 * one of its `inject` entries targets a node P owns. The owner of a node id is the module whose name prefixes the id as
 * `<Module>.`, or the module that declares the id in `tabs`, `fills`, `nodes` or `slots`; core shell ids
 * (`infantry.shell.`) have no owner, so they make no edge. Edges to uninstalled modules and self edges are ignored. A
 * cycle is broken deterministically: modules are walked in sorted order and the edge that closes a cycle is dropped
 * (reported in [problems]). A member left without a parent is a root. No ids are known here.
 */
class PluginTree(manifests: List<ModuleManifest>) {
    private val byName = manifests.associateBy { it.name }
    private val slotOwners: Map<String, List<String>> =
        manifests.flatMap { m -> m.slots.map { it to m.name } }.groupBy({ it.first }, { it.second })
    private val declaredOwners: Map<String, List<String>> = manifests.flatMap { m ->
        (m.tabs.map { it.id } + m.fills.map { it.node } + m.nodes.map { it.id } + m.slots).map { it to m.name }
    }.groupBy({ it.first }, { it.second })

    private fun ownersOf(id: String): List<String> {
        if (id.startsWith("infantry.shell")) return emptyList()
        val prefixed = byName.keys.filter { id.startsWith("$it.") }
        return prefixed + declaredOwners[id].orEmpty()
    }

    private val rawParents: Map<String, List<String>> = byName.keys.sorted().associateWith { name ->
        val m = byName.getValue(name)
        val viaSlots = m.panels.flatMap { slotOwners[it.panel].orEmpty() }
        val viaInject = m.inject.flatMap { ownersOf(it.targetId) }
        (m.parents + viaSlots + viaInject).filter { it != name && it in byName }.distinct().sorted()
    }

    private val resolved: Pair<Map<String, List<String>>, List<String>> = breakCycles()

    private fun breakCycles(): Pair<Map<String, List<String>>, List<String>> {
        val kept = rawParents.mapValues { it.value.toMutableList() }
        val problems = mutableListOf<String>()
        val done = HashSet<String>()
        val stack = ArrayList<String>()
        fun visit(n: String) {
            stack += n
            for (p in kept.getValue(n).toList()) {
                val at = stack.indexOf(p)
                if (at >= 0) {
                    problems += "plugin parent cycle: ${(stack.subList(at, stack.size) + p).joinToString(" -> ")}, edge $n -> $p ignored"
                    kept.getValue(n).remove(p)
                } else if (p !in done) visit(p)
            }
            stack.removeAt(stack.size - 1)
            done += n
        }
        for (n in kept.keys.sorted()) if (n !in done) visit(n)
        return kept.mapValues { it.value.toList() } to problems
    }

    /** One line per parent edge dropped to break a cycle; empty when the graph is acyclic. */
    val problems: List<String> get() = resolved.second

    /** The direct parents of [name], sorted; only installed modules count. */
    fun parentsOf(name: String): List<String> = resolved.first[name].orEmpty()

    /** The direct children of [name] (not grandchildren), sorted. */
    fun childrenOf(name: String): List<String> = byName.keys.filter { name in parentsOf(it) }.sorted()

    /** Modules with no parent: the top level of the Plugins tab. */
    fun roots(): List<String> = byName.keys.filter { parentsOf(it).isEmpty() }.sorted()
}
