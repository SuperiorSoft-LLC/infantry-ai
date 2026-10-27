package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L3: the core tree's node ids are the override address and, since L5, plugin API (plugins/GUIDE.md); this pins them. */
class CoreShellTreeTest {
    private fun Block.walk(depth: Int = 0): List<String> = listOf("  ".repeat(depth) + id) + children.flatMap { it.walk(depth + 1) }

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
            "      infantry.shell.assistant",
            "        infantry.shell.assistant.resize",
            "        infantry.shell.assistant.body",
            "          infantry.shell.assistant.body.header",
            "          infantry.shell.assistant.body.chat",
            "      infantry.shell.toast",
            "      infantry.shell.board",
            "        infantry.shell.board.header",
            "        infantry.shell.board.pane",
            "      infantry.shell.diff",
            "        infantry.shell.diff.header",
            "        infantry.shell.diff.body",
            "      infantry.shell.sound",
            "      infantry.shell.voice",
            "      infantry.shell.questions",
            "  infantry.shell.windowLayer",
            "    infantry.shell.config",
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
        )
        val tree = CoreShell.root.walk()
        assertEquals(expected.joinToString("\n"), tree.joinToString("\n"))
        assertEquals(tree.size, tree.map { it.trim() }.toSet().size, "node ids are unique")
    }

    /** BLOCKS-LAYOUT L14: the core tab trees (drawn by their tab, not in the window tree) are pinned the same way. */
    @Test fun `the core tab trees keep every node id, in order, unique across the window tree`() {
        val git = "infantry.shell.git"
        val expected = listOf(git) + listOf("base", "directory", "tree", "diff", "branches", "pipeline", "history").map { "  $git.$it" }
        assertEquals(listOf(git), CoreShell.tabs.map { it.id })
        assertEquals(expected.joinToString("\n"), CoreShell.tabs.single().walk().joinToString("\n"))
        val all = (listOf(CoreShell.root) + CoreShell.tabs).flatMap { it.walk() }.map { it.trim() }
        assertEquals(all.size, all.toSet().size, "node ids are unique across the trees")
    }
}
