package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.GroupBlock
import com.superiorsoftllc.infantry.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
import com.superiorsoftllc.infantry.ui.shell.blocks.SETTINGS_SLOT
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** S4 (drop-in path): how core modules fill the shell's empty slots, and what is refused. */
class ShellFillsTest {
    private val header = "infantry.shell.header"

    private fun Block.find(id: String): Block? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

    private fun module(name: String, fills: Map<String, Block>, declared: List<String> = fills.keys.toList(), tier: Tier = Tier.CORE) =
        InfantryModule(ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", fills = declared.map { com.superiorsoftllc.infantry.plugin.api.FillDeclaration(it) }), tier = tier, fills = fills)

    @Test fun `with no module the shell draws its empty tree, every slot empty`() {
        val f = TabFactory(emptyList())
        assertSame(CoreShell.root, f.baseRoot)
        assertSame(CoreShell.root, f.shellRoot)
        assertEquals(emptyList(), f.tabTreeRoots)
        CoreShell.slots.forEach { assertNull(assertIs<PlaceholderBlock>(f.baseRoot.find(it), it).default, it) }
        assertEquals(emptyList(), f.problems)
    }

    @Test fun `a core module's fill is the slot's node itself, before overrides`() {
        val mine = GroupBlock(header)
        val f = TabFactory(listOf(module("Acme_Ui", mapOf(header to mine))))
        assertSame(mine, f.baseRoot.find(header))
        assertSame(mine, f.shellRoot.find(header))
        // The locked dialogs beside it are the core's own.
        assertSame(CoreShell.root.find("infantry.shell.connect"), f.baseRoot.find("infantry.shell.connect"))
        assertEquals(emptyList(), f.problems)
    }

    @Test fun `a community fill, an undeclared one, a non-slot, a wrong id and a second fill are refused`() {
        val f = TabFactory(
            listOf(
                module("Acme_Community", mapOf(header to GroupBlock(header)), tier = Tier.COMMUNITY),
                module("Acme_Undeclared", mapOf(header to GroupBlock(header)), declared = emptyList()),
                module("Acme_Locked", mapOf("infantry.shell.connect" to GroupBlock("infantry.shell.connect"))),
                module("Acme_WrongId", mapOf(header to GroupBlock("acme.header"))),
                module("Acme_First", mapOf(header to GroupBlock(header))),
                module("Acme_Second", mapOf(header to GroupBlock(header))),
                module("Acme_Missing", emptyMap(), declared = listOf("infantry.shell.footer")),
            ),
        )
        val p = f.problems
        assertTrue("Acme_Community: fill of $header refused: only a core module fills a shell slot" in p, p.toString())
        assertTrue("Acme_Undeclared: fill of $header is not declared in its module.json; refused" in p, p.toString())
        assertTrue("Acme_Locked: fill of infantry.shell.connect refused: it is not an empty shell slot" in p, p.toString())
        assertTrue("Acme_WrongId: fill of $header refused: its block's id is acme.header" in p, p.toString())
        assertTrue("Acme_Second: fill of $header skipped: already filled" in p, p.toString())
        assertTrue("Acme_Missing: fill of infantry.shell.footer is declared but not registered; skipped" in p, p.toString())
        assertEquals("Acme_First", f.modules.first { it.fills[header] === f.baseRoot.find(header) }.manifest.name)
    }

    @Test fun `Infantry_Roles, Infantry_Board, Infantry_Alerts and Infantry_Processes fill every slot and add no tab tree`() {
        // RUNTIME-14/15: the official Infantry_Roles and Infantry_Board (their jars on desktop) fill infantry.shell.roles and
        // infantry.shell.board; RUNTIME-16: Infantry_Alerts the sound and voice slots;
        // RUNTIME-18: Infantry_Processes the status bar's processes badge; RUNTIME-26: no core module is left.
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.infantry.plugins.official.roles.InfantryRoles + com.superiorsoftllc.infantry.plugins.official.board.InfantryBoard + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat + com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed + com.superiorsoftllc.infantry.plugins.official.approvals.InfantryApprovals + com.superiorsoftllc.infantry.plugins.official.assistant.InfantryAssistant + com.superiorsoftllc.infantry.plugins.official.changes.InfantryChanges + com.superiorsoftllc.infantry.plugins.official.usage.InfantryUsage + com.superiorsoftllc.infantry.plugins.official.attention.InfantryAttention + com.superiorsoftllc.infantry.plugins.official.alerts.InfantryAlerts + com.superiorsoftllc.infantry.plugins.official.appearance.InfantryAppearance + com.superiorsoftllc.infantry.plugins.official.processes.InfantryProcesses)
        (CoreShell.slots - SETTINGS_SLOT).forEach { id -> assertTrue(f.baseRoot.find(id) !is PlaceholderBlock, "$id is still empty") }
        assertEquals("Infantry_Roles", f.fillModules["infantry.shell.roles"])
        assertEquals("Infantry_Board", f.fillModules["infantry.shell.board"])
        assertEquals("Infantry_Alerts", f.fillModules["infantry.shell.sound"])
        assertEquals("Infantry_Alerts", f.fillModules["infantry.shell.voice"])
        assertEquals("Infantry_Processes", f.fillModules["infantry.shell.footer.processesBadge"])
        assertEquals("Infantry_Header", f.fillModules["infantry.shell.header"]) // RUNTIME-20
        // RUNTIME-6/7/8: Infantry_Git, Infantry_Run, Infantry_Notes and Infantry_Changes (official jars) register their own trees.
        assertEquals(listOf("infantry.shell.changes"), f.tabTreeRoots.map { it.id }) // RUNTIME-25: Infantry_Changes (fills the diff slot) brings its own tab tree
        assertEquals("Infantry_Changes", f.fillModules["infantry.shell.diff"])
        assertEquals(emptyList(), f.problems)
    }

    private fun settingsModule(name: String, block: Block, replaces: String? = null) =
        InfantryModule(ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", fills = listOf(FillDeclaration(SETTINGS_SLOT, replaces = replaces))), tier = Tier.CORE, fills = mapOf(SETTINGS_SLOT to block))

    @Test fun `CONFIG-10b a throwaway settings plugin replaces the default surface and the default returns without it`() {
        val default = GroupBlock(SETTINGS_SLOT)
        val acme = GroupBlock(SETTINGS_SLOT)
        // Without the replacement the default fill takes the slot.
        val alone = TabFactory(listOf(settingsModule("Infantry_Settings", default)))
        assertSame(default, alone.baseRoot.find(SETTINGS_SLOT))
        assertEquals(emptyList(), alone.problems)
        // With it, fills[].replaces hands the slot over; the default stays loaded but unused.
        val both = TabFactory(listOf(settingsModule("Infantry_Settings", default), settingsModule("Acme_Settings", acme, replaces = "Infantry_Settings")))
        assertSame(acme, both.baseRoot.find(SETTINGS_SLOT))
        assertEquals("Acme_Settings", both.fillModules[SETTINGS_SLOT])
        assertTrue(both.modules.any { it.manifest.name == "Infantry_Settings" })
        // Order does not matter, and no replaces key keeps first-wins.
        val reversed = TabFactory(listOf(settingsModule("Acme_Settings", acme, replaces = "Infantry_Settings"), settingsModule("Infantry_Settings", default)))
        assertSame(acme, reversed.baseRoot.find(SETTINGS_SLOT))
        val plain = TabFactory(listOf(settingsModule("Infantry_Settings", default), settingsModule("Acme_Settings", acme)))
        assertSame(default, plain.baseRoot.find(SETTINGS_SLOT))
        // The empty slot draws the generated default (no fill at all).
        assertEquals(null, assertIs<PlaceholderBlock>(TabFactory(emptyList()).baseRoot.find(SETTINGS_SLOT)).default)
    }

    @Test fun `the compiled modules are found through the service loader`() {
        val core = CoreModules.all.map { it.manifest.name }
        val found = CompiledModules.all.map { it.manifest.name }
        assertEquals(core, found.take(core.size))
        // RUNTIME-4..18: then the official modules this test class path carries, through their jars' ServiceLoader entries.
        assertEquals(
            setOf("Infantry_Agents", "Infantry_Settings", "Infantry_Git", "Infantry_Run", "Infantry_Notes", "Infantry_Changes", "Infantry_Prompts", "Infantry_Skills",
                "Infantry_Stats", "Infantry_Usage", "Infantry_Epics", "Infantry_Review", "Infantry_Pods", "Infantry_Profiles", "Infantry_Triggers", "Infantry_Attention", "Infantry_Roles", "Infantry_Board", "Infantry_Alerts", "Infantry_Appearance", "Infantry_Header", "Infantry_Processes", "Infantry_Chat", "Infantry_Approvals", "Infantry_Assistant"),
            found.drop(core.size).toSet(),
        )
    }
}
