package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/**
 * DAEMON-PLUGINS-1: Infantry_Board's daemon half. It registers the board's two read tools, `agenthq_ticket_files` and
 * `agenthq_ticket_related_files` (TICKET-FILES, contract 1.137.0), which were built into the CLI's MCP server before.
 * Same names, schemas, answers and gating: each is one `ticket_files` frame on the caller's own connection.
 */
class BoardDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(TicketFilesTools.Files)
        api.tools.register(TicketFilesTools.Related)
    }
}
