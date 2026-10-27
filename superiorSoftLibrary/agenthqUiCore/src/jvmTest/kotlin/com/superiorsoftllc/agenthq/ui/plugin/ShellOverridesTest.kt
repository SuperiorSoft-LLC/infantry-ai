package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideBlock
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** BLOCKS-LAYOUT L5: how the factory puts module overrides into the core shell tree (by node id). */
class ShellOverridesTest {
    private val footer = "infantry.shell.footer"
    private val nothing = BlockOverride { _, _ -> }

    private fun module(name: String, vararg decls: ShellOverrideDeclaration, registered: Set<String> = decls.map { it.name }.toSet()) = AgentHqModule(
        ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", shell = decls.toList()),
        tier = Tier.COMMUNITY, shell = registered.associateWith { nothing },
    )

    private fun accepted(vararg ms: AgentHqModule) =
        ms.flatMap { m -> m.manifest.shell.map { AcceptedShellOverride(m.manifest.name, it.nodeId, it.name, it.mode) } }

    private fun factory(vararg ms: AgentHqModule) = TabFactory(CoreModules.all + ms, shellOverrides = accepted(*ms))

    private fun Block.find(id: String): Block? =
        if (this.id == id) this else (children + listOfNotNull((this as? OverrideBlock)?.inner)).firstNotNullOfOrNull { it.find(id) }

    @Test fun `with no override the window draws the core tree itself`() {
        val f = TabFactory(CoreModules.all)
        assertSame(CoreShell.root, f.shellRoot)
        assertEquals(emptyList(), f.problems)
    }

    @Test fun `replace puts the module block in the node place, with the node id and place`() {
        val f = factory(module("Acme_Bar", ShellOverrideDeclaration(footer, "bar")))
        val node = assertIs<OverrideBlock>(f.shellRoot.find(footer))
        assertEquals(OverrideModes.REPLACE, node.mode)
        assertEquals("Acme_Bar", node.module)
        assertSame(CoreShell.root.find(footer)!!.place, node.place)
        assertEquals(emptyList(), f.problems)
        // Only the path down to the node is rebuilt; a sibling subtree stays the same object.
        assertSame(CoreShell.root.find("infantry.shell.windowLayer"), f.shellRoot.find("infantry.shell.windowLayer"))
    }

    @Test fun `wrap keeps the core block as inner`() {
        val f = factory(module("Acme_Bar", ShellOverrideDeclaration("infantry.shell#infantry.shell.center", "frame", OverrideModes.WRAP)))
        val node = assertIs<OverrideBlock>(f.shellRoot.find("infantry.shell.center"))
        assertEquals(OverrideModes.WRAP, node.mode)
        assertSame(CoreShell.root.find("infantry.shell.center"), node.inner)
    }

    @Test fun `a locked node refuses replace and wrap with the reason`() {
        val f = factory(
            module(
                "Acme_Bar",
                ShellOverrideDeclaration("infantry.shell.connect", "token"),
                ShellOverrideDeclaration("infantry.shell.dock", "dock", OverrideModes.WRAP),
            ),
        )
        assertSame(CoreShell.root, f.shellRoot)
        assertTrue("Acme_Bar: shell override (replace) of infantry.shell.connect refused: infantry.shell.connect is locked" in f.problems, f.problems.toString())
        assertTrue(f.problems.any { "(wrap) of infantry.shell.dock refused: infantry.shell.dock is locked" in it }, f.problems.toString())
    }

    /** SHELL-EMPTY-DROPIN S1: frame, body, panes and windowLayer are placeholders, open slots with a core default. */
    @Test fun `a placeholder with no override draws its core default, with the node id, place and children`() {
        CoreShell.placeholders.forEach { id ->
            val p = assertIs<PlaceholderBlock>(CoreShell.root.find(id), id)
            val default = assertNotNull(p.default, id)
            assertNotEquals("placeholder", default.blockType, id)
            assertSame(default.children, p.children, id)
            assertTrue(id !in CoreShell.locked, "$id is a placeholder, not locked")
        }
        assertTrue(assertIs<PlaceholderBlock>(CoreShell.root.find("infantry.shell.frame")).wearsHostSurface, "the frame default wears the host surface")
    }

    @Test fun `a module replaces a placeholder with its block, in the placeholder id and place`() {
        listOf("infantry.shell.frame", "infantry.shell.body", "infantry.shell.panes").forEach { id ->
            val f = factory(module("Acme_Slot", ShellOverrideDeclaration(id, "slot")))
            assertEquals(emptyList(), f.problems, id)
            val node = assertIs<OverrideBlock>(f.shellRoot.find(id), id)
            assertEquals(OverrideModes.REPLACE, node.mode)
            assertSame(CoreShell.root.find(id)!!.place, node.place, id)
            assertSame(CoreShell.root.find(id), node.inner, id)
            // A replace of the frame hands the host surface (focus target, key handler) on to the module's block.
            assertEquals(id == "infantry.shell.frame", node.wearsHostSurface, id)
            // The locked dialog layer beside it is untouched.
            assertSame(CoreShell.root.find("infantry.shell.windowLayer"), f.shellRoot.find("infantry.shell.windowLayer"), id)
        }
    }

    @Test fun `the windowLayer placeholder refuses a replace that would remove the locked dialogs in it`() {
        val f = factory(module("Acme_Layer", ShellOverrideDeclaration("infantry.shell.windowLayer", "layer")))
        assertSame(CoreShell.root, f.shellRoot)
        assertEquals(listOf("Acme_Layer: shell override (replace) of infantry.shell.windowLayer refused: it would remove locked node infantry.shell.config"), f.problems)
    }

    @Test fun `every locked id is a node of the core tree`() {
        CoreShell.locked.forEach { assertTrue(CoreShell.root.find(it) != null, "$it is not in the tree") }
    }

    @Test fun `an unknown node id or mode is ignored with a warning`() {
        val f = factory(
            module(
                "Acme_Bar",
                ShellOverrideDeclaration("infantry.shell.nope", "a"),
                ShellOverrideDeclaration(footer, "b", "remove"),
            ),
        )
        assertSame(CoreShell.root, f.shellRoot)
        assertTrue(f.problems.any { "of infantry.shell.nope ignored: infantry.shell has no node infantry.shell.nope" in it }, f.problems.toString())
        assertTrue(f.problems.any { "of $footer ignored: unknown mode 'remove'" in it }, f.problems.toString())
    }

    @Test fun `declared but not registered, and registered but not declared, are both reported`() {
        val f = factory(module("Acme_Bar", ShellOverrideDeclaration(footer, "bar"), registered = setOf("stray")))
        assertSame(CoreShell.root, f.shellRoot)
        assertTrue("Acme_Bar: shell override bar is declared but not registered; skipped" in f.problems, f.problems.toString())
        assertTrue("Acme_Bar: shell override stray is not declared in its module.json; refused" in f.problems, f.problems.toString())
    }

    @Test fun `several overrides apply in load order, wraps nest with the last outermost, the last replace wins`() {
        val a = module("Acme_A", ShellOverrideDeclaration(footer, "w", OverrideModes.WRAP))
        val b = module("Acme_B", ShellOverrideDeclaration(footer, "w", OverrideModes.WRAP))
        val outer = assertIs<OverrideBlock>(factory(a, b).shellRoot.find(footer))
        assertEquals("Acme_B", outer.module)
        val innerWrap = assertIs<OverrideBlock>(outer.inner)
        assertEquals("Acme_A", innerWrap.module)
        assertSame(CoreShell.root.find(footer), innerWrap.inner)

        val r1 = module("Acme_R1", ShellOverrideDeclaration(footer, "r"))
        val r2 = module("Acme_R2", ShellOverrideDeclaration(footer, "r"))
        val f = factory(r1, r2)
        assertEquals("Acme_R2", assertIs<OverrideBlock>(f.shellRoot.find(footer)).module)
        assertTrue("Acme_R2: replaces $footer, overriding Acme_R1" in f.problems, f.problems.toString())
    }

    @Test fun `overrides of a parent and of its child both apply`() {
        val f = factory(
            module(
                "Acme_Bar",
                ShellOverrideDeclaration("$footer.buildStamp", "stamp"),
                ShellOverrideDeclaration(footer, "bar", OverrideModes.WRAP),
            ),
        )
        val bar = assertIs<OverrideBlock>(f.shellRoot.find(footer))
        assertIs<OverrideBlock>(bar.inner.children.single { it.id == "$footer.buildStamp" })
    }
}
