package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import com.superiorsoftllc.agenthq.plugin.api.GrantPattern
import com.superiorsoftllc.agenthq.ui.LocalCoreTier
import com.superiorsoftllc.agenthq.ui.ModuleTier
import com.superiorsoftllc.agenthq.ui.plugin.ModuleScope

/** BLOCKS-LAYOUT L5: how an override takes a node over (module.json `shell[].mode`). */
object OverrideModes {
    /** The core block is not drawn; the override draws in its place. */
    const val REPLACE = "replace"
    /** The override draws around the core block, which it gets as `inner`. */
    const val WRAP = "wrap"
    /** OVERRIDE-1: the override draws as the node's previous sibling in its parent; the node itself is untouched. */
    const val BEFORE = "before"
    /** OVERRIDE-1: the override draws as the node's next sibling in its parent; the node itself is untouched. */
    const val AFTER = "after"
    val ALL = setOf(REPLACE, WRAP, BEFORE, AFTER)
}

/**
 * BLOCKS-LAYOUT L5: what a module draws for a node it overrides. [content] gets the node's [BlockScope] (apply its
 * modifier exactly once, on the outermost node, as any block does) and, for a wrap, the block it wraps as `inner`
 * (`inner.Render(scope)` hands the placement on); a replace gets null.
 */
class BlockOverride(val content: @Composable (scope: BlockScope, inner: Block?) -> Unit)

/** One override of node [node] by [module], already through the trust rules. */
class NodeOverride(
    val module: String,
    /** A node id or an OVERRIDE-1 selector ([NodeSelector]). */
    val node: String,
    val mode: String,
    val override: BlockOverride,
    val community: Boolean = false,
    /** OVERRIDE-1: higher wins a replace, is outermost for a wrap and goes first among before / after; ties: list (load) order. */
    val priority: Int = 0,
)

/**
 * OVERRIDE-1 (docs/PLUGIN-OVERRIDES.md section 1): the DOM-like address of a block. A selector is either a plain node id
 * (`infantry.shell.footer`, the node with that id anywhere in the tree) or a path of ids joined by `/`, each the direct
 * parent of the next (`infantry.shell.left/infantry.board.button`); the path ends at the block it addresses and need not
 * start at the root. A segment `[n]` is the n-th child (from 0) of the segment before it. The old `infantry.shell#<id>`
 * address is still a plain id.
 */
object NodeSelector {
    /** The id of the node [selector] addresses in [root], or null. The first match in tree order wins. */
    fun find(root: Block, selector: String): String? {
        val segs = selector.removePrefix("infantry.shell#").split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        if (segs.size == 1) return segs[0].takeIf { !isIndex(it) && contains(root, it) }
        fun matches(chain: List<Block>): Boolean {
            // chain = root..candidate; the path must equal the tail of the chain, an [n] segment checks the child index.
            var i = chain.size - 1
            for (k in segs.indices.reversed()) {
                val seg = segs[k]
                if (i < 0) return false
                if (isIndex(seg)) {
                    val n = seg.drop(1).dropLast(1).toIntOrNull() ?: return false
                    if (i == 0 || chain[i - 1].children.getOrNull(n) !== chain[i]) return false
                } else if (chain[i].id != seg) return false
                i--
            }
            return true
        }
        fun walk(chain: List<Block>): String? {
            if (matches(chain)) return chain.last().id
            return chain.last().children.firstNotNullOfOrNull { walk(chain + it) }
        }
        return walk(listOf(root))
    }

    private fun isIndex(seg: String) = seg.length > 2 && seg.startsWith("[") && seg.endsWith("]")
    private fun contains(b: Block, id: String): Boolean = b.id == id || b.children.any { contains(it, id) }
}

/**
 * The block drawn for an overridden node: it keeps the node's id and place, so its parent places it the same way. A
 * replace of a node that wears the host surface (S1: `infantry.shell.frame`) gets the surface in its scope's modifier;
 * a wrap leaves it to `inner`.
 */
class OverrideBlock(
    val module: String,
    val mode: String,
    val inner: Block,
    private val override: BlockOverride,
    private val community: Boolean = false,
) : Block("override", inner.id, inner.place) {
    @Composable
    override fun Render(scope: BlockScope) =
        // GAP-AUDIT-1 (a): probed before it is drawn; one that throws is never drawn, the node as core drew it is.
        // A wrap's inner node is movable content, so the switch from the fallback to the wrap keeps its state; the probe
        // gets an empty stand-in for it, so only the module's code is probed and core's subtree is never composed twice.
        if (mode == OverrideModes.WRAP) {
            val kept = remember(inner) { movableContentOf { s: BlockScope -> inner.Render(s) } }
            val movable = remember(inner, kept) { MovableBlock(inner, kept) }
            GuardedBlock(OverrideHealth.key(module, mode, inner.id), override, fallback = { kept(scope) }, probe = { Draw(scope, ProbeStandIn(inner)) }) { Draw(scope, movable) }
        } else {
            GuardedBlock(OverrideHealth.key(module, mode, inner.id), override, fallback = { inner.Render(scope) }) { Draw(scope, inner) }
        }

    @Composable
    private fun Draw(scope: BlockScope, inner: Block) = if (mode == OverrideModes.WRAP) {
        // S4d: a community wrap draws without the core tier; the node it wraps is drawn back at the tier around it.
        if (community) {
            val outer = LocalCoreTier.current
            ModuleTier(false) { ModuleScope(module) { override.content(scope, TierBlock(inner, outer)) } }
        } else override.content(scope, inner)
    } else {
        val s = if (inner.wearsHostSurface) BlockScope(scope.modifier.then(LocalHostSurface.current), scope.box, scope.row, scope.blockId) else scope
        if (community) ModuleTier(false) { ModuleScope(module) { override.content(s, null) } } else override.content(s, null)
    }

    override val wearsHostSurface: Boolean get() = inner.wearsHostSurface
}

/** GAP-AUDIT-1 (a): what a wrap's probe gets as `inner`: the node's id, place and children, drawing nothing. */
private class ProbeStandIn(private val inner: Block) : Block(inner.blockType, inner.id, inner.place, inner.children) {
    @Composable
    override fun Render(scope: BlockScope) {}

    override fun withChildren(children: List<Block>): Block = ProbeStandIn(inner.withChildren(children))
}

/** GAP-AUDIT-1 (a): a wrap's [inner] node drawn through [kept], the movable content its fallback drew. */
private class MovableBlock(private val inner: Block, private val kept: @Composable (BlockScope) -> Unit) : Block(inner.blockType, inner.id, inner.place, inner.children) {
    @Composable
    override fun Render(scope: BlockScope) = kept(scope)

    override fun withChildren(children: List<Block>): Block = inner.withChildren(children)

    override val wearsHostSurface: Boolean get() = inner.wearsHostSurface
}

/** S4d: [inner] drawn at module tier [core] (a community wrap's inner node is the shell's, not the module's). */
private class TierBlock(private val inner: Block, private val core: Boolean) : Block(inner.blockType, inner.id, inner.place, inner.children) {
    @Composable
    override fun Render(scope: BlockScope) = CompositionLocalProvider(LocalCoreTier provides core) { inner.Render(scope) }

    /** RUNTIME-17: a wrap may hand the shell's node more children (a module's toast under `infantry.shell.toast`). */
    override fun withChildren(children: List<Block>): Block = TierBlock(inner.withChildren(children), core)

    override val wearsHostSurface: Boolean get() = inner.wearsHostSurface
}

/**
 * BLOCKS-INJECTORS J1 (docs/BLOCKS-INJECTORS.md section 2a): where an injection goes among the target's direct
 * children. The module.json grammar is `first | last | before:<child id> | after:<child id>` ([parse], [toString]).
 */
sealed interface InjectAt {
    data object First : InjectAt { override fun toString() = "first" }
    data object Last : InjectAt { override fun toString() = "last" }
    data class Before(val childId: String) : InjectAt { override fun toString() = "before:$childId" }
    data class After(val childId: String) : InjectAt { override fun toString() = "after:$childId" }

    companion object {
        /** The `at` of module.json, or null if it is not in the grammar (an empty child id included). */
        fun parse(at: String): InjectAt? = when {
            at == "first" -> First
            at == "last" -> Last
            at.startsWith("before:") -> at.removePrefix("before:").takeIf { it.isNotEmpty() }?.let(::Before)
            at.startsWith("after:") -> at.removePrefix("after:").takeIf { it.isNotEmpty() }?.let(::After)
            else -> null
        }
    }
}

/** BLOCKS-INJECTORS J1: what a module registers for an injection, its placement and drawing. The host gives it its id. */
class BlockInjection(val place: Place = Place(), val content: @Composable (scope: BlockScope) -> Unit)

/**
 * BLOCKS-INJECTORS J1: one injection of [module]'s [name] into node [target], placed by [at], then [order], then
 * [loadIndex] (the loader's Kahn order), then [name]. [admitInjections] applies the trust rule, [BlockResolver] the
 * tree's rules.
 */
class NodeInjection(
    val module: String,
    val name: String,
    val target: String,
    val at: InjectAt = InjectAt.Last,
    val order: Int = 0,
    val loadIndex: Int = 0,
    val injection: BlockInjection,
    /** S4d: a community module's injection, drawn without the core tier. */
    val community: Boolean = false,
) {
    /** The id of the drawn node, `<module>.<name>`. */
    val id: String get() = "$module.$name"
}

/** The node drawn for an injection: a leaf with id `<module>.<name>`, so nothing can be injected into it. */
class InjectedBlock(
    val module: String,
    id: String,
    place: Place,
    private val content: @Composable (BlockScope) -> Unit,
    private val community: Boolean = false,
) : Block("injected", id, place) {
    @Composable
    override fun Render(scope: BlockScope) =
        // GAP-AUDIT-1 (a): an injection (and an override's before / after) that throws in the probe draws nothing.
        GuardedBlock(OverrideHealth.key(module, "inject", id), content, fallback = {}) {
            if (community) ModuleTier(false) { ModuleScope(module) { content(scope) } } else content(scope)
        }
}

/**
 * BLOCKS-INJECTORS J1 (section 2b.1), the trust rule: a core module ([isCore]) injects freely, any other module only
 * into a target its modules.json `allowInject` ([allowInject]) lists. Each refusal is one line in [problems].
 */
fun admitInjections(
    injections: List<NodeInjection>,
    isCore: (module: String) -> Boolean,
    allowInject: (module: String) -> Collection<String>,
    problems: MutableList<String>,
): List<NodeInjection> = injections.filter { j ->
    (isCore(j.module) || GrantPattern.matches(allowInject(j.module), j.target)).also { ok ->
        if (!ok) problems += "${j.module}: injection ${j.name} into ${j.target} refused: a community module cannot inject into ${j.target} unless modules.json allowInject lists it"
    }
}

/** A resolved tree and every override or injection it refused or ignored, one line each (never a silent drop). */
class Resolved(val root: Block, val problems: List<String>)

/**
 * BLOCKS-LAYOUT L5 (docs/BLOCKS-LAYOUT-DESIGN.md section 6) and BLOCKS-INJECTORS J1 (docs/BLOCKS-INJECTORS.md section
 * 2): puts the overrides and the injections into a tree. It runs when the module set changes, not per frame. Each node
 * is resolved bottom-up: its children first; then its injections go in among them (`first`, then per child its
 * `before:`, the child, its `after:`, then `last`; within one anchor by order, load order, name); the node is rebuilt
 * through `withChildren` only if a child changed; then the node's own overrides apply in the order given (load order):
 * a wrap wraps what is there, injected children included, so the last loaded is outermost; a replace drops what is
 * there, so the last replace wins and the injections it drops are not drawn (one line each).
 * - an unknown node id or mode is ignored with a warning;
 * - a [locked] node refuses replace, wrap and every injection, and so does any replace that would remove a locked node
 *   below it;
 * - an injection into a node that takes no children, next to an id that is not a direct child of the target, or with
 *   an id already in the tree is refused or ignored with a line.
 * With no override and no injection that applies the result is [root] itself, the very same object.
 */
object BlockResolver {
    fun resolve(
        root: Block,
        overrides: List<NodeOverride>,
        locked: Set<String>,
        tree: String = "infantry.shell",
        injections: List<NodeInjection> = emptyList(),
    ): Resolved {
        if (overrides.isEmpty() && injections.isEmpty()) return Resolved(root, emptyList())
        val nodes = HashMap<String, Block>()
        val parents = HashMap<String, String>()
        fun index(b: Block) { nodes.getOrPut(b.id) { b }; b.children.forEach { parents.getOrPut(it.id) { b.id }; index(it) } }
        index(root)
        fun lockedBelow(b: Block): String? = b.children.firstNotNullOfOrNull { c -> c.id.takeIf { it in locked } ?: lockedBelow(c) }

        val problems = mutableListOf<String>()
        val byNode = LinkedHashMap<String, MutableList<NodeOverride>>()
        // OVERRIDE-1: a higher priority is applied later (so wins a replace, is outermost for a wrap); equal priority keeps list (load) order.
        val ordered = overrides.sortedBy { it.priority }
        val siblings = mutableListOf<NodeInjection>()
        for ((n, o) in ordered.withIndex()) {
            val id = NodeSelector.find(root, o.node)
            val node = id?.let(nodes::get)
            val what = "${o.module}: shell override (${o.mode}) of ${o.node}"
            val below = node?.takeIf { o.mode == OverrideModes.REPLACE }?.let(::lockedBelow)
            val sibling = o.mode == OverrideModes.BEFORE || o.mode == OverrideModes.AFTER
            when {
                node == null -> problems += "$what ignored: $tree has no node ${o.node}"
                o.mode !in OverrideModes.ALL -> problems += "$what ignored: unknown mode '${o.mode}' (${OverrideModes.ALL.joinToString()})"
                node.id in locked -> problems += "$what refused: ${node.id} is locked"
                sibling && parents[node.id] == null -> problems += "$what refused: ${node.id} is the root and has no parent to place a sibling in"
                sibling && parents[node.id] in locked -> problems += "$what refused: ${parents[node.id]} is locked, nothing may be placed beside ${node.id}"
                below != null -> problems += "$what refused: it would remove locked node $below"
                sibling -> {
                    val at = if (o.mode == OverrideModes.BEFORE) InjectAt.Before(node.id) else InjectAt.After(node.id)
                    // injections sort by order then load index: a higher priority goes first, so its order is the lower one.
                    siblings += NodeInjection(o.module, "${o.mode}-${node.id}", parents.getValue(node.id), at, -o.priority, n,
                        BlockInjection { scope -> o.override.content(scope, null) }, o.community)
                }
                else -> {
                    val list = byNode.getOrPut(node.id) { mutableListOf() }
                    if (o.mode == OverrideModes.REPLACE) list.lastOrNull { it.mode == OverrideModes.REPLACE }?.let { problems += "${o.module}: replaces ${node.id}, overriding ${it.module}" }
                    list += o
                }
            }
        }

        val injectInto = LinkedHashMap<String, MutableList<NodeInjection>>()
        val taken = HashSet(nodes.keys)
        for (j in injections + siblings) {
            val node = nodes[j.target]
            val what = "${j.module}: injection ${j.name} into ${j.target} (${j.at})"
            val anchor = when (val at = j.at) { is InjectAt.Before -> at.childId; is InjectAt.After -> at.childId; else -> null }
            when {
                node == null -> problems += "$what ignored: $tree has no node ${j.target}"
                j.target in locked -> problems += "$what refused: ${j.target} is locked"
                anchor != null && node.children.none { it.id == anchor } -> problems += "$what ignored: ${j.target} has no child $anchor"
                !taken.add(j.id) -> problems += "$what refused: duplicate id ${j.id}"
                else -> injectInto.getOrPut(j.target) { mutableListOf() } += j
            }
        }
        if (byNode.isEmpty() && injectInto.isEmpty()) return Resolved(root, problems)

        val byPlace = compareBy<NodeInjection>({ it.order }, { it.loadIndex }, { it.name })
        fun drawn(js: List<NodeInjection>, target: String, at: InjectAt): List<Block> {
            val bucket = js.filter { it.at == at }.sortedWith(byPlace)
            bucket.zipWithNext().filter { (a, b) -> a.order == b.order }.forEach { (a, b) ->
                problems += "${b.module}: injection ${b.name} into $target ($at) has the same order ${b.order} as ${a.id}, which goes first"
            }
            return bucket.map { InjectedBlock(it.module, it.id, it.injection.place, it.injection.content, it.community) }
        }
        fun inject(target: String, kids: List<Block>, js: List<NodeInjection>): List<Block> = buildList {
            addAll(drawn(js, target, InjectAt.First))
            for (k in kids) {
                addAll(drawn(js, target, InjectAt.Before(k.id)))
                add(k)
                addAll(drawn(js, target, InjectAt.After(k.id)))
            }
            addAll(drawn(js, target, InjectAt.Last))
        }
        fun same(kids: List<Block>, b: Block) = kids.size == b.children.size && kids.indices.all { kids[it] === b.children[it] }
        /** The injections drawn in [b]'s subtree, which a replace of [b] drops. */
        fun injectedIn(b: Block): List<NodeInjection> = b.children.flatMap(::injectedIn) + injectInto[b.id].orEmpty()

        fun resolveNode(b: Block): Block {
            val kids = b.children.map(::resolveNode)
            val js = injectInto[b.id].orEmpty()
            val withInjected = if (js.isEmpty()) kids else inject(b.id, kids, js)
            val rebuilt = when {
                same(withInjected, b) -> b
                js.isEmpty() -> b.withChildren(kids)
                else -> try {
                    b.withChildren(withInjected)
                } catch (e: BlockRefusedException) {
                    js.forEach { problems += "${it.module}: injection ${it.name} into ${b.id} (${it.at}) refused: ${b.id} takes no children (${e.message})" }
                    injectInto.remove(b.id)
                    if (same(kids, b)) b else b.withChildren(kids)
                }
            }
            val own = byNode[b.id].orEmpty()
            own.lastOrNull { it.mode == OverrideModes.REPLACE }?.let { r ->
                // Each dropped injection is reported once, by the lowest replace that drops it.
                injectedIn(b).forEach { j ->
                    problems += "${j.module}: injection ${j.name} into ${j.target} not drawn: ${b.id} is replaced by ${r.module}"
                    injectInto.remove(j.target)
                }
            }
            return own.fold(rebuilt) { inner, o -> OverrideBlock(o.module, o.mode, inner, o.override, o.community) }
        }
        return Resolved(resolveNode(root), problems)
    }
}
