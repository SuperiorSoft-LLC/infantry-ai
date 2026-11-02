package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.roles.InfantryRoles
import com.superiorsoftllc.agenthq.plugins.official.board.InfantryBoard
import com.superiorsoftllc.agenthq.plugins.official.stats.InfantryStats
import com.superiorsoftllc.agenthq.plugins.official.epics.InfantryEpics
import com.superiorsoftllc.agenthq.plugins.official.pods.InfantryPods
import com.superiorsoftllc.agenthq.plugins.official.profiles.InfantryProfiles
import com.superiorsoftllc.agenthq.plugins.official.triggers.InfantryTriggers
import com.superiorsoftllc.agenthq.plugins.official.attention.InfantryAttention
import com.superiorsoftllc.agenthq.plugins.official.review.InfantryReview
import com.superiorsoftllc.agenthq.plugins.official.usage.InfantryUsage
import com.superiorsoftllc.agenthq.plugins.official.changes.InfantryChanges
import com.superiorsoftllc.agenthq.plugins.official.notes.InfantryNotes
import androidx.compose.material3.Text
import com.superiorsoftllc.agenthq.plugin.api.InjectDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.official.git.InfantryGit
import com.superiorsoftllc.agenthq.plugins.official.run.InfantryRun
import com.superiorsoftllc.agenthq.ui.block.layout.BlockInjection
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ChildSource
import com.superiorsoftllc.agenthq.ui.plugin.HostKind
import com.superiorsoftllc.agenthq.ui.plugin.NodeRegistry
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.shell.blocks.SETTINGS_SLOT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1 (f60f4d97, docs/BLOCKS-LAYOUT-DESIGN.md section 6s): the registry read model over what TabFactory resolved. */
class NodeRegistryTest {
    /** RUNTIME-6/7/8: the compiled-in core modules plus the official Notes, Changes, Run and Git (jars on desktop), as the window has them. */
    private val coreAndGit = CoreModules.all + InfantryRoles + InfantryBoard + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + com.superiorsoftllc.agenthq.plugins.official.chatfeed.InfantryChatFeed + com.superiorsoftllc.agenthq.plugins.official.approvals.InfantryApprovals + com.superiorsoftllc.agenthq.plugins.official.assistant.InfantryAssistant + com.superiorsoftllc.agenthq.plugins.official.alerts.InfantryAlerts + com.superiorsoftllc.agenthq.plugins.official.processes.InfantryProcesses + InfantryPods + InfantryProfiles + InfantryTriggers + InfantryAttention + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit

    private fun tab(id: String, order: Int, title: String) =
        TabProvider(id, TabContainers.CENTER, order, { title }, InfantryGit.tabs.single().icon, "center-tab-$id") { Text(title) }

    private val acme = AgentHqModule(
        ModuleManifest("Acme_Tabs", "2.1.0", author = "Acme", license = "NOASSERTION", tabs = listOf(TabDeclaration("acme.tabs", TabContainers.CENTER))),
        tabs = listOf(tab("acme.tabs", 500, "Acme")),
        tier = Tier.COMMUNITY,
    )

    @Test fun `the host nodes are the two tab strips, then every container of the window and the core tab trees`() {
        val shell = "infantry.shell"
        val tree = listOf(
            "window", "images", "dock", "frame", "header", "header.lead", "header.lead.overflow", "body", "panes", "center",
            "center.chat", "center.newAgent", "right", "right.body", "footer", "assistant", "assistant.body", "board", "board.pane",
            "board.pane.columns", "board.card", "board.card.summary", "diff", "windowLayer", "config", "config.settings", "roles",
            "roles.form", "notes", "notes.hits", "notes.list", "changes", "changes.files", "changes.branchFiles",
            "changes.history", "run", "git", // RUNTIME-6/7/8: the official modules' own trees, in load order
        ).map { "$shell.$it" }
        val hosts = NodeRegistry.of(TabFactory(coreAndGit))
        assertEquals(listOf("$shell.center.chat.tabs", "$shell.right.body.strip") + tree, hosts.map { it.id })
        assertEquals(listOf(HostKind.TABS, HostKind.TABS), hosts.take(2).map { it.kind })
        assertEquals(setOf("$shell.window", "$shell.images", "$shell.dock", "$shell.config"), hosts.filter { it.locked }.map { it.id }.toSet())
    }

    @Test fun `a community tab in the centre strip shows its module, version, tier and order, after the core tabs`() {
        val center = NodeRegistry.host(TabFactory(coreAndGit + acme), "infantry.shell.center.chat.tabs")
        assertNotNull(center)
        val row = center.children.single { it.id == "acme.tabs" }
        assertEquals("Acme", row.name)
        assertEquals("Acme_Tabs", row.module)
        assertEquals("2.1.0", row.version)
        assertEquals(Tier.COMMUNITY, row.tier)
        assertEquals(ChildSource.TAB, row.source)
        assertEquals(500, row.order)
        assertTrue(row.enabled)
        assertFalse(row.locked)
        // The Git tab draws a core tab tree; the strip is in drawing order, by order then id.
        val git = center.children.single { it.id == "infantry.git" }
        assertEquals(ChildSource.CORE_TAB_TREE, git.source)
        assertEquals("Infantry_Git", git.module)
        assertEquals(Tier.CORE, git.tier)
        assertEquals("Git", git.name)
        assertEquals(center.children.sortedWith(compareBy({ it.order }, { it.id })), center.children)
    }

    @Test fun `a tab declared by a module that did not load is listed as disabled, without an order`() {
        val statuses = coreAndGit.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) } +
            ModuleStatus("Acme_Tabs", Tier.COMMUNITY, ModuleState.DISABLED, "off in modules.json", acme.manifest)
        val center = NodeRegistry.host(TabFactory(coreAndGit, statuses = statuses), "infantry.shell.center.chat.tabs")!!
        val row = center.children.last()
        assertEquals("acme.tabs", row.id)
        assertFalse(row.enabled)
        assertNull(row.order)
        assertEquals(Tier.COMMUNITY, row.tier)
    }

    @Test fun `a preference that replaces a tab shows as a replace of the module it took over`() {
        val pref = AgentHqModule(
            ModuleManifest(
                "Acme_Git", "1.0.0", author = "Acme", license = "NOASSERTION",
                tabs = listOf(TabDeclaration("acme.git", TabContainers.CENTER)), preferences = listOf(PreferenceDeclaration("infantry.git", "acme.git")),
            ),
            tabs = listOf(tab("acme.git", 900, "Acme Git")),
            tier = Tier.COMMUNITY,
        )
        val f = TabFactory(coreAndGit + pref, preferences = listOf(AcceptedPreference("Acme_Git", "infantry.git", "acme.git")))
        val row = NodeRegistry.host(f, "infantry.shell.center.chat.tabs")!!.children.single { it.id == "infantry.git" }
        assertEquals(ChildSource.REPLACE, row.source)
        assertEquals("Acme_Git", row.module)
        assertEquals("Infantry_Git", row.by)
        assertEquals(Tier.COMMUNITY, row.tier)
    }

    @Test fun `a wrap of a core tab tree and an injection into a container show who did it`() {
        val decl = ShellOverrideDeclaration("infantry.shell.git", "frame", OverrideModes.WRAP)
        val inject = InjectDeclaration("infantry.shell.config.settings", "extra")
        val m = AgentHqModule(
            ModuleManifest("Acme_Shell", "3.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl), inject = listOf(inject)),
            tier = Tier.CORE,
            shell = mapOf("frame" to BlockOverride { scope, inner -> inner?.Render(scope) }),
            inject = mapOf("extra" to BlockInjection { Text("extra") }),
        )
        val f = TabFactory(coreAndGit + m, shellOverrides = listOf(AcceptedShellOverride("Acme_Shell", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        val git = NodeRegistry.host(f, "infantry.shell.center.chat.tabs")!!.children.single { it.id == "infantry.git" }
        assertEquals(ChildSource.WRAP, git.source)
        assertEquals("Acme_Shell", git.module)
        assertEquals("3.0.0", git.version)
        assertEquals("Infantry_Git", git.by)
        // The wrapped tree still lists the children inside it.
        assertEquals(7, NodeRegistry.host(f, "infantry.shell.git")!!.children.size)
        val settings = NodeRegistry.host(f, "infantry.shell.config.settings")!!
        val extra = settings.children.last()
        assertEquals("Acme_Shell.extra", extra.id)
        assertEquals(ChildSource.INJECTION, extra.source)
        assertEquals("Acme_Shell", extra.module)
        // RUNTIME-26: the settings are the shell's own, so their nodes are the core tree's.
        assertEquals(ChildSource.SHELL, settings.children.first().source)
    }

    @Test fun `the locked dialog and its trust sections are marked locked, the open settings are not`() {
        val config = NodeRegistry.host(TabFactory(coreAndGit), "infantry.shell.config")!!
        assertTrue(config.locked)
        assertEquals(
            listOf("settings", "limits", "roles", "voice", "incomingSound", "questions", "policy", "plugins", "modules", "registry", "secrets", "pair", "about", "updates", "serverUpdate", "logs").map { "infantry.shell.config.$it" }.let { it.take(1) + SETTINGS_SLOT + it.drop(1) },
            config.children.map { it.id },
        )
        assertEquals(List(7) { false } + List(10) { true }, config.children.map { it.locked })
        assertFalse(NodeRegistry.host(TabFactory(coreAndGit), "infantry.shell.config.settings")!!.locked)
    }
    /** S4d: a filled slot and the nodes under it are the filling module's (an official plugin), not the core tree's. */
    @Test fun `a filled slot and its nodes name the module that fills it`() {
        val f = TabFactory(coreAndGit)
        assertTrue(f.fillModules.isNotEmpty())
        val rows = NodeRegistry.of(f).flatMap { it.children }
        f.fillModules.forEach { (slot, module) ->
            val row = rows.single { it.id == slot }
            assertEquals(module to ChildSource.FILL, row.module to row.source, slot)
        }
        assertEquals(setOf("Infantry_Roles", "Infantry_Board", "Infantry_Alerts", "Infantry_Header", "Infantry_Chat", "Infantry_Approvals", "Infantry_Assistant", "Infantry_Changes", "Infantry_Processes", "Infantry_Usage", "Infantry_Attention"), f.fillModules.values.toSet()) // RUNTIME-19: Usage and Attention fill their config sections; RUNTIME-14/15/16/18: Roles fills the roles window, Board the board, Alerts sound + voice, Processes the status bar's badge
        // A node under a fill: the title bar's lead row is the header plugin's.
        val lead = rows.single { it.id == "infantry.shell.header.lead" }
        assertEquals("Infantry_Header" to ChildSource.FILL, lead.module to lead.source)
        // RUNTIME-18: the status bar is the shell's own now; its cog is no module's fill.
        val cog = rows.single { it.id == "infantry.shell.footer.configCog" }
        assertTrue(cog.source != ChildSource.FILL, "the cog is the shell's: ${cog.module} ${cog.source}")
    }
}
