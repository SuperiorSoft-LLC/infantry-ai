package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/**
 * DAEMON-PLUGINS-1: Infantry_Board's daemon half. It registers the board's read tools (`agenthq_ticket_get` since BOARD-API-2, over the Tickets API) and the two files tools, `agenthq_ticket_files` and
 * `agenthq_ticket_related_files` (TICKET-FILES, contract 1.137.0), which were built into the CLI's MCP server before.
 * Same names, schemas, answers and gating: each is one `ticket_files` frame on the caller's own connection.
 */
class BoardDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(TicketGetTool(api))
        api.tools.register(TicketFilesTools.Files)
        api.tools.register(TicketFilesTools.Related)
        // RUNTIME-LOAD-4 (F5): the board's write tools, moved out of the CLI's MCP server.
        api.tools.register(EpicBaseTool)
        api.tools.register(TicketMoveTool(api))
        api.tools.register(TicketDeleteTool(api))
        api.tools.register(TicketCreateTool(api))
        api.tools.register(TicketUpdateTool(api))
    }
}
