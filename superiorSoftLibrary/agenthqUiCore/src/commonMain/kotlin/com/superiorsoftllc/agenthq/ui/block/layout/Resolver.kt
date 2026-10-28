package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.agenthq.ui.LocalCoreTier
import com.superiorsoftllc.agenthq.ui.ModuleTier

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
class NodeOverride(val module: String, val node: String, val mode: String, val override: BlockOverride, val community: Boolean = false)

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
    override fun Render(scope: BlockScope) = if (mode == OverrideModes.WRAP) {
        // S4d: a community wrap draws without the core tier; the node it wraps is drawn back at the tier around it.
        if (community) {
            val outer = LocalCoreTier.current
            ModuleTier(false) { override.content(scope, TierBlock(inner, outer)) }
        } else override.content(scope, inner)
    } else {
        val s = if (inner.wearsHostSurface) BlockScope(scope.modifier.then(LocalHostSurface.current), scope.box, scope.row) else scope
        if (community) ModuleTier(false) { override.content(s, null) } else override.content(s, null)
    }

    override val wearsHostSurface: Boolean get() = inner.wearsHostSurface
}

/** S4d: [inner] drawn at module tier [core] (a community wrap's inner node is the shell's, not the module's). */
private class TierBlock(private val inner: Block, private val core: Boolean) : Block(inner.blockType, inner.id, inner.place, inner.children) {
    @Composable
    override fun Render(scope: BlockScope) = CompositionLocalProvider(LocalCoreTier provides core) { inner.Render(scope) }

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
    override fun Render(scope: BlockScope) = if (community) ModuleTier(false) { content(scope) } else content(scope)
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
    (isCore(j.module) || j.target in allowInject(j.module)).also { ok ->
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

        val injectInto = LinkedHashMap<String, MutableList<NodeInjection>>()
        val taken = HashSet(nodes.keys)
        for (j in injections) {
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
