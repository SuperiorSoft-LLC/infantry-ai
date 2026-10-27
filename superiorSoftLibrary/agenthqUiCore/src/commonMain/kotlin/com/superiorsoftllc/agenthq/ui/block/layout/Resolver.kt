package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.runtime.Composable

/** BLOCKS-LAYOUT L5: how an override takes a node over (module.json `shell[].mode`). */
object OverrideModes {
    /** The core block is not drawn; the override draws in its place. */
    const val REPLACE = "replace"
    /** The override draws around the core block, which it gets as `inner`. */
    const val WRAP = "wrap"
    val ALL = setOf(REPLACE, WRAP)
}

/**
 * BLOCKS-LAYOUT L5: what a module draws for a node it overrides. [content] gets the node's [BlockScope] (apply its
 * modifier exactly once, on the outermost node, as any block does) and, for a wrap, the block it wraps as `inner`
 * (`inner.Render(scope)` hands the placement on); a replace gets null.
 */
class BlockOverride(val content: @Composable (scope: BlockScope, inner: Block?) -> Unit)

/** One override of node [node] by [module], already through the trust rules. */
class NodeOverride(val module: String, val node: String, val mode: String, val override: BlockOverride)

/** The block drawn for an overridden node: it keeps the node's id and place, so its parent places it the same way. */
class OverrideBlock(val module: String, val mode: String, val inner: Block, private val override: BlockOverride) :
    Block("override", inner.id, inner.place) {
    @Composable
    override fun Render(scope: BlockScope) = override.content(scope, if (mode == OverrideModes.WRAP) inner else null)
}

/** A resolved tree and every override it refused or ignored, one line each (never a silent drop). */
class Resolved(val root: Block, val problems: List<String>)

/**
 * BLOCKS-LAYOUT L5 (docs/BLOCKS-LAYOUT-DESIGN.md section 6): puts the overrides into a tree. It runs when the module
 * set changes, not per frame. Each node is resolved bottom-up: its children first (the node is rebuilt through
 * `withChildren` only if one changed), then the node's own overrides in the order given (load order): a wrap wraps
 * what is there, so the last loaded is outermost; a replace drops what is there, so the last replace wins.
 * - an unknown node id or mode is ignored with a warning;
 * - a [locked] node refuses replace and wrap, and so does any replace that would remove a locked node below it.
 * With no override that applies the result is [root] itself, the very same object.
 */
object BlockResolver {
    fun resolve(root: Block, overrides: List<NodeOverride>, locked: Set<String>, tree: String = "infantry.shell"): Resolved {
        if (overrides.isEmpty()) return Resolved(root, emptyList())
        val nodes = HashMap<String, Block>()
        fun index(b: Block) { nodes.getOrPut(b.id) { b }; b.children.forEach(::index) }
        index(root)
        fun lockedBelow(b: Block): String? = b.children.firstNotNullOfOrNull { c -> c.id.takeIf { it in locked } ?: lockedBelow(c) }

        val problems = mutableListOf<String>()
        val byNode = LinkedHashMap<String, MutableList<NodeOverride>>()
        for (o in overrides) {
            val node = nodes[o.node]
            val what = "${o.module}: shell override (${o.mode}) of ${o.node}"
            val below = node?.takeIf { o.mode == OverrideModes.REPLACE }?.let(::lockedBelow)
            when {
                node == null -> problems += "$what ignored: $tree has no node ${o.node}"
                o.mode !in OverrideModes.ALL -> problems += "$what ignored: unknown mode '${o.mode}' (${OverrideModes.ALL.joinToString()})"
                o.node in locked -> problems += "$what refused: ${o.node} is locked"
                below != null -> problems += "$what refused: it would remove locked node $below"
                else -> {
                    val list = byNode.getOrPut(o.node) { mutableListOf() }
                    if (o.mode == OverrideModes.REPLACE) list.lastOrNull()?.let { problems += "${o.module}: replaces ${o.node}, overriding ${it.module}" }
                    list += o
                }
            }
        }
        if (byNode.isEmpty()) return Resolved(root, problems)

        fun resolveNode(b: Block): Block {
            val kids = b.children.map(::resolveNode)
            val rebuilt = if (kids.indices.all { kids[it] === b.children[it] }) b else b.withChildren(kids)
            return byNode[b.id].orEmpty().fold(rebuilt) { inner, o -> OverrideBlock(o.module, o.mode, inner, o.override) }
        }
        return Resolved(resolveNode(root), problems)
    }
}
