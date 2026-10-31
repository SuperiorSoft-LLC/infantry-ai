package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.registerBoardFrames
import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/**
 * DAEMON-PLUGINS-1: Infantry_Board's daemon half. It registers the board's read tools (`agenthq_ticket_get` since BOARD-API-2, over the Tickets API) and the two files tools, `agenthq_ticket_files` and
 * `agenthq_ticket_related_files` (TICKET-FILES, contract 1.137.0), which were built into the CLI's MCP server before.
 * Same names, schemas, answers and gating: each is one `ticket_files` frame on the caller's own connection.
 */
class BoardDaemonPlugin : DaemonPlugin {
    /** The board this plugin built from the host's runtime; null when the daemon hosts the board itself or has none. */
    @Volatile
    var built: BuiltBoard? = null
        private set

    override fun start(api: DaemonApi) {
        // DP-5h-3a2: with a BoardRuntime the plugin owns the board: it builds it and answers the board family frames. With none,
        // nothing registers and the daemon answers `<type> refused: this daemon has no board`, as before.
        api.boardRuntime?.let { runtime ->
            val b = BoardBuild.build(runtime)
            built = b
            registerBoardFrames(api.frames.table, b.board, api.spawns.charge, b.epics, b.stories, b.storyBoard)
        } ?: api.log("no board runtime: the daemon hosts the board itself or has none")
        api.tools.register(TicketGetTool(api))
        api.tools.register(TicketFilesTools.Files)
        api.tools.register(TicketFilesTools.Related)
        // RUNTIME-LOAD-4 (F5): the board's write tools, moved out of the CLI's MCP server.
        api.tools.register(EpicBaseTool)
        api.tools.register(TicketMoveTool(api))
        api.tools.register(TicketDeleteTool(api))
        api.tools.register(TicketCreateTool(api))
        api.tools.register(TicketUpdateTool(api))
        // RUNTIME-LOAD-5 (F5 remainder): the board summary, over the host's sessions read model.
        api.tools.register(BoardTool(api))
        // RUNTIME-LOAD-7 (F5 last): the ticket start, over the host spawn service.
        api.tools.register(TicketStartTool(api))
    }
}
