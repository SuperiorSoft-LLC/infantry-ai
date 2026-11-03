package com.superiorsoftllc.infantry.ui.block.layout

import com.superiorsoftllc.infantry.plugins.core.CoreModules
import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BLOCKS-INJECTORS J1 (docs/BLOCKS-INJECTORS.md section 2): injections in [BlockResolver], on plain trees. */
class BlockInjectorTest {
    private class Leaf(id: String) : Block("leaf", id) {
        @Composable override fun Render(scope: BlockScope) = Unit
    }

    private val a = Leaf("t.a")
    private val b = Leaf("t.b")
    private val bar = RowBlock("t.bar", children = listOf(a, b))
    private val lockedRow = RowBlock("t.locked", children = listOf(Leaf("t.locked.x")))
    private val root = StackBlock("t", children = listOf(bar, lockedRow))
    private val locked = setOf("t.locked")
    private val nothing = BlockInjection { }

    private fun inj(module: String, name: String, target: String = "t.bar", at: InjectAt = InjectAt.Last, order: Int = 0, load: Int = 0) =
        NodeInjection(module, name, target, at, order, load, nothing)

    private fun resolve(vararg js: NodeInjection, overrides: List<NodeOverride> = emptyList()) =
        BlockResolver.resolve(root, overrides, locked, "t", js.toList())

    private fun Block.find(id: String): Block? =
        if (this.id == id) this else (children + listOfNotNull((this as? OverrideBlock)?.inner)).firstNotNullOfOrNull { it.find(id) }

    private fun Resolved.barIds() = root.find("t.bar")!!.let { (it as? OverrideBlock)?.inner ?: it }.children.map { it.id }

    @Test fun `zero injections and zero overrides give the root itself`() {
        assertSame(root, BlockResolver.resolve(root, emptyList(), locked, "t", emptyList()).root)
        val core = BlockResolver.resolve(CoreShell.root, emptyList(), CoreShell.locked)
        assertSame(CoreShell.root, core.root)
        assertEquals(emptyList(), core.problems)
    }

    @Test fun `first and last go around the core children`() {
        val r = resolve(inj("m", "end"), inj("m", "start", at = InjectAt.First))
        assertEquals(listOf("m.start", "t.a", "t.b", "m.end"), r.barIds())
        assertEquals(emptyList(), r.problems)
        val drawn = assertIs<InjectedBlock>(r.root.find("m.end"))
        assertEquals("m", drawn.module)
        // Only the path down to the target is rebuilt; a sibling subtree and the core children stay the same objects.
        assertSame(lockedRow, r.root.find("t.locked"))
        assertSame(a, r.root.find("t.a"))
    }

    @Test fun `before and after sit next to their anchor`() {
        val r = resolve(inj("m", "x", at = InjectAt.After("t.a")), inj("m", "y", at = InjectAt.Before("t.a")), inj("m", "z", at = InjectAt.Before("t.b")))
        assertEquals(listOf("m.y", "t.a", "m.x", "m.z", "t.b"), r.barIds())
    }

    @Test fun `one anchor sorts by order, then load order, then name, and a tie writes a line`() {
        val r = resolve(
            inj("late", "q", order = 0, load = 2),
            inj("early", "q", order = 0, load = 1),
            inj("first", "q", order = -1, load = 9),
            inj("early", "a", order = 0, load = 1),
        )
        assertEquals(listOf("t.a", "t.b", "first.q", "early.a", "early.q", "late.q"), r.barIds())
        assertEquals(2, r.problems.size, r.problems.toString())
        assertTrue(r.problems.all { "has the same order 0" in it }, r.problems.toString())
        // Swapping the load order changes only the ties.
        val swapped = resolve(inj("late", "q", load = 1), inj("early", "q", load = 2), inj("first", "q", order = -1, load = 9), inj("early", "a", load = 2))
        assertEquals(listOf("t.a", "t.b", "first.q", "late.q", "early.a", "early.q"), swapped.barIds())
    }

    @Test fun `a community module needs allowInject for the target, a core module does not`() {
        val problems = mutableListOf<String>()
        val js = listOf(inj("Core_A", "x"), inj("Acme_B", "y"), inj("Acme_C", "z"))
        val admitted = admitInjections(js, isCore = { it.startsWith("Core_") }, allowInject = { if (it == "Acme_C") listOf("t.bar") else emptyList() }, problems)
        assertEquals(listOf("Core_A.x", "Acme_C.z"), admitted.map { it.id })
        assertEquals(
            listOf("Acme_B: injection y into t.bar refused: a community module cannot inject into t.bar unless modules.json allowInject lists it"),
            problems,
        )
    }

    @Test fun `a locked target is refused, the tree is the root itself`() {
        val r = resolve(inj("m", "x", target = "t.locked"))
        assertSame(root, r.root)
        assertEquals(listOf("m: injection x into t.locked (last) refused: t.locked is locked"), r.problems)
    }

    @Test fun `no core node takes an injection while it is locked`() {
        CoreShell.locked.forEach { id ->
            val r = BlockResolver.resolve(CoreShell.root, emptyList(), CoreShell.locked, injections = listOf(NodeInjection("m", "x", id, injection = nothing)))
            assertSame(CoreShell.root, r.root, id)
            assertEquals(listOf("m: injection x into $id (last) refused: $id is locked"), r.problems)
        }
    }

    /** SHELL-EMPTY-DROPIN S1: a placeholder takes injections among its default's children; a community module needs allowInject. */
    @Test fun `the placeholders take an injection last, drawn by their default, and a community module needs allowInject`() {
        CoreShell.placeholders.forEach { id ->
            val r = BlockResolver.resolve(CoreShell.root, emptyList(), CoreShell.locked, injections = listOf(NodeInjection("m", "x", id, injection = nothing)))
            assertEquals(emptyList(), r.problems, id)
            val p = assertIs<PlaceholderBlock>(r.root.find(id), id)
            assertEquals(CoreShell.root.find(id)!!.children.map { it.id } + "m.x", p.children.map { it.id }, id)
            assertEquals(CoreShell.root.find(id)!!.place, p.place, id)
            assertSame(p.children, p.default!!.children, id)
        }
        val problems = mutableListOf<String>()
        val js = CoreShell.placeholders.map { NodeInjection("Acme_Ui", it.substringAfterLast('.'), it, injection = nothing) }
        assertEquals(listOf("infantry.shell.panes"), admitInjections(js, isCore = { false }, allowInject = { listOf("infantry.shell.panes") }, problems).map { it.target })
        assertEquals(3, problems.size, problems.toString())
    }

    @Test fun `an empty placeholder draws nothing and an injection into it goes into a group default`() {
        val slot = PlaceholderBlock("t.slot")
        val tree = StackBlock("t", children = listOf(slot))
        val r = BlockResolver.resolve(tree, emptyList(), emptySet(), "t", listOf(NodeInjection("m", "x", "t.slot", injection = nothing)))
        val p = assertIs<PlaceholderBlock>(r.root.find("t.slot"))
        assertIs<GroupBlock>(p.default)
        assertEquals(listOf("m.x"), p.children.map { it.id })
        assertNull(slot.default)
        assertEquals(emptyList(), slot.children)
    }

    @Test fun `on the core tree the footer takes an injection last`() {
        val footer = "infantry.shell.footer"
        val r = BlockResolver.resolve(
            CoreModules.factory.baseRoot, emptyList(), CoreShell.locked,
            injections = listOf(NodeInjection("m", "chip", footer, injection = nothing)),
        )
        assertEquals(CoreModules.factory.baseRoot.find(footer)!!.children.map { it.id } + "m.chip", r.root.find(footer)!!.children.map { it.id })
        // RUNTIME-26: the board header lives in the official Infantry_Board's tree, not in the empty shell's.
        assertEquals(emptyList(), r.problems)
    }

    @Test fun `a missing target draws nothing and writes one line`() {
        val r = resolve(inj("m", "x", target = "t.nope"))
        assertSame(root, r.root)
        assertEquals(listOf("m: injection x into t.nope (last) ignored: t has no node t.nope"), r.problems)
    }

    @Test fun `a leaf target is refused without a throw`() {
        val r = resolve(inj("m", "x", target = "t.a"), inj("m", "y"))
        assertNull(r.root.find("m.x"))
        assertEquals(listOf("t.a", "t.b", "m.y"), r.barIds())
        assertEquals(1, r.problems.size, r.problems.toString())
        assertTrue(r.problems.single().startsWith("m: injection x into t.a (last) refused: t.a takes no children"), r.problems.toString())
        // Alone, the refusal leaves the root itself.
        assertSame(root, resolve(inj("m", "x", target = "t.a")).root)
    }

    @Test fun `an anchor that is not a direct child is ignored with a line`() {
        val r = resolve(inj("m", "x", at = InjectAt.After("t.locked.x")), inj("m", "y", target = "t", at = InjectAt.Before("t.a")))
        assertSame(root, r.root)
        assertEquals(
            listOf("m: injection x into t.bar (after:t.locked.x) ignored: t.bar has no child t.locked.x", "m: injection y into t (before:t.a) ignored: t has no child t.a"),
            r.problems,
        )
    }

    @Test fun `a duplicate id, an earlier injection or a core node, is refused`() {
        val r = resolve(inj("m", "x"), inj("m", "x", at = InjectAt.First), inj("t", "a"))
        assertEquals(listOf("t.a", "t.b", "m.x"), r.barIds())
        assertEquals(listOf("m: injection x into t.bar (first) refused: duplicate id m.x", "t: injection a into t.bar (last) refused: duplicate id t.a"), r.problems)
    }

    @Test fun `a wrap of the target keeps the injected children as inner`() {
        val wrap = NodeOverride("w", "t.bar", OverrideModes.WRAP, BlockOverride { _, _ -> })
        val r = resolve(inj("m", "x"), overrides = listOf(wrap))
        val node = assertIs<OverrideBlock>(r.root.find("t.bar"))
        assertEquals(listOf("t.a", "t.b", "m.x"), node.inner.children.map { it.id })
        assertEquals(emptyList(), r.problems)
    }

    @Test fun `a replace of the target or of a node above it drops the injection with a line`() {
        val replace = NodeOverride("r", "t.bar", OverrideModes.REPLACE, BlockOverride { _, _ -> })
        val r = resolve(inj("m", "x"), overrides = listOf(replace))
        assertIs<OverrideBlock>(r.root.find("t.bar"))
        assertEquals(listOf("m: injection x into t.bar not drawn: t.bar is replaced by r"), r.problems)

        val above = NodeOverride("r", "t", OverrideModes.REPLACE, BlockOverride { _, _ -> })
        val nested = BlockResolver.resolve(StackBlock("top", children = listOf(root)), listOf(replace, above), emptySet(), "top", listOf(inj("m", "x")))
        assertEquals(1, nested.problems.count { "not drawn" in it }, nested.problems.toString())
    }

    @Test fun `an anchor child that is overridden still anchors`() {
        val wrapA = NodeOverride("w", "t.a", OverrideModes.WRAP, BlockOverride { _, _ -> })
        val r = resolve(inj("m", "x", at = InjectAt.After("t.a")), overrides = listOf(wrapA))
        assertEquals(listOf("t.a", "m.x", "t.b"), r.barIds())
        assertIs<OverrideBlock>(r.root.find("t.bar")!!.children.first())
    }

    @Test fun `the at grammar parses and prints the module json form`() {
        listOf("first", "last", "before:t.a", "after:t.b").forEach { assertEquals(it, InjectAt.parse(it).toString()) }
        assertEquals(InjectAt.After("t.b"), InjectAt.parse("after:t.b"))
        listOf("", "middle", "before:", "after:", "First").forEach { assertNull(InjectAt.parse(it), it) }
    }
}
