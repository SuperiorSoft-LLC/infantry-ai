package com.superiorsoftllc.agenthq.ui.shell.blocks

import com.superiorsoftllc.agenthq.ui.block.Block

/**
 * BLOCKS-SHELL-1: the window's blocks, in the order they are drawn (the order App() called them before). The root
 * only renders this array; nothing overrides, inserts or reorders it yet (a later BLOCKS-1 piece lets modules do so).
 */
object CoreShell {
    val blocks: List<Block> = listOf(
        ShellHeader,
        ShellLeftPanel,
        ShellCenter,
        ShellRightPanel,
        ShellCompactDrawer,
        ShellStatusBar,
        ShellAssistant,
        ShellToast,
        ShellBoard,
        ShellDiff,
        ShellSound,
        ShellVoice,
        ShellQuestionPill,
        ShellConfig,
        ShellServerUpdate,
        ShellRoles,
        ShellServers,
        ShellSwitchError,
        ShellConnect,
    )
}
