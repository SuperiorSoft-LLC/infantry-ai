// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced

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
        if (board.open) {
            val onDismiss: () -> Unit = board::close
            val host = BoardOverlay(onOpenAgent = board::openAgent, onDismiss = onDismiss)
            BoardModalFrame(onDismiss) {
                CompositionLocalProvider(LocalBoardOverlay provides host) {
                    RenderPlaced(children)
                }
            }
        }
        // TICKET-COMMENTS-1: drawn last, so it sits over the board or the session view.
        board.openTicketId?.let { id -> TicketModal(id, board::closeTicket) }
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
