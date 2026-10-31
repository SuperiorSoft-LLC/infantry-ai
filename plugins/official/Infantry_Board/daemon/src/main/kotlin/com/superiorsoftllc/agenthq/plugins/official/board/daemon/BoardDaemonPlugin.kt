package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.BoardFromRuntime
import com.superiorsoftllc.agenthq.backlog.registerBoardFrames
import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.board.NoBoardHost
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/**
 * DAEMON-PLUGINS-1: Infantry_Board's daemon half. It registers the board's read tools (`agenthq_ticket_get` since BOARD-API-2, over the Tickets API) and the two files tools, `agenthq_ticket_files` and
 * `agenthq_ticket_related_files` (TICKET-FILES, contract 1.137.0), which were built into the CLI's MCP server before.
 * Same names, schemas, answers and gating: each is one `ticket_files` frame on the caller's own connection.
 */
class BoardDaemonPlugin : DaemonPlugin {
    @Volatile private var attached: com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime? = null

    override fun stop() { attached?.detachCore(); attached = null }

    override fun start(api: DaemonApi) {
        // DP-5h-3a: with no core-built board, the plugin builds its own from the board runtime and registers the ticket frames through
        // the typed binding and the spawn-charge port (DP-5h-3a2: with the story and epic handlers built from the runtime inputs). While core still builds the
        // board (BoardWiring), only the tools below are registered.
        val runtime = api.boardRuntime
        if (!runtime.coreBuiltBoard && runtime.host !== NoBoardHost) {
            val board = BoardFromRuntime.build(runtime, BoardFromRuntime.contextOf(runtime))
            val h = BoardBuild.handlers(runtime, board)
            BoardBuild.startup(runtime, board)
            registerBoardFrames(api.frames.table, board, api.spawns.charge, h.epics, h.stories, h.storyBoard)
            runtime.attachCore(PluginCoreAccess(board, h.epicStore, h.epicInputs))
            attached = runtime
        }
        registerPipeline(api)
        api.tools.register(PipelineTools.ShowTool)
        api.tools.register(PipelineTools.RecordTool)
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

    /**
     * DP-6: the project pipeline (PIPE-1) frames over the adopted pipeline tables. The author of a move is the declared session's
     * "role (agent)" label; every change is pushed as `pipeline_state` to every client through the board runtime.
     */
    private fun registerPipeline(api: DaemonApi) {
        val runtime = api.boardRuntime
        val store = PipelineStore(api.storage)
        val handler = { caller: com.superiorsoftllc.agenthq.daemon.api.Caller ->
            PipelineHandler(
                store = store,
                author = { id ->
                    (api.sessionReads.find(id, caller) as? com.superiorsoftllc.agenthq.daemon.api.sessions.SessionsResult.Ok)
                        ?.value?.takeIf { it.id == id }?.let { "${it.role ?: "agent"} (${it.agentId})" }
                },
                push = { runtime.push(listOf(it)) },
            )
        }
        for (type in listOf("pipeline_config", "pipeline_show", "pipeline_record", "pipeline_check")) {
            api.frames.handle(type) { frame, caller -> handler(caller).answer(frame) }
        }
    }
}
