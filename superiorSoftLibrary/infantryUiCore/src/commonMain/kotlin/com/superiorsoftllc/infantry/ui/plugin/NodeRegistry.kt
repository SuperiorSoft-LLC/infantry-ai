// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.InjectedBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.plugin.config.ChildrenControl
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell

/** P1 (f60f4d97): how a container's children are registered (docs/BLOCKS-LAYOUT-DESIGN.md section 6s). */
enum class HostKind {
    /** A tab strip: its children are the tabs modules declare for its container, as [TabFactory] resolved them. */
    TABS,
    /** A node of the core tree or a core tab tree, as [com.superiorsoftllc.infantry.ui.block.layout.BlockResolver] resolved it. */
    TREE,
}

/** P1: where a registered child came from. */
enum class ChildSource(val label: String) {
    /** A module's tab. */
    TAB("Tab"),
    /** A core module's tab that draws a tab tree ([TabFactory.tabTreeRoots]). S4d: was labelled "CoreShell.tabs" (pre-S4). */
    CORE_TAB_TREE("Tab tree"),
    /** S4d: a node of an empty shell slot's fill (S4), the fill's root or under it; drawn by the core module that fills it. */
    FILL("Fill"),
    /** A tab replaced through a preference, or a node a module's shell override replaces. */
    REPLACE("Replace"),
    /** A node a module's shell override wraps. */
    WRAP("Wrap"),
    /** A block a module injected among the node's children. */
    INJECTION("Injection"),
    /** A core node, as shipped. */
    SHELL("Core tree"),
}

/** The module name shown for the core tree's own nodes, which no module registers. */
const val CORE_SHELL_MODULE = "Infantry core"

/**
 * P1: one child registered in a container node. [module] is who draws it now (for a replace or a wrap the overriding
 * module), [by] the module whose tab or node it took over, if any. [order] is null where none applies (a tab whose module
 * did not load). [locked]: no module may replace, wrap or inject into it ([CoreShell.locked]). [enabled]: drawn now.
 * [loaded]: false for a tab whose module did not load (Modules decides that, not this node's settings).
 */
data class RegisteredChild(
    val id: String,
    val name: String,
    val module: String,
    val version: String?,
    val tier: Tier,
    val order: Int?,
    val source: ChildSource,
    val by: String? = null,
    val locked: Boolean = false,
    val enabled: Boolean = true,
    val loaded: Boolean = true,
)

/**
 * P1: a node that hosts registered children, and those children in the order they are drawn; P1 step 2: a child its
 * settings disable keeps its place, marked disabled; a tab whose module did not load comes last.
 */
data class HostNode(val id: String, val title: String, val kind: HostKind, val locked: Boolean, val children: List<RegisteredChild>)

/**
 * P1 (docs/BLOCKS-LAYOUT-DESIGN.md section 6s): the registry read model. For every container node, what is registered in
 * it, built from what [TabFactory] already resolved (its tabs, its preferences, its resolved trees), never a second copy.
 * P1 step 2: the node's own settings ([ChildrenControl]) are read on top: its stored order and its disabled children.
 */
object NodeRegistry {
    /** The tab strip nodes, by the [TabContainers] container they draw. */
    val strips: Map<String, String> = linkedMapOf(
        "infantry.shell.center.chat.tabs" to TabContainers.CENTER,
        "infantry.shell.right.body.strip" to TabContainers.RIGHT,
    )

    private val stripTitles = mapOf(TabContainers.CENTER to "Centre tab strip", TabContainers.RIGHT to "Right tab strip")

    /** Every host node: the tab strips, then each tab that has sub-tabs, then every tree container in tree order. */
    fun of(factory: TabFactory): List<HostNode> {
        val strips = strips.map { (node, container) -> tabHost(factory, node, stripTitles[container] ?: container, factory.declaredTabs(container), container) }
        val parents = strips.flatMap { it.children }.filter { factory.declaredChildren(it.id).isNotEmpty() }
            .map { tabHost(factory, it.id, it.name, factory.declaredChildren(it.id), container = null) }
        val trees = listOf(factory.resolvedShellRoot) + factory.tabTreeRoots.map { factory.resolvedTabTree(it.id) ?: it }
        return strips + parents + trees.flatMap { treeHosts(factory, it, owner = null) }
    }

    /** The host node [id], or null if it hosts nothing. */
    fun host(factory: TabFactory, id: String): HostNode? = of(factory).firstOrNull { it.id == id }

    private fun tabHost(factory: TabFactory, node: String, title: String, declared: List<TabProvider>, container: String?): HostNode {
        val trees = factory.tabTreeTabs.entries.associate { (tree, tab) -> tab to tree }
        val off = ChildrenControl.disabled(factory.config, node)
        val rank = ChildrenControl.order(factory.config, node).withIndex().associate { (i, c) -> c to i }
        val tabs = factory.sorted(node, declared).withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val home = declared.firstOrNull()?.id
        val drawn = tabs.map { t ->
            val replaced = factory.replacedTabs[t.id]
            // A module's override of the tab's core tree draws the tab now, over the tab's own module.
            val tree = trees[t.id]?.let(factory::tabTree)
            val (source, by) = when {
                replaced != null -> ChildSource.REPLACE to replaced
                tree is OverrideBlock -> treeSource(tree) to t.module
                trees[t.id] != null -> ChildSource.CORE_TAB_TREE to null
                else -> ChildSource.TAB to null
            }
            val module = (tree as? OverrideBlock)?.module ?: t.module
            val m = factory.modules.firstOrNull { it.manifest.name == module }
            RegisteredChild(t.id, label(t), module, m?.manifest?.version, m?.tier ?: Tier.CORE, t.order, source, by, t.id in CoreShell.locked, enabled = t.id == home || t.id !in off)
        }
        // A tab its module declares while the module did not load: listed, not drawn.
        val unloaded = if (container == null) emptyList() else factory.statuses.filter { it.state != ModuleState.LOADED }.flatMap { s ->
            s.manifest?.tabs.orEmpty().filter { it.container == container && tabs.none { t -> t.id == it.id } }.map {
                RegisteredChild(it.id, it.id, s.name, s.manifest?.version, s.tier, null, ChildSource.TAB, locked = it.id in CoreShell.locked, enabled = false, loaded = false)
            }
        }
        return HostNode(node, title, HostKind.TABS, node in CoreShell.locked, drawn + unloaded)
    }

    private fun treeSource(b: OverrideBlock) = if (b.mode == OverrideModes.WRAP) ChildSource.WRAP else ChildSource.REPLACE

    /** What [b] draws its children from: a wrap's inner block (a replace has none the core knows). */
    private fun contents(b: Block): List<Block> = if (b is OverrideBlock) { if (b.mode == OverrideModes.WRAP) contents(b.inner) else emptyList() } else b.children

    /** [owner]: the module whose fill [b] is in (S4d), null in the core tree. */
    private fun treeHosts(factory: TabFactory, b: Block, owner: String?): List<HostNode> {
        val kids = contents(b)
        fun ownerOf(c: Block) = factory.fillModules[c.id] ?: owner
        val here = if (kids.isEmpty()) emptyList() else listOf(
            HostNode(
                b.id, b.id.removePrefix("infantry.shell."), HostKind.TREE, b.id in CoreShell.locked,
                kids.mapIndexed { i, c -> treeChild(factory, c, i, ownerOf(c), ChildrenControl.disabled(factory.config, b.id)) },
            ),
        )
        return here + kids.flatMap { treeHosts(factory, it, ownerOf(it)) }
    }

    private fun treeChild(factory: TabFactory, c: Block, index: Int, owner: String?, off: Set<String>): RegisteredChild {
        val locked = c.id in CoreShell.locked
        fun by(module: String, source: ChildSource, over: String? = null): RegisteredChild {
            val m = factory.modules.firstOrNull { it.manifest.name == module }
            return RegisteredChild(c.id, c.id, module, m?.manifest?.version, m?.tier ?: Tier.CORE, index, source, over, locked)
        }
        return when (c) {
            is InjectedBlock -> by(c.module, ChildSource.INJECTION).copy(enabled = c.id !in off)
            is OverrideBlock -> by(c.module, treeSource(c), owner ?: CORE_SHELL_MODULE)
            else -> if (owner != null) by(owner, ChildSource.FILL) else RegisteredChild(c.id, c.id, CORE_SHELL_MODULE, null, Tier.CORE, index, ChildSource.SHELL, locked = locked)
        }
    }

    /** A tab's strip label without its live badge or shortcut hint (read against an empty state, so it never changes). */
    private fun label(t: TabProvider): String = t.title(AppState()).substringBefore(" ^").substringBefore(" (").trim().ifEmpty { t.id }
}
