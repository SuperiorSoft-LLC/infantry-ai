package com.superiorsoftllc.agenthq.plugins.official.board

import com.superiorsoftllc.agenthq.ui.*
import com.superiorsoftllc.agenthq.ui.shell.blocks.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced

private const val BOARD = BOARD_NODE

/**
 * M2-8: the board, a near-full-screen modal opened from the sidebar; "Agent" on a card closes it on the chat.
 * BLOCKS-LAYOUT L10: a container of child blocks `infantry.shell.board.<name>`, top to bottom, drawn straight into the
 * modal's panel column. The frame (backdrop, scrim, Esc, focus) stays the board's own. NS2: the pane under it
 * (`board.pane` and its sections) lives in Infantry_Board, built from that module's block types.
 */
class BoardBlock(children: List<Block>, place: Place = Place()) : Block("boardModal", BOARD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalShellContext.current?.board ?: return
        if (!board.open) return
        val onDismiss: () -> Unit = board::close
        val host = BoardOverlay(onOpenAgent = board::openAgent, onDismiss = onDismiss)
        BoardModalFrame(onDismiss) {
            CompositionLocalProvider(LocalBoardOverlay provides host) {
                RenderPlaced(children)
            }
        }
    }

    override fun withChildren(children: List<Block>) = BoardBlock(children, place)
}

/** The title row (title, active project, close) and the divider under it; its placement goes on the row. */
object BoardHeader : Block("boardHeader", "$BOARD.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalBoardOverlay.current ?: return
        BoardModalHeader(LocalShellContext.current?.session?.activeProject, host.onDismiss, scope.modifier)
    }
}

/** NS2: the overlay (this module) over the board pane (Infantry_Board's [BoardBody], drawn from its types). */
val ShellBoard = BoardBlock(listOf(BoardHeader, BoardBody))
