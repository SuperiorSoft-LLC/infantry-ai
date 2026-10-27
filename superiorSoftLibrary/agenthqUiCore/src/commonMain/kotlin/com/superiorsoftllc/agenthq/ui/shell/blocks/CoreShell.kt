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

    /** BLOCKS-SHELL-2: the status bar's children, in the order they sit in the bar (only the build stamp so far). */
    val statusBarChildren: List<Block> = listOf(StatusBuildStamp)

    /** BLOCKS-SHELL-3: the status bar's children that sit right after its status text, before the flexible gap. */
    val statusBarLeading: List<Block> = listOf(StatusProcessesBadge)
}
