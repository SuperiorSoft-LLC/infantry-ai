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
        ) + listOf("lead", "connection", "windowControls")
            .map { "          $header.$it" } + listOf(
            "        infantry.shell.body",
            "          infantry.shell.panes",
            "            infantry.shell.left",
            "            infantry.shell.center",
            "            infantry.shell.right",
            "          infantry.shell.drawer",
            "        $footer",
        ) + listOf("connStatus", "lastFrame", "contractWarning", "versionMismatch", "processesBadge", "gap", "shortcutHint", "buildStamp", "configCog")
            .map { "          $footer.$it" } + listOf(
            "      infantry.shell.assistant",
            "      infantry.shell.toast",
            "      infantry.shell.board",
            "      infantry.shell.diff",
            "      infantry.shell.sound",
            "      infantry.shell.voice",
            "      infantry.shell.questions",
            "  infantry.shell.windowLayer",
            "    infantry.shell.config",
            "    infantry.shell.serverUpdate",
            "    infantry.shell.roles",
            "    infantry.shell.servers",
            "    infantry.shell.switchError",
            "    infantry.shell.connect",
        )
        val tree = CoreShell.root.walk()
        assertEquals(expected.joinToString("\n"), tree.joinToString("\n"))
        assertEquals(tree.size, tree.map { it.trim() }.toSet().size, "node ids are unique")
    }
}
