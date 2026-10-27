package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.BoardModal
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.model.CompactScreen

/** M2-8: the board, a near-full-screen modal opened from the sidebar; "Agent" on a card closes it on the chat. */
object ShellBoard : Block("infantry.shell.board", "infantry.shell.board") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        if (ui.showBoard) {
            BoardModal(
                s.state, s.controller,
                onOpenAgent = {
                    s.controller.select(it); ui.centerTab = s.homeTab; s.chat.spawnMode = false; ui.showBoard = false
                    // MOBILE-LAYOUT-2: a compact window opens the Board from the list; "Agent" lands on the output.
                    if (s.state.panels.compactWindow) s.controller.showCompactScreen(CompactScreen.OUTPUT)
                },
                onDismiss = { ui.showBoard = false; s.root.requestFocus() },
            )
        }
    }
}
