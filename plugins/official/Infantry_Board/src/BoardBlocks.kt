// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockType
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext

/** The shipped node ids of the board (6m); the main app's overlay is [BOARD_NODE], the exportable section [BOARD_PANE_NODE]. */
const val BOARD_NODE = "infantry.shell.board"
const val BOARD_PANE_NODE = "$BOARD_NODE.pane"
const val BOARD_CARD_NODE = "$BOARD_NODE.card"

/**
 * NS2: what the board's children act on when an overlay frames them: open an agent from a card, close the board. The
 * main app's board overlay (Infantry_AppUi's `BoardBlock`) provides it through [LocalBoardOverlay]; a pane drawn without
 * one (a fresh shell's center slot) opens agents through `ShellContext.board` and has nothing to close.
 */
class BoardOverlay(val onOpenAgent: (String) -> Unit, val onDismiss: () -> Unit)

/** The open board overlay; null when the pane fills a slot on its own. */
val LocalBoardOverlay = compositionLocalOf<BoardOverlay?> { null }

/** The board pane's host; null outside [BoardPaneBlock] (a pane section moved elsewhere draws nothing). */
private val LocalBoardPaneHost = compositionLocalOf<BoardPaneHost?> { null }

/** The ticket a card is drawn for; null outside the columns (a card moved elsewhere draws nothing). */
private val LocalBoardCardItem = compositionLocalOf<BoardCardItem?> { null }

/** One card's host; null outside [TicketCardBlock] (a card section moved elsewhere draws nothing). */
private val LocalBoardCardHost = compositionLocalOf<BoardCardHost?> { null }

/**
 * The board itself in its padded box, which wears the placement; it takes the free height. BLOCKS-LAYOUT L16: a
 * container of child blocks `infantry.shell.board.pane.<name>`, drawn straight into the pane's column, top to bottom.
 * The frame (the epics load, the Epic filter, the new-ticket flag, the drag and its ghost) stays the pane's own.
 * NS2: type `board.pane`; it draws inside the main app's overlay or on its own in any slot.
 */
class BoardPaneBlock(id: String, children: List<Block>, place: Place = Place(weight = 1f)) : Block(BoardTypes.PANE, id, place, children) {
    constructor(children: List<Block>, place: Place = Place(weight = 1f)) : this(BOARD_PANE_NODE, children, place)

    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val onOpenAgent = LocalBoardOverlay.current?.onOpenAgent ?: ctx.board::openAgent
        BoardModalPaneBox(scope.modifier) {
            BoardPaneFrame(BoardDeps(ctx), onOpenAgent) { board ->
                CompositionLocalProvider(LocalBoardPaneHost provides board) { RenderPlaced(children) }
            }
        }
    }

    override fun withChildren(children: List<Block>) = BoardPaneBlock(id, children, place)
}

/** The filter row (Epic, count, Start next, + New ticket); its placement goes on the row. */
class BoardFiltersBlock(id: String) : Block(BoardTypes.FILTERS, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardFiltersSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** The "unassigned tickets not shown" line, with a project selected; its placement goes on the line. */
class BoardUnassignedBlock(id: String) : Block(BoardTypes.UNASSIGNED, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardUnassignedSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** The new-ticket form while it is open; a placement other than the default gets a Column to wear it. */
class BoardNewTicketBlock(id: String) : Block(BoardTypes.NEW_TICKET, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        BoardNewTicketSection(LocalBoardPaneHost.current ?: return, scope.modifier)
    }
}

/** What an empty backlog says; its placement goes on its column. */
class BoardEmptyBlock(id: String) : Block(BoardTypes.EMPTY, id) {
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
class BoardColumnsBlock(id: String, children: List<Block>, place: Place = Place(weight = 1f)) : Block(BoardTypes.COLUMNS, id, place, children) {
    constructor(children: List<Block>, place: Place = Place(weight = 1f)) : this("$BOARD_PANE_NODE.columns", children, place)

    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalBoardPaneHost.current ?: return
        BoardColumnsSection(board, scope.modifier) { item ->
            CompositionLocalProvider(LocalBoardCardItem provides item) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = BoardColumnsBlock(id, children, place)
}

/**
 * The card template: one ticket's card frame (its column, the drag look, the edit form's open flag); its children
 * `infantry.shell.board.card.<name>` go straight into the card's column, top to bottom. Its placement comes before the
 * drag look.
 */
class TicketCardBlock(id: String, children: List<Block>, place: Place = Place()) : Block(BoardTypes.CARD, id, place, children) {
    constructor(children: List<Block>, place: Place = Place()) : this(BOARD_CARD_NODE, children, place)

    @Composable
    override fun Render(scope: BlockScope) {
        val board = LocalBoardPaneHost.current ?: return
        val item = LocalBoardCardItem.current ?: return
        TicketCardFrame(board, item, scope.modifier) { card ->
            CompositionLocalProvider(LocalBoardCardHost provides card) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = TicketCardBlock(id, children, place)
}

/** The drag handle and the clickable summary; its children go straight into the summary's column. Its placement goes on the row. */
class TicketCardSummaryBlock(id: String, children: List<Block>, place: Place = Place()) : Block(BoardTypes.CARD_SUMMARY, id, place, children) {
    constructor(children: List<Block>, place: Place = Place()) : this("$BOARD_CARD_NODE.summary", children, place)

    @Composable
    override fun Render(scope: BlockScope) {
        val card = LocalBoardCardHost.current ?: return
        TicketCardSummarySection(card, scope.modifier) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = TicketCardSummaryBlock(id, children, place)
}

/** The card's title; its placement goes on the text. */
class TicketCardTitleBlock(id: String) : Block(BoardTypes.CARD_TITLE, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardTitleSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The type, assignee, epic, won't fix, queued and report chips; its placement goes on the row. */
class TicketCardBadgesBlock(id: String) : Block(BoardTypes.CARD_BADGES, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardBadgesSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The hold reason, executor, branch, progress and last run lines; a placement other than the default gets a Column. */
class TicketCardDetailsBlock(id: String) : Block(BoardTypes.CARD_DETAILS, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardDetailsSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** Why a drop snapped back, and the role pick; a placement other than the default gets a Column. */
class TicketCardNoteBlock(id: String) : Block(BoardTypes.CARD_NOTE, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardNoteSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The edit form and the links while the card is open; a placement other than the default gets a Column. */
class TicketCardEditBlock(id: String) : Block(BoardTypes.CARD_EDIT, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardEditSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/** The card's buttons; its placement goes on the row. */
class TicketCardActionsBlock(id: String) : Block(BoardTypes.CARD_ACTIONS, id) {
    @Composable
    override fun Render(scope: BlockScope) {
        TicketCardActionsSection(LocalBoardCardHost.current ?: return, scope.modifier)
    }
}

/**
 * NS2 (docs/NORTH-STAR-DESIGN.md 3a): the board's 14 block types, each a factory over the section blocks above. The
 * declarations (parents, max, template, the two instance fields) live in [BoardModule]'s manifest and module.json.
 */
object BoardTypes {
    const val PANE = "board.pane"
    const val FILTERS = "board.filters"
    const val UNASSIGNED = "board.unassigned"
    const val NEW_TICKET = "board.newTicket"
    const val EMPTY = "board.empty"
    const val COLUMNS = "board.columns"
    const val CARD = "board.card"
    const val CARD_SUMMARY = "board.card.summary"
    const val CARD_TITLE = "board.card.title"
    const val CARD_BADGES = "board.card.badges"
    const val CARD_DETAILS = "board.card.details"
    const val CARD_NOTE = "board.card.note"
    const val CARD_EDIT = "board.card.edit"
    const val CARD_ACTIONS = "board.card.actions"

    /** Every type, in tree order. */
    val all: Map<String, BlockType> = linkedMapOf(
        PANE to BlockType { id, c -> BoardPaneBlock(id, c) },
        FILTERS to BlockType { id, _ -> BoardFiltersBlock(id) },
        UNASSIGNED to BlockType { id, _ -> BoardUnassignedBlock(id) },
        NEW_TICKET to BlockType { id, _ -> BoardNewTicketBlock(id) },
        EMPTY to BlockType { id, _ -> BoardEmptyBlock(id) },
        COLUMNS to BlockType { id, c -> BoardColumnsBlock(id, c) },
        CARD to BlockType { id, c -> TicketCardBlock(id, c) },
        CARD_SUMMARY to BlockType { id, c -> TicketCardSummaryBlock(id, c) },
        CARD_TITLE to BlockType { id, _ -> TicketCardTitleBlock(id) },
        CARD_BADGES to BlockType { id, _ -> TicketCardBadgesBlock(id) },
        CARD_DETAILS to BlockType { id, _ -> TicketCardDetailsBlock(id) },
        CARD_NOTE to BlockType { id, _ -> TicketCardNoteBlock(id) },
        CARD_EDIT to BlockType { id, _ -> TicketCardEditBlock(id) },
        CARD_ACTIONS to BlockType { id, _ -> TicketCardActionsBlock(id) },
    )

    /** A new instance [id] of [type] (one of [all]). */
    fun create(type: String, id: String, children: List<Block> = emptyList()): Block =
        checkNotNull(all[type]) { "not a board type: $type" }.create(id, children)
}

// The shipped instances, built through the types: the main app's overlay draws exactly these.
val BoardFilters: Block = BoardTypes.create(BoardTypes.FILTERS, "$BOARD_PANE_NODE.filters")
val BoardUnassigned: Block = BoardTypes.create(BoardTypes.UNASSIGNED, "$BOARD_PANE_NODE.unassigned")
val BoardNewTicket: Block = BoardTypes.create(BoardTypes.NEW_TICKET, "$BOARD_PANE_NODE.newTicket")
val BoardEmpty: Block = BoardTypes.create(BoardTypes.EMPTY, "$BOARD_PANE_NODE.empty")
val TicketCardTitle: Block = BoardTypes.create(BoardTypes.CARD_TITLE, "$BOARD_CARD_NODE.summary.title")
val TicketCardBadges: Block = BoardTypes.create(BoardTypes.CARD_BADGES, "$BOARD_CARD_NODE.summary.badges")
val TicketCardDetails: Block = BoardTypes.create(BoardTypes.CARD_DETAILS, "$BOARD_CARD_NODE.summary.details")
val TicketCardNote: Block = BoardTypes.create(BoardTypes.CARD_NOTE, "$BOARD_CARD_NODE.note")
val TicketCardEdit: Block = BoardTypes.create(BoardTypes.CARD_EDIT, "$BOARD_CARD_NODE.edit")
val TicketCardActions: Block = BoardTypes.create(BoardTypes.CARD_ACTIONS, "$BOARD_CARD_NODE.actions")

/** BLOCKS-LAYOUT L16: the card template, drawn once per ticket by [BoardColumnsBlock]. */
val ShellTicketCard: Block = BoardTypes.create(
    BoardTypes.CARD, BOARD_CARD_NODE,
    listOf(
        BoardTypes.create(BoardTypes.CARD_SUMMARY, "$BOARD_CARD_NODE.summary", listOf(TicketCardTitle, TicketCardBadges, TicketCardDetails)),
        TicketCardNote, TicketCardEdit, TicketCardActions,
    ),
)

/** BLOCKS-LAYOUT L10: the board's body; L16 split it into its sections and the card template. NS2: section `board.pane`. */
val BoardBody: Block = BoardTypes.create(
    BoardTypes.PANE, BOARD_PANE_NODE,
    listOf(
        BoardFilters, BoardUnassigned, BoardNewTicket, BoardEmpty,
        BoardTypes.create(BoardTypes.COLUMNS, "$BOARD_PANE_NODE.columns", listOf(ShellTicketCard)),
    ),
)
