package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreStatusBar
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellAssistant
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellBoard
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellCenter
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellChanges
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellCompactDrawer
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellConfigSettings
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellDiff
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellGit
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellHeader
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellLeftPanel
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellNotes
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellQuestionPill
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRightPanel
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRoles
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRun
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellSound
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellToast
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellVoice

/** S4: the trees Infantry_AppUi draws in the shell's empty slots (`CoreShell.slots`), in slot order. */
private val fills: List<Block> = listOf(
    ShellHeader, ShellLeftPanel, ShellCenter, ShellRightPanel, ShellCompactDrawer, CoreStatusBar,
    ShellAssistant, ShellToast, ShellBoard, ShellDiff, ShellSound, ShellVoice, ShellQuestionPill,
    ShellConfigSettings, ShellRoles,
)

/**
 * S4 (drop-in path): core module Infantry_AppUi, the app's UI as a plugin of the shell. It fills every empty slot of
 * the shell and adds the tab trees its tabs draw; without it the shell draws its skeleton and locked dialogs only.
 */
val InfantryAppUi = AgentHqModule(
    ModuleManifest(
        name = "Infantry_AppUi", version = "1.0.0", author = "Infantry AI",
        description = "The app's UI in the shell's empty slots: title bar, side panels, chat, status bar, overlays and the roles window.",
        fills = fills.map { com.superiorsoftllc.agenthq.plugin.api.FillDeclaration(it.id) },
    ),
    fills = fills.associateBy { it.id },
    tabTrees = mapOf(
        "infantry.git" to ShellGit, "infantry.run" to ShellRun,
        "infantry.notes" to ShellNotes, "infantry.changes" to ShellChanges,
    ),
)

/** RUNTIME-1: the entry of Infantry_AppUi (module.json `entry`): hands over this module alone. */
class AppUiModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryAppUi)
}
