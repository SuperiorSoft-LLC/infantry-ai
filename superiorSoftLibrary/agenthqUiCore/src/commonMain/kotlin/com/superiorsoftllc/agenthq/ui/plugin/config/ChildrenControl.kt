package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.plugin.ChildSource
import com.superiorsoftllc.agenthq.ui.plugin.HostKind
import com.superiorsoftllc.agenthq.ui.plugin.HostNode
import com.superiorsoftllc.agenthq.ui.plugin.RegisteredChild
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s E): a container's children control, stored on the container node itself in
 * `node_config` (never inherited, [isOwnOnlyKey]): [DISABLED] the child ids it does not draw, [ORDER] a tab strip's
 * child ids in the order the user put them (a child not named keeps its declared place after them).
 */
object ChildrenControl {
    const val DISABLED = "children.disabled"
    const val ORDER = "children.order"

    fun disabled(state: NodeConfigState, node: String): Set<String> = ids(state, node, DISABLED).toSet()

    fun order(state: NodeConfigState, node: String): List<String> = ids(state, node, ORDER)

    private fun ids(state: NodeConfigState, node: String, key: String): List<String> =
        (state.own(node, key)?.value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    /**
     * [items] (in declared order) as [node]'s settings arrange them: its stored order first, then the rest as declared,
     * without the disabled ones. The [home] child is never dropped, so a strip always has a tab to open on.
     */
    fun <T> arrange(state: NodeConfigState, node: String, items: List<T>, home: String?, id: (T) -> String): List<T> {
        val off = disabled(state, node)
        val order = order(state, node)
        val rank = order.withIndex().associate { (i, c) -> c to i }
        return items.withIndex()
            .filter { (_, t) -> id(t) == home || id(t) !in off }
            .sortedWith(compareBy({ rank[id(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /**
     * Why [child] of [host] cannot be switched off or moved, or null when it can. Locked nodes and children never; a
     * tree container's core children as shipped never (only what a module injected); a strip's home tab never; a
     * community module's child only where modules.json grants it [host] (`allowOverride` or `allowInject`).
     */
    fun refusal(factory: TabFactory, host: HostNode, child: RegisteredChild): String? = when {
        host.locked -> "Locked node"
        child.locked -> "Locked"
        !child.loaded -> "Module off"
        host.kind == HostKind.TREE && child.source != ChildSource.INJECTION -> "Core tree"
        child.id == homeOf(factory, host) -> "Home tab"
        child.tier == Tier.COMMUNITY && !factory.granted(child.module, host.id) -> "Not granted"
        else -> null
    }

    /** Whether [host]'s children may be reordered here: a tab strip that is not locked. */
    fun reorderable(host: HostNode): Boolean = host.kind == HostKind.TABS && !host.locked && host.id !in CoreShell.locked

    /** The tab a strip host opens on (its first declared tab), or null for a tree container. */
    fun homeOf(factory: TabFactory, host: HostNode): String? = when {
        host.kind != HostKind.TABS -> null
        else -> com.superiorsoftllc.agenthq.ui.plugin.NodeRegistry.strips[host.id]?.let { factory.declaredTabs(it).firstOrNull()?.id }
            ?: factory.declaredChildren(host.id).firstOrNull()?.id
    }

    /** Switches [childId] of [node] on or off; answers whether the daemon took it. */
    suspend fun setEnabled(state: NodeConfigState, node: String, childId: String, enabled: Boolean): Boolean {
        val now = disabled(state, node)
        val next = if (enabled) now - childId else now + childId
        return state.set(node, DISABLED, if (next.isEmpty()) null else JsonArray(next.sorted().map(::JsonPrimitive)))
    }

    /** Moves [childId] by [delta] places among [current] (the host's children in drawn order) and stores the whole order. */
    suspend fun move(state: NodeConfigState, node: String, current: List<String>, childId: String, delta: Int): Boolean {
        val from = current.indexOf(childId)
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) return true
        val next = current.toMutableList().apply { add(to, removeAt(from)) }
        return state.set(node, ORDER, JsonArray(next.map(::JsonPrimitive)))
    }
}
