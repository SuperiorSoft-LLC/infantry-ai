package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import com.superiorsoftllc.agenthq.ui.shell.blocks.BoardTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * NS2 (docs/NORTH-STAR-DESIGN.md 3a): the board's 14 block type declarations, as in module.json. Two instance fields:
 * `board.pane.columns` (the statuses shown, default all five) and `board.filters.showStartNext`.
 */
val BoardBlockTypeDeclarations: List<BlockTypeDeclaration> = listOf(
    BlockTypeDeclaration(
        BoardTypes.PANE, "Board", takesChildren = true,
        fields = listOf(
            ConfigFieldDeclaration(
                "columns", ConfigFieldTypes.MULTI_SELECT, "Columns",
                JsonArray(TicketColumns.ALL.map(::JsonPrimitive)), TicketColumns.ALL,
            ),
        ),
    ),
    BlockTypeDeclaration(
        BoardTypes.FILTERS, "Board filters", parents = listOf(BoardTypes.PANE),
        fields = listOf(ConfigFieldDeclaration("showStartNext", ConfigFieldTypes.CHECKBOX, "Show Start next", JsonPrimitive(true))),
    ),
    BlockTypeDeclaration(BoardTypes.UNASSIGNED, "Unassigned note", parents = listOf(BoardTypes.PANE)),
    BlockTypeDeclaration(BoardTypes.NEW_TICKET, "New ticket form", parents = listOf(BoardTypes.PANE), max = 1),
    BlockTypeDeclaration(BoardTypes.EMPTY, "Empty board", parents = listOf(BoardTypes.PANE)),
    BlockTypeDeclaration(BoardTypes.COLUMNS, "Board columns", takesChildren = true, parents = listOf(BoardTypes.PANE), max = 1),
    BlockTypeDeclaration(BoardTypes.CARD, "Ticket card", takesChildren = true, parents = listOf(BoardTypes.COLUMNS), template = true),
    BlockTypeDeclaration(BoardTypes.CARD_SUMMARY, "Card summary", takesChildren = true, parents = listOf(BoardTypes.CARD)),
    BlockTypeDeclaration(BoardTypes.CARD_TITLE, "Card title", parents = listOf(BoardTypes.CARD_SUMMARY)),
    BlockTypeDeclaration(BoardTypes.CARD_BADGES, "Card badges", parents = listOf(BoardTypes.CARD_SUMMARY)),
    BlockTypeDeclaration(BoardTypes.CARD_DETAILS, "Card details", parents = listOf(BoardTypes.CARD_SUMMARY)),
    BlockTypeDeclaration(BoardTypes.CARD_NOTE, "Card note", parents = listOf(BoardTypes.CARD)),
    BlockTypeDeclaration(BoardTypes.CARD_EDIT, "Card edit form", parents = listOf(BoardTypes.CARD)),
    BlockTypeDeclaration(BoardTypes.CARD_ACTIONS, "Card actions", parents = listOf(BoardTypes.CARD)),
)

/**
 * NS2 (3b): core module Infantry_Board, the scrum board as block types. It fills nothing: the main app's board overlay
 * (Infantry_AppUi) draws `infantry.shell.board.pane` from these types, and an exported composition can put a
 * `board.pane` in any slot (a fresh shell's center).
 */
val InfantryBoard: AgentHqModule = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Board", version = "1.0.0", author = "Infantry AI",
        description = "The scrum board as block types: board.pane and its sections, cards and card sections.",
        blockTypes = BoardBlockTypeDeclarations,
    ),
    blockTypes = BoardTypes.all,
)

/** NS2: the entry of Infantry_Board.ihq (boardIhq): hands over the board module alone. */
class BoardModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryBoard)
}
