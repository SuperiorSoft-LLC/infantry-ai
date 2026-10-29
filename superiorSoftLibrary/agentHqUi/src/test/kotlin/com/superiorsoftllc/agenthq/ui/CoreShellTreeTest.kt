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
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.official.git.InfantryGit
import com.superiorsoftllc.agenthq.plugins.official.run.InfantryRun
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L3: the core tree's node ids are the override address and, since L5, plugin API (plugins/GUIDE.md); this pins them. */
class CoreShellTreeTest {
    /**
     * RUNTIME-14/15: the compiled-in modules plus the official Infantry_Roles and Infantry_Board (their jars on desktop),
     * which fill infantry.shell.roles and infantry.shell.board.
     */
    private val withRoles by lazy { TabFactory(CoreModules.all + InfantryRoles + InfantryBoard + com.superiorsoftllc.agenthq.plugins.official.header.InfantryHeader + com.superiorsoftllc.agenthq.plugins.official.chat.InfantryChat + com.superiorsoftllc.agenthq.plugins.official.approvals.InfantryApprovals + com.superiorsoftllc.agenthq.plugins.official.assistant.InfantryAssistant + com.superiorsoftllc.agenthq.plugins.official.changes.InfantryChanges + InfantryAttention + InfantryUsage + com.superiorsoftllc.agenthq.plugins.official.alerts.InfantryAlerts + com.superiorsoftllc.agenthq.plugins.official.appearance.InfantryAppearance + com.superiorsoftllc.agenthq.plugins.official.processes.InfantryProcesses) }

    private fun Block.walk(depth: Int = 0): List<String> = listOf("  ".repeat(depth) + id) + children.flatMap { it.walk(depth + 1) }

    /** SHELL-EMPTY-DROPIN S3: the config dialog's open settings sections (under `.settings`) and its locked trust sections. */
    private val configSettings = listOf(
        "theme", "blur", "markdownEngine", "reasoning", "tabGroups", "userPortrait", "agentScope", "progressStale", "lifecycle",
        "cliLocations", "toolApproval",
    )
    /** RUNTIME-19: the sections official plugins own, one slot each beside `.settings`. */
    private val configOwned = listOf("limits", "roles", "voice", "incomingSound", "questions")
    private val configTrust = listOf("policy", "plugins", "modules", "registry", "secrets", "pair", "about", "updates", "serverUpdate", "logs")

    @Test fun `the core tree keeps every node id, in order, each unique`() {
        val header = "infantry.shell.header"
        val footer = "infantry.shell.footer"
        val expected = listOf(
            "infantry.shell.window",
            "  infantry.shell.images",
            "    infantry.shell.dock",
            "      infantry.shell.frame",
            "        $header",
            "          $header.lead",
        ) + listOf("toggleLeft", "brand", "project", "agents", "overflow")
            .map { "            $header.lead.$it" } + listOf("project", "attention", "connection")
            .map { "              $header.lead.overflow.$it" } + listOf("attention", "toggleRight", "server", "connection", "windowControls")
            .map { "          $header.$it" } + listOf(
            "        infantry.shell.body",
            "          infantry.shell.panes",
            "            infantry.shell.left",
            "            infantry.shell.center",
            "              infantry.shell.center.chat",
            "                infantry.shell.center.chat.header",
            "                infantry.shell.center.chat.tabs",
            "                infantry.shell.center.chat.transcript",
            "                infantry.shell.center.chat.notices",
            "                infantry.shell.center.chat.spawn",
            "                infantry.shell.center.chat.switch",
            "                infantry.shell.center.chat.composer",
            "              infantry.shell.center.newAgent",
            "                infantry.shell.center.newAgent.role",
            "                infantry.shell.center.newAgent.executor",
            "                infantry.shell.center.newAgent.error",
            "                infantry.shell.center.newAgent.task",
            "            infantry.shell.right",
            "              infantry.shell.right.resize",
            "              infantry.shell.right.body",
            "                infantry.shell.right.body.strip",
            "                infantry.shell.right.body.tab",
            "          infantry.shell.drawer",
            "        $footer",
        ) + listOf("connStatus", "lastFrame", "contractWarning", "versionMismatch", "processesBadge", "gap", "shortcutHint", "buildStamp", "configCog")
            .map { "          $footer.$it" } + listOf(
            // S4c: the core's Configuration entry, a dock sibling outside the replaceable frame.
            "      infantry.shell.configEntry",
            "      infantry.shell.safeMode",
            "      infantry.shell.assistant",
            "        infantry.shell.assistant.resize",
            "        infantry.shell.assistant.body",
            "          infantry.shell.assistant.body.header",
            "          infantry.shell.assistant.body.chat",
            "      infantry.shell.toast",
            "      infantry.shell.board",
            "        infantry.shell.board.header",
            "        infantry.shell.board.pane",
            "          infantry.shell.board.pane.filters",
            "          infantry.shell.board.pane.unassigned",
            "          infantry.shell.board.pane.newTicket",
            "          infantry.shell.board.pane.empty",
            "          infantry.shell.board.pane.columns",
            "            infantry.shell.board.card",
            "              infantry.shell.board.card.summary",
            "                infantry.shell.board.card.summary.title",
            "                infantry.shell.board.card.summary.badges",
            "                infantry.shell.board.card.summary.details",
            "              infantry.shell.board.card.note",
            "              infantry.shell.board.card.edit",
            "              infantry.shell.board.card.actions",
            "      infantry.shell.diff",
            "        infantry.shell.diff.header",
            "        infantry.shell.diff.body",
            "      infantry.shell.sound",
            "      infantry.shell.voice",
            "      infantry.shell.questions",
            "      infantry.shell.theme",
            "  infantry.shell.windowLayer",
            "    infantry.shell.config",
            "      infantry.shell.config.settings",
        ) + configSettings.map { "        infantry.shell.config.$it" } +
            listOf("      infantry.shell.settings") + // CONFIG-10b: the empty settings slot
            configOwned.map { "      infantry.shell.config.$it" } +
            configTrust.map { "      infantry.shell.config.$it" } + listOf(
            "    infantry.shell.serverUpdate",
            "    infantry.shell.roles",
            "      infantry.shell.roles.hint",
            "      infantry.shell.roles.list",
            "      infantry.shell.roles.form",
            "        infantry.shell.roles.form.title",
            "        infantry.shell.roles.form.identity",
            "        infantry.shell.roles.form.executor",
            "        infantry.shell.roles.form.traits",
            "        infantry.shell.roles.form.notes",
            "        infantry.shell.roles.form.actions",
            "    infantry.shell.servers",
            "    infantry.shell.switchError",
            "    infantry.shell.connect",
            "    infantry.shell.dropConsent",
        )
        // S4: the window tree as drawn, the app UI's fills in the shell's empty slots.
        val tree = withRoles.baseRoot.walk()
        assertEquals(expected.joinToString("\n"), tree.joinToString("\n"))
        assertEquals(tree.size, tree.map { it.trim() }.toSet().size, "node ids are unique")
    }

    /** SHELL-EMPTY-DROPIN S1: the placeholder ids (open slots a dropped plugin fills) and the locked set are plugin API too. */
    @Test fun `the placeholder ids and the locked ids are pinned`() {
        fun Block.all(): List<Block> = listOf(this) + children.flatMap { it.all() }
        val placeholders = listOf("infantry.shell.frame", "infantry.shell.body", "infantry.shell.panes", "infantry.shell.windowLayer")
        assertEquals(placeholders, CoreShell.placeholders)
        // RUNTIME-26: the side panels, the compact drawer and the open settings group are the shell's own, replaceable by id.
        assertEquals(
            (placeholders + listOf("infantry.shell.left", "infantry.shell.right", "infantry.shell.drawer", "infantry.shell.config.settings")).sorted(),
            CoreShell.root.all().filterIsInstance<PlaceholderBlock>().filter { it.default != null }.map { it.id }.sorted(),
        )
        // S4: the empty slots, in tree order; each empty in the shell, filled by an official plugin, nothing left empty.
        val slots = listOf(
            "infantry.shell.header", "infantry.shell.center", "infantry.shell.footer.processesBadge",
            "infantry.shell.assistant", "infantry.shell.board", "infantry.shell.diff",
            "infantry.shell.sound", "infantry.shell.voice", "infantry.shell.questions", "infantry.shell.theme",
            "infantry.shell.config.limits", "infantry.shell.config.roles", "infantry.shell.config.voice",
            "infantry.shell.config.incomingSound", "infantry.shell.config.questions", "infantry.shell.roles", "infantry.shell.settings",
        )
        assertEquals(slots, CoreShell.slots)
        // CONFIG-10b: in tree order the settings slot sits right after the theme block (slots lists it last).
        val treeOrder = slots.filter { it != "infantry.shell.settings" }.let { it.take(it.indexOf("infantry.shell.theme") + 1) + "infantry.shell.settings" + it.drop(it.indexOf("infantry.shell.theme") + 1) }
        assertEquals(treeOrder, CoreShell.root.all().filterIsInstance<PlaceholderBlock>().filter { it.default == null }.map { it.id })
        // CONFIG-10b: the settings slot is the one deliberately open slot; no official plugin in this fixture fills it.
        assertEquals(listOf("infantry.shell.settings"), withRoles.baseRoot.all().filter { it is PlaceholderBlock && it.default == null }.map { it.id })
        assertEquals(emptyList(), withRoles.problems)
        assertEquals(
            setOf(
                "infantry.shell.window", "infantry.shell.images", "infantry.shell.dock",
                "infantry.shell.connect", "infantry.shell.servers", "infantry.shell.switchError",
                "infantry.shell.serverUpdate", "infantry.shell.config", "infantry.shell.configEntry",
                "infantry.shell.dropConsent", "infantry.shell.safeMode",
                // S3: the trust half of Configuration (plugins, secrets, pairing, updates, about, logs).
                // POLICY-1: the Settings Policy card, first of the trust sections.
                "infantry.shell.config.policy",
                "infantry.shell.config.plugins", "infantry.shell.config.modules", "infantry.shell.config.secrets",
                // P1 (f60f4d97): where plugins are registered (step 2: where a registered child is turned off).
                "infantry.shell.config.registry",
                "infantry.shell.config.pair", "infantry.shell.config.about", "infantry.shell.config.updates",
                "infantry.shell.config.serverUpdate", "infantry.shell.config.logs",
            ),
            CoreShell.locked,
        )
        // Every config section is either open (under .settings) or locked, never both.
        assertEquals(emptyList(), (configSettings + configOwned).map { "infantry.shell.config.$it" }.filter { it in CoreShell.locked })
        assertEquals(false, "infantry.shell.config.settings" in CoreShell.locked)
    }

    /** BLOCKS-LAYOUT L14, L15, L17, L18: the core tab trees (drawn by their tab, not in the window tree) are pinned the same way. */
    @Test fun `the core tab trees keep every node id, in order, unique across the window tree`() {
        val git = "infantry.shell.git"
        val run = "infantry.shell.run"
        val expectedGit = listOf(git) + listOf("base", "directory", "tree", "diff", "branches", "pipeline", "history").map { "  $git.$it" }
        val expectedRun = listOf(run) + listOf("empty", "toolbar", "pills", "confirm", "editor", "notices", "output").map { "  $run.$it" }
        val notes = "infantry.shell.notes"
        val expectedNotes = listOf(
            notes,
            "  $notes.header",
            "  $notes.empty",
            "  $notes.scopes",
            "  $notes.banner",
            "  $notes.noOwner",
            "  $notes.search",
            "  $notes.hits",
            "    $notes.hit",
            "  $notes.error",
            "  $notes.list",
            "    $notes.note",
            "  $notes.modal",
        )
        val changes = "infantry.shell.changes"
        val expectedChanges = listOf(
            changes,
            "  $changes.empty",
            "  $changes.title",
            "  $changes.unsupported",
            "  $changes.notGit",
            "  $changes.worktree",
            "  $changes.header",
            "  $changes.mode",
            "  $changes.branch",
            "  $changes.hint",
            "  $changes.files",
            "    $changes.file",
            "  $changes.branchFiles",
            "    $changes.branchFile",
            "  $changes.history",
            "    $changes.commit",
        )
        // S4: the tab trees are the app UI's contributions now (TabFactory.tabTreeRoots), no longer CoreShell.tabs.
        // RUNTIME-6/7/8: Infantry_Notes, Infantry_Changes, Infantry_Run and Infantry_Git (official jars) register their own trees.
        val factory = TabFactory(CoreModules.all + InfantryRoles + InfantryBoard + InfantryPods + InfantryProfiles + InfantryTriggers + InfantryAttention + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit)
        val tabs = factory.tabTreeRoots
        assertEquals(listOf(notes, changes, run, git), tabs.map { it.id })
        assertEquals(expectedNotes.joinToString("\n"), tabs[0].walk().joinToString("\n"))
        assertEquals(expectedChanges.joinToString("\n"), tabs[1].walk().joinToString("\n"))
        assertEquals(expectedRun.joinToString("\n"), tabs[2].walk().joinToString("\n"))
        assertEquals(expectedGit.joinToString("\n"), tabs[3].walk().joinToString("\n"))
        val all = (listOf(factory.baseRoot) + tabs).flatMap { it.walk() }.map { it.trim() }
        assertEquals(all.size, all.toSet().size, "node ids are unique across the trees")
        // P1 (f60f4d97): the tab that draws each tree, as Configuration > Plugins > Registered names it.
        assertEquals(
            mapOf(git to "infantry.git", run to "infantry.run", notes to "infantry.notes", changes to "infantry.changes"),
            factory.tabTreeTabs,
        )
    }
}
