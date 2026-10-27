package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.agenthq.ui.BoardCardHost
import com.superiorsoftllc.agenthq.ui.BoardCardItem
import com.superiorsoftllc.agenthq.ui.BoardColumnsSection
import com.superiorsoftllc.agenthq.ui.BoardEmptySection
import com.superiorsoftllc.agenthq.ui.BoardFiltersSection
import com.superiorsoftllc.agenthq.ui.BoardModalFrame
import com.superiorsoftllc.agenthq.ui.BoardModalHeader
import com.superiorsoftllc.agenthq.ui.BoardModalPaneBox
import com.superiorsoftllc.agenthq.ui.BoardNewTicketSection
import com.superiorsoftllc.agenthq.ui.BoardPaneFrame
import com.superiorsoftllc.agenthq.ui.BoardPaneHost
import com.superiorsoftllc.agenthq.ui.BoardUnassignedSection
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.TicketCardActionsSection
import com.superiorsoftllc.agenthq.ui.TicketCardBadgesSection
import com.superiorsoftllc.agenthq.ui.TicketCardDetailsSection
import com.superiorsoftllc.agenthq.ui.TicketCardEditSection
import com.superiorsoftllc.agenthq.ui.TicketCardFrame
import com.superiorsoftllc.agenthq.ui.TicketCardNoteSection
import com.superiorsoftllc.agenthq.ui.TicketCardSummarySection
import com.superiorsoftllc.agenthq.ui.TicketCardTitleSection
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced

private const val BOARD = "infantry.shell.board"
private const val PANE = "$BOARD.pane"
private const val CARD = "$BOARD.card"

/** What the board's children act on: open an agent from a card, close the board. */
private class BoardHost(val onOpenAgent: (String) -> Unit, val onDismiss: () -> Unit)

/** The open board the board's children read; null outside [BoardBlock] (a child moved elsewhere draws nothing). */
private val LocalBoardHost = compositionLocalOf<BoardHost?> { null }

/** The board pane's host; null outside [BoardPaneBlock] (a pane section moved elsewhere draws nothing). */
private val LocalBoardPaneHost = compositionLocalOf<BoardPaneHost?> { null }

/** The ticket a card is drawn for; null outside the columns (a card moved elsewhere draws nothing). */
private val LocalBoardCardItem = compositionLocalOf<BoardCardItem?> { null }

/** One card's host; null outside [TicketCardBlock] (a card section moved elsewhere draws nothing). */
private val LocalBoardCardHost = compositionLocalOf<BoardCardHost?> { null }

/**
 * M2-8: the board, a near-full-screen modal opened from the sidebar; "Agent" on a card closes it on the chat.
 * BLOCKS-LAYOUT L10: a container of child blocks `infantry.shell.board.<name>`, top to bottom, drawn straight into the
 * modal's panel column. The frame (backdrop, scrim, Esc, focus) stays the board's own.
 */
class BoardBlock(children: List<Block>, place: Place = Place()) : Block("boardModal", BOARD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalShellContext.current?.board ?: return
        if (!board.open) return
        val onDismiss: () -> Unit = board::close
        val host = BoardHost(onOpenAgent = board::openAgent, onDismiss = onDismiss)
        BoardModalFrame(onDismiss) {
            CompositionLocalProvider(LocalBoardHost provides host) {
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
        val host = LocalBoardHost.current ?: return
        BoardModalHeader(LocalShellContext.current?.session?.activeProject, host.onDismiss, scope.modifier)
    }
}

/**
 * The board itself in its padded box, which wears the placement; it takes the free height. BLOCKS-LAYOUT L16: a
 * container of child blocks `infantry.shell.board.pane.<name>`, drawn straight into the pane's column, top to bottom.
 * The frame (the epics load, the Epic filter, the new-ticket flag, the drag and its ghost) stays the pane's own.
 */
class BoardPaneBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("boardPane", PANE, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalBoardHost.current ?: return
        val s = LocalShell.current
        BoardModalPaneBox(scope.modifier) {
            BoardPaneFrame(s.state, s.controller, host.onOpenAgent) { board ->
                CompositionLocalProvider(LocalBoardPaneHost provides board) { RenderPlaced(children) }
            }
        }
    }

    override fun withChildren(children: List<Block>) = BoardPaneBlock(children, place)
}

/** The filter row (Epic, count, Start next, + New ticket); its placement goes on the row. */
object BoardFilters : Block("boardFilters", "$PANE.filters") {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardFiltersSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** The "unassigned tickets not shown" line, with a project selected; its placement goes on the line. */
object BoardUnassigned : Block("boardUnassigned", "$PANE.unassigned") {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardUnassignedSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** The new-ticket form while it is open; a placement other than the default gets a Column to wear it. */
object BoardNewTicket : Block("boardNewTicket", "$PANE.newTicket") {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardNewTicketSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** What an empty backlog says; its placement goes on its column. */
object BoardEmpty : Block("boardEmpty", "$PANE.empty") {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardEmptySection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/**
 * The status columns, taking the rest of the height; its placement goes on the row. Its children are drawn once per
 * ticket into the column's card list, with that ticket in a composition local: `infantry.shell.board.card` is the card
 * template, so an override of it (or of one of its sections) applies to every card.
 */
class BoardColumnsBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("boardColumns", "$PANE.columns", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalBoardPaneHost.current ?: return
        BoardColumnsSection(board, scope.modifier) { item ->
            CompositionLocalProvider(LocalBoardCardItem provides item) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = BoardColumnsBlock(children, place)
}

/**
 * The card template: one ticket's card frame (its column, the drag look, the edit form's open flag); its children
 * `infantry.shell.board.card.<name>` go straight into the card's column, top to bottom. Its placement comes before the
 * drag look.
 */
class TicketCardBlock(children: List<Block>, place: Place = Place()) : Block("ticketCard", CARD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalBoardPaneHost.current ?: return
        val item = LocalBoardCardItem.current ?: return
        TicketCardFrame(board, item, scope.modifier) { card ->
            CompositionLocalProvider(LocalBoardCardHost provides card) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = TicketCardBlock(children, place)
}

/** The drag handle and the clickable summary; its children go straight into the summary's column. Its placement goes on the row. */
class TicketCardSummaryBlock(children: List<Block>, place: Place = Place()) : Block("ticketCardSummary", "$CARD.summary", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val card = LocalBoardCardHost.current ?: return
        TicketCardSummarySection(card, scope.modifier) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = TicketCardSummaryBlock(children, place)
}

/** The card's title; its placement goes on the text. */
object TicketCardTitle : Block("ticketCardTitle", "$CARD.summary.title") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardTitleSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The type, assignee, epic, won't fix, queued and report chips; its placement goes on the row. */
object TicketCardBadges : Block("ticketCardBadges", "$CARD.summary.badges") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardBadgesSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The hold reason, executor, branch, progress and last run lines; a placement other than the default gets a Column. */
object TicketCardDetails : Block("ticketCardDetails", "$CARD.summary.details") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardDetailsSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** Why a drop snapped back, and the role pick; a placement other than the default gets a Column. */
object TicketCardNote : Block("ticketCardNote", "$CARD.note") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardNoteSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The edit form and the links while the card is open; a placement other than the default gets a Column. */
object TicketCardEdit : Block("ticketCardEdit", "$CARD.edit") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardEditSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The card's buttons; its placement goes on the row. */
object TicketCardActions : Block("ticketCardActions", "$CARD.actions") {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardActionsSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** BLOCKS-LAYOUT L16: the card template, drawn once per ticket by [BoardColumnsBlock]. */
val ShellTicketCard = TicketCardBlock(
    listOf(
        TicketCardSummaryBlock(listOf(TicketCardTitle, TicketCardBadges, TicketCardDetails)),
        TicketCardNote, TicketCardEdit, TicketCardActions,
    ),
)

/** BLOCKS-LAYOUT L10: the board's body; L16 split it into its sections and the card template. */
val BoardBody = BoardPaneBlock(
    listOf(BoardFilters, BoardUnassigned, BoardNewTicket, BoardEmpty, BoardColumnsBlock(listOf(ShellTicketCard))),
)

val ShellBoard = BoardBlock(listOf(BoardHeader, BoardBody))
