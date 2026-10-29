package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.NodeDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GAP-AUDIT-1 (b) `overridable: false`, (c) the who-overrides-what map, (e) glob grants, on the real core tree. */
class OverrideGapsTest {
    private val body = "infantry.shell.body"
    private val nothing = BlockOverride { _, _ -> }

    private fun module(name: String, tier: Tier, shell: List<ShellOverrideDeclaration> = emptyList(), nodes: List<NodeDeclaration> = emptyList()) =
        AgentHqModule(
            ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", shell = shell, nodes = nodes),
            tier = tier, shell = shell.associate { it.name to nothing },
        )

    @Test fun `a core module's overridable false locks the node against every other module`() {
        val locker = module("Acme_Lock", Tier.CORE, nodes = listOf(NodeDeclaration(body, overridable = false)))
        val other = module("Acme_Other", Tier.CORE, shell = listOf(ShellOverrideDeclaration(body, "x", "replace")))
        val f = TabFactory(CoreModules.all + locker + other)
        assertTrue(body in f.locked)
        assertTrue(CoreShell.locked.all { it in f.locked }, "the shell's own locks stay")
        assertTrue(f.problems.any { it.startsWith("Acme_Other: shell override (replace) of $body refused: $body is locked") }, f.problems.joinToString("\n"))
        val row = f.overrideMap.single { it.module == "Acme_Other" }
        assertFalse(row.drawn)
        assertTrue(row.problem!!.contains("is locked"))
    }

    @Test fun `a community module locks only its own nodes`() {
        val c = module("Acme_C", Tier.COMMUNITY, nodes = listOf(NodeDeclaration(body, overridable = false), NodeDeclaration("Acme_C.panel", overridable = false)))
        val f = TabFactory(CoreModules.all + c)
        assertFalse(body in f.locked)
        assertTrue("Acme_C.panel" in f.locked)
        assertTrue(f.problems.any { it.contains("overridable:false on $body refused") })
    }

    @Test fun `about is in the locked trust kernel`() {
        assertTrue("infantry.shell.config.about" in CoreShell.locked)
    }

    @Test fun `the override map resolves a selector to the full path and says it is drawn`() {
        val m = module("Acme_Banner", Tier.CORE, shell = listOf(ShellOverrideDeclaration("infantry.shell.frame/$body", "b", "before")))
        val row = TabFactory(CoreModules.all + m).overrideMap.single { it.module == m.manifest.name }
        assertEquals(body, row.node)
        assertEquals("infantry.shell.window/infantry.shell.images/infantry.shell.dock/infantry.shell.frame/$body", row.path)
        assertTrue(row.drawn, row.toString())
        assertNull(row.problem)
    }

    @Test fun `an unknown selector is in the map with no path`() {
        val m = module("Acme_Lost", Tier.CORE, shell = listOf(ShellOverrideDeclaration("infantry.shell.nope", "b", "replace")))
        val row = TabFactory(CoreModules.all + m).overrideMap.single { it.module == m.manifest.name }
        assertNull(row.path)
        assertFalse(row.drawn)
        assertNotNull(row.problem)
    }

    @Test fun `a community grant pattern admits a matching selector and refuses others`() {
        val m = module("Acme_Glob", Tier.COMMUNITY, shell = listOf(
            ShellOverrideDeclaration("infantry.shell.frame/$body", "a", "before"),
            ShellOverrideDeclaration("infantry.shell.panes", "b", "wrap"),
        ))
        val f = TabFactory(CoreModules.all + m, allowOverride = { listOf("infantry.shell.frame/*") })
        assertEquals(listOf("infantry.shell.frame/$body"), f.overrideMap.filter { it.module == "Acme_Glob" }.map { it.selector })
        assertTrue(f.problems.any { it.contains("cannot override core shell node infantry.shell.panes") })
    }
}
