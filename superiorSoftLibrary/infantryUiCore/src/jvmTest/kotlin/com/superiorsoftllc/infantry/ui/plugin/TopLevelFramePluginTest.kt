package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CORE-CLEAN-1 (docs/CORE-CLEAN.md, "Replacing a core concern"): a top-level plugin registered through the normal plugin
 * path supplies its own shell frame, and the factory (what the window draws from) takes it with no problems and no
 * change to core. Nothing here is special-cased: core holds the abstraction, the plugin holds the frame.
 */
class TopLevelFramePluginTest {
    private val ran = mutableListOf<String>()
    private val ownFrame = BlockOverride { scope, _ -> ran += "frame:${scope.hashCode() != 0}" }

    private val plugin = object : UiPlugin {
        override fun register(registrar: UiRegistrar) = registrar.shell("ownFrame", ownFrame)
    }

    private val manifest = ModuleManifest(
        "Acme_OwnCore", "1.0.0", author = "Acme", license = "NOASSERTION",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.frame", "ownFrame")),
    )

    private fun Block.find(id: String): Block? =
        if (this.id == id) this else (children + listOfNotNull((this as? OverrideBlock)?.inner)).firstNotNullOfOrNull { it.find(id) }

    @Test fun `a top-level plugin supplies its own shell frame and the factory draws it`() {
        val module = registerModule(manifest, Tier.COMMUNITY, NoPluginServices, plugin)
        assertEquals(setOf("ownFrame"), module.shell.keys)
        val accepted = manifest.shell.map { AcceptedShellOverride(manifest.name, it.nodeId, it.name, it.mode) }
        val f = TabFactory(CoreModules.all + module, shellOverrides = accepted)
        assertEquals(emptyList(), f.problems)
        val frame = assertIs<OverrideBlock>(f.shellRoot.find("infantry.shell.frame"))
        assertEquals(OverrideModes.REPLACE, frame.mode)
        assertEquals("Acme_OwnCore", frame.module)
        assertTrue(frame.wearsHostSurface, "the replacing frame gets the host surface")
        assertNotNull(f.shellRoot.find("infantry.shell.windowLayer"), "the locked dialog layer stays core's")
    }
}
