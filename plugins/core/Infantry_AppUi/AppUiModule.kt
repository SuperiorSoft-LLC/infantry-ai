package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreStatusBar
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellAssistant
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellCenter
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellCompactDrawer
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellConfigSettings
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellDiff
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellHeader
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellLeftPanel
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellQuestionPill
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRightPanel
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellSound
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellToast
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellVoice

/** S4: the trees Infantry_AppUi draws in the shell's empty slots (`CoreShell.slots`), in slot order. */
private val fills: List<Block> = listOf(
    ShellHeader, ShellLeftPanel, ShellCenter, ShellRightPanel, ShellCompactDrawer, CoreStatusBar,
    ShellAssistant, ShellToast, ShellDiff, ShellSound, ShellVoice, ShellQuestionPill,
    ShellConfigSettings,
    // RUNTIME-14: infantry.shell.roles (the roles window) is filled by the official Infantry_Roles itself.
    // RUNTIME-15: infantry.shell.board (the board overlay) is filled by the official Infantry_Board itself.
)

/**
 * S4 (drop-in path): core module Infantry_AppUi, the app's UI as a plugin of the shell. It fills every empty slot of
 * the shell; without it the shell draws its skeleton and locked dialogs only.
 */
val InfantryAppUi = AgentHqModule(
    ModuleManifest(
        name = "Infantry_AppUi", version = "1.0.0", author = "Infantry AI",
        description = "The app's UI in the shell's empty slots: title bar, side panels, chat, status bar, and overlays.",
        fills = fills.map { com.superiorsoftllc.agenthq.plugin.api.FillDeclaration(it.id) },
    ),
    fills = fills.associateBy { it.id },
    // RUNTIME-6/7/8: no tab trees of its own any more: Infantry_Git, Infantry_Run, Infantry_Notes and Infantry_Changes
    // (official jars) register theirs (ShellGit, ShellRun, ShellNotes, ShellChanges).
)

/** RUNTIME-1: the entry of Infantry_AppUi (module.json `entry`): hands over this module alone. */
class AppUiModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryAppUi)
}
