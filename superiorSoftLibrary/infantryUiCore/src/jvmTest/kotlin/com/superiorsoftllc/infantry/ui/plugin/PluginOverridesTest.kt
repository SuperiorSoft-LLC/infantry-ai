// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.NodeSelector
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** OVERRIDE-1 (docs/PLUGIN-OVERRIDES.md): selectors, modes, priority, trust and the compile-time path, on the real core tree. */
class PluginOverridesTest {
    private val body = "infantry.shell.body"
    private val path = "infantry.shell.frame/$body"
    private val nothing = BlockOverride { _, _ -> }

    private fun module(name: String, tier: Tier, vararg decls: ShellOverrideDeclaration) = InfantryModule(
        ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", shell = decls.toList()),
        tier = tier, shell = decls.associate { it.name to nothing },
    )

    private fun core(name: String, vararg decls: ShellOverrideDeclaration) = module(name, Tier.CORE, *decls)

    /** The compile-time path: no loader list, the overrides come off the manifests. */
    private fun factory(vararg ms: InfantryModule, allow: Set<String> = emptySet()) =
        TabFactory(CoreModules.all + ms, allowOverride = { allow })

    private fun Block.find(id: String): Block? =
        if (this.id == id) this else (children + listOfNotNull((this as? OverrideBlock)?.inner)).firstNotNullOfOrNull { it.find(id) }

    private fun TabFactory.frameKids() = shellRoot.find("infantry.shell.frame")!!.let { f -> (f as? OverrideBlock)?.inner ?: f }.children.map { it.id }

    @Test fun `a path selector and a plain id address the same block`() {
        val root = CoreModules.factory.baseRoot
        assertEquals(body, NodeSelector.find(root, body))
        assertEquals(body, NodeSelector.find(root, path))
        assertEquals(body, NodeSelector.find(root, "infantry.shell#$body"))
        assertEquals(body, NodeSelector.find(root, "infantry.shell.window/infantry.shell.images/infantry.shell.dock/infantry.shell.frame/$body"))
        assertNull(NodeSelector.find(root, "infantry.shell.panes/$body"), "body is not a direct child of panes")
        assertNull(NodeSelector.find(root, "infantry.shell.nope"))
    }

    @Test fun `an index segment addresses the nth child`() {
        val root = CoreModules.factory.baseRoot
        val frame = root.find("infantry.shell.frame")!!
        assertEquals(frame.children[1].id, NodeSelector.find(root, "infantry.shell.frame/[1]"))
        assertNull(NodeSelector.find(root, "infantry.shell.frame/[99]"))
    }

    @Test fun `replace by path draws the plugin block in the original place and by id the same`() {
        for (sel in listOf(path, body)) {
            val f = factory(core("Acme_A", ShellOverrideDeclaration(sel, "x")))
            val node = assertIs<OverrideBlock>(f.shellRoot.find(body))
            assertEquals("Acme_A", node.module)
            assertEquals(emptyList(), f.problems)
        }
    }

    @Test fun `before and after put the block beside the target and leave the original`() {
        val f = factory(core("Acme_A", ShellOverrideDeclaration(path, "b", OverrideModes.BEFORE), ShellOverrideDeclaration(path, "a", OverrideModes.AFTER)))
        val kids = f.frameKids()
        val i = kids.indexOf(body)
        assertEquals("Acme_A.before-$body", kids[i - 1])
        assertEquals("Acme_A.after-$body", kids[i + 1])
        assertSame(f.baseRoot.find(body), f.shellRoot.find(body), "the target itself is untouched")
        assertEquals(emptyList(), f.problems)
    }

    @Test fun `removing the plugin restores the original layout`() {
        val with = factory(core("Acme_A", ShellOverrideDeclaration(path, "b", OverrideModes.BEFORE), ShellOverrideDeclaration(body, "r")))
        assertTrue(with.frameKids().size > factory().frameKids().size)
        val without = factory()
        assertSame(without.baseRoot, without.shellRoot)
    }

    @Test fun `two replacers resolve by priority then load order and the loser is logged`() {
        val hi = core("Acme_Hi", ShellOverrideDeclaration(body, "x", priority = 5))
        val lo = core("Acme_Lo", ShellOverrideDeclaration(body, "x", priority = 1))
        // loaded first, higher priority: still wins over the later, lower one
        val f = factory(hi, lo)
        assertEquals("Acme_Hi", assertIs<OverrideBlock>(f.shellRoot.find(body)).module)
        assertTrue(f.problems.any { "Acme_Hi: replaces $body, overriding Acme_Lo" in it }, f.problems.toString())
        // same priority: the later module wins
        val tie = factory(core("Acme_One", ShellOverrideDeclaration(body, "x")), core("Acme_Two", ShellOverrideDeclaration(body, "x")))
        assertEquals("Acme_Two", assertIs<OverrideBlock>(tie.shellRoot.find(body)).module)
    }

    @Test fun `several before blocks all render, by priority then load order`() {
        val f = factory(
            core("Acme_Low", ShellOverrideDeclaration(body, "b", OverrideModes.BEFORE, priority = 1)),
            core("Acme_High", ShellOverrideDeclaration(body, "b", OverrideModes.BEFORE, priority = 9)),
            core("Acme_Mid", ShellOverrideDeclaration(body, "b", OverrideModes.BEFORE, priority = 1)),
        )
        val kids = f.frameKids()
        val i = kids.indexOf(body)
        assertEquals(listOf("Acme_High.before-$body", "Acme_Low.before-$body", "Acme_Mid.before-$body"), kids.subList(i - 3, i))
    }

    @Test fun `wrap draws around the original and keeps it`() {
        val f = factory(core("Acme_W", ShellOverrideDeclaration(path, "w", OverrideModes.WRAP)))
        val node = assertIs<OverrideBlock>(f.shellRoot.find(body))
        assertEquals(OverrideModes.WRAP, node.mode)
        assertSame(f.baseRoot.find(body), node.inner)
    }

    @Test fun `a community module without a grant is refused and with a grant admitted`() {
        val c = module("Acme_C", Tier.COMMUNITY, ShellOverrideDeclaration(body, "x"))
        val refused = factory(c)
        assertSame(refused.baseRoot, refused.shellRoot)
        assertTrue(refused.problems.any { "Acme_C" in it && "refused" in it }, refused.problems.toString())
        assertIs<OverrideBlock>(factory(c, allow = setOf(body)).shellRoot.find(body))
    }

    @Test fun `a locked block cannot be overridden nor have a block placed beside it`() {
        val f = factory(core("Acme_L",
            ShellOverrideDeclaration("infantry.shell.config", "r"),
            ShellOverrideDeclaration("infantry.shell.config", "b", OverrideModes.BEFORE),
            ShellOverrideDeclaration("infantry.shell.window/infantry.shell.images", "a", OverrideModes.AFTER)))
        assertSame(f.baseRoot, f.shellRoot)
        assertEquals(3, f.problems.count { "refused" in it && "locked" in it }, f.problems.toString())
    }

    @Test fun `the demo set's override wins on the compile-time path and the live set does not have it`() {
        val demo = core("Demo_Banner", ShellOverrideDeclaration(path, "banner", OverrideModes.BEFORE, priority = 10))
        val live = factory()
        val withDemo = factory(demo)
        assertFalse("Demo_Banner.before-$body" in live.frameKids())
        assertTrue("Demo_Banner.before-$body" in withDemo.frameKids())
        assertSame(live.baseRoot.find(body), withDemo.shellRoot.find(body), "the production block is still there")
        // a demo replace of a live module's block wins over the live module (demo is listed after live)
        val liveBar = core("Live_Bar", ShellOverrideDeclaration(body, "x"))
        val demoBar = core("Demo_Bar", ShellOverrideDeclaration(body, "x"))
        assertEquals("Live_Bar", assertIs<OverrideBlock>(factory(liveBar).shellRoot.find(body)).module)
        assertEquals("Demo_Bar", assertIs<OverrideBlock>(factory(liveBar, demoBar).shellRoot.find(body)).module)
    }

    @Test fun `the resolver source names no plugin`() {
        val dir = File("src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/block/layout")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (f in files) assertFalse(Regex("(Infantry|Demo|Acme)_[A-Z]").containsMatchIn(f.readText()), "${f.name} names a plugin")
    }
}
