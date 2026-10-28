package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.shell.blocks.ChangesTab
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Changes. The project's worktrees and their changes. */
val InfantryChanges = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Changes", version = "1.0.0", author = "Infantry AI", description = "The project's worktrees and their changes.",
        tabs = listOf(TabDeclaration("infantry.changes", TabContainers.RIGHT)),
    ),
    listOf(
        TabProvider(
            id = "infantry.changes", container = TabContainers.RIGHT, order = 10,
            title = { "Changes ^3" },
            icon = tabIcon(
                "changes",
                { moveTo(12f, 4f); lineTo(12f, 12f) },
                { moveTo(8f, 8f); lineTo(16f, 8f) },
                { moveTo(8f, 18f); lineTo(16f, 18f) },
            ),
            testTag = "right-tab-CHANGES",
            shortcut = 3,
            routes = listOf("loadProjectWorktrees", "loadProjectBranches", "loadBranchChanges", "loadProjectTree", "loadWorktreeStatus", "selectChangesBranch", "selectChangesWorktree", "pollOnce"),
        ) { ChangesTab(it.diffTarget, it.onOpenDiff) },
    ),
)

/** RUNTIME-1: the entry of Infantry_Changes (module.json `entry`): hands over this module alone. */
class ChangesModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryChanges)
}
