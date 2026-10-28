package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.GroupBlock
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
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
        AgentHqModule(ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", fills = declared.map { com.superiorsoftllc.agenthq.plugin.api.FillDeclaration(it) }), tier = tier, fills = fills)

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

    @Test fun `Infantry_AppUi, Infantry_Roles, Infantry_Board, Infantry_Alerts and Infantry_Processes fill every slot and add no tab tree`() {
        // RUNTIME-14/15: the official Infantry_Roles and Infantry_Board (their jars on desktop) fill infantry.shell.roles and
        // infantry.shell.board; RUNTIME-16: Infantry_Alerts the sound and voice slots;
        // RUNTIME-18: Infantry_Processes the status bar's processes badge; Infantry_AppUi every other slot.
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.agenthq.plugins.official.roles.InfantryRoles + com.superiorsoftllc.agenthq.plugins.official.board.InfantryBoard + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + com.superiorsoftllc.agenthq.plugins.official.approvals.InfantryApprovals + com.superiorsoftllc.agenthq.plugins.official.usage.InfantryUsage + com.superiorsoftllc.agenthq.plugins.official.attention.InfantryAttention + com.superiorsoftllc.agenthq.plugins.official.alerts.InfantryAlerts + com.superiorsoftllc.agenthq.plugins.official.processes.InfantryProcesses)
        CoreShell.slots.forEach { id -> assertTrue(f.baseRoot.find(id) !is PlaceholderBlock, "$id is still empty") }
        assertEquals("Infantry_Roles", f.fillModules["infantry.shell.roles"])
        assertEquals("Infantry_Board", f.fillModules["infantry.shell.board"])
        assertEquals("Infantry_Alerts", f.fillModules["infantry.shell.sound"])
        assertEquals("Infantry_Alerts", f.fillModules["infantry.shell.voice"])
        assertEquals("Infantry_Processes", f.fillModules["infantry.shell.footer.processesBadge"])
        assertEquals("Infantry_Header", f.fillModules["infantry.shell.header"]) // RUNTIME-20
        // RUNTIME-6/7/8: Infantry_Git, Infantry_Run, Infantry_Notes and Infantry_Changes (official jars) register their own trees.
        assertEquals(emptyList<String>(), f.tabTreeRoots.map { it.id })
        assertEquals(emptyList(), f.problems)
    }

    @Test fun `the compiled modules are found through the service loader`() {
        val core = CoreModules.all.map { it.manifest.name }
        val found = CompiledModules.all.map { it.manifest.name }
        assertEquals(core, found.take(core.size))
        // RUNTIME-4..18: then the official modules this test class path carries, through their jars' ServiceLoader entries.
        assertEquals(
            setOf("Infantry_Agents", "Infantry_Settings", "Infantry_Git", "Infantry_Run", "Infantry_Notes", "Infantry_Changes", "Infantry_Prompts", "Infantry_Skills",
                "Infantry_Stats", "Infantry_Usage", "Infantry_Epics", "Infantry_Review", "Infantry_Pods", "Infantry_Profiles", "Infantry_Triggers", "Infantry_Attention", "Infantry_Roles", "Infantry_Board", "Infantry_Alerts", "Infantry_Header", "Infantry_Processes"),
            found.drop(core.size).toSet(),
        )
    }
}
