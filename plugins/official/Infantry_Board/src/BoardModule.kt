package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import com.superiorsoftllc.infantry.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.plugin.Panels
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.BadgeTone
import com.superiorsoftllc.infantry.ui.plugin.SessionBadge
import com.superiorsoftllc.infantry.ui.plugin.SessionBadgeProvider
import com.superiorsoftllc.infantry.ui.model.ticketTagFull
import com.superiorsoftllc.infantry.ui.model.ticketTagLabel
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
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

/** AR-7b: the session row's ticket tag (short id + truncated title, full title on hover), contributed by the board. */
val TicketBadge = SessionBadgeProvider("infantry.board.ticket") { state, a ->
    val id = a.session.ticketId?.takeIf { it.isNotBlank() } ?: return@SessionBadgeProvider null
    val title = state.ticket(id)?.title
    SessionBadge(ticketTagLabel(id, title), BadgeTone.INFO, ticketTagFull(id, title), "agent-row-ticket-${a.id}")
}

/** The board overlay's empty shell slot (opened from the sidebar's Board button), which this module fills itself. */
const val BOARD_FILL_ID = BOARD_NODE

/** AR-7: the id of the Board button panel section (module.json "panels"). */
const val BOARD_BUTTON_PANEL = "infantry.board.button"

/** AR-7 / ANDROID-BOARD-BTN-1: the Board button section; shared by the runtime registrar and the compiled-in module (web, Android). */
val BoardButtonPanel = PanelProvider(id = BOARD_BUTTON_PANEL, panel = Panels.LEFT_BOARD, order = 0, testTag = "sidebar-board-slot") { ctx ->
    val chat = LocalShellContext.current?.chat
    SidebarBoardButton(ctx.state, onOpen = { chat?.showBoard() })
}

/**
 * NS2 (3b): Infantry_Board (official since RUNTIME-15), the scrum board: the overlay it fills (`infantry.shell.board`,
 * formerly filled by Infantry_AppUi) and its block types, so an exported composition can put a `board.pane` in any slot.
 * Compiled in on web and Android (D9).
 */
val InfantryBoard: InfantryModule = InfantryModule(
    ModuleManifest(
        name = "Infantry_Board", version = "1.0.0", author = "Infantry AI",
        description = "The scrum board: the board overlay and its block types, board.pane and its sections, cards and card sections.",
        fills = listOf(FillDeclaration(BOARD_FILL_ID)),
        panels = listOf(com.superiorsoftllc.infantry.plugin.api.PanelDeclaration(BOARD_BUTTON_PANEL, Panels.LEFT_BOARD)),
        blockTypes = BoardBlockTypeDeclarations,
    ),
    fills = mapOf(BOARD_FILL_ID to ShellBoard),
    blockTypes = BoardTypes.all,
    sessionBadges = listOf(TicketBadge),
    panels = listOf(BoardButtonPanel),
)


/**
 * RUNTIME-15: the entry of Infantry_Board (module.json `entry`), an official module. It fills the board overlay's slot and
 * registers the board's block types. Without it there is no board (the sidebar hides its Board button).
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Board`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class BoardPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(BOARD_FILL_ID, ShellBoard)
        BoardTypes.all.forEach { (type, blockType) -> registrar.blockType(type, blockType) }
        registrar.sessionBadge(TicketBadge)
        // AR-7: the sidebar's Board button, in the left panel's left.board slot (first).
        registrar.panel(BoardButtonPanel)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryBoard)
}
