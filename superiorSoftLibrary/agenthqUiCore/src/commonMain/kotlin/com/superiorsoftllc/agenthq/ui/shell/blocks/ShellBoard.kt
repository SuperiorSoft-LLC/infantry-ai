package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.BoardModalFrame
import com.superiorsoftllc.agenthq.ui.BoardModalHeader
import com.superiorsoftllc.agenthq.ui.BoardModalPane
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.model.CompactScreen

private const val BOARD = "infantry.shell.board"

/** What the board's children act on: open an agent from a card, close the board. */
private class BoardHost(val onOpenAgent: (String) -> Unit, val onDismiss: () -> Unit)

/** The open board the board's children read; null outside [BoardBlock] (a child moved elsewhere draws nothing). */
private val LocalBoardHost = compositionLocalOf<BoardHost?> { null }

/**
 * M2-8: the board, a near-full-screen modal opened from the sidebar; "Agent" on a card closes it on the chat.
 * BLOCKS-LAYOUT L10: a container of child blocks `infantry.shell.board.<name>`, top to bottom, drawn straight into the
 * modal's panel column. The frame (backdrop, scrim, Esc, focus) stays the board's own.
 */
class BoardBlock(children: List<Block>, place: Place = Place()) : Block("boardModal", BOARD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        if (!ui.showBoard) return
        val onDismiss: () -> Unit = { ui.showBoard = false; s.root.requestFocus() }
        val host = BoardHost(
            onOpenAgent = {
                s.controller.select(it); ui.centerTab = s.homeTab; s.chat.spawnMode = false; ui.showBoard = false
                // MOBILE-LAYOUT-2: a compact window opens the Board from the list; "Agent" lands on the output.
                if (s.state.panels.compactWindow) s.controller.showCompactScreen(CompactScreen.OUTPUT)
            },
            onDismiss = onDismiss,
        )
        BoardModalFrame(onDismiss) {
            CompositionLocalProvider(LocalBoardHost provides host) {
                RenderPlaced(children)
                BoardModalPane(s.state, s.controller, host.onOpenAgent, Modifier.weight(1f))
            }
        }
    }

    override fun withChildren(children: List<Block>) = BoardBlock(children, place)
}

/** The title row (title, active project, close) and the divider under it; its placement goes on the row. */
object BoardHeader : Block("boardHeader", "$BOARD.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalBoardHost.current ?: return
        BoardModalHeader(LocalShell.current.state, host.onDismiss, scope.modifier)
    }
}

val ShellBoard = BoardBlock(listOf(BoardHeader))
