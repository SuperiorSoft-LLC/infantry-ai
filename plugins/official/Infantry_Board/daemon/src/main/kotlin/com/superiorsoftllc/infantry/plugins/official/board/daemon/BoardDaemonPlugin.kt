// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.backlog.BoardFromRuntime
import com.superiorsoftllc.infantry.backlog.registerBoardFrames
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.board.NoBoardHost
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin

/**
 * DAEMON-PLUGINS-1: Infantry_Board's daemon half. It registers the board's read tools (`infantry_ticket_get` since BOARD-API-2, over the Tickets API) and the two files tools, `infantry_ticket_files` and
 * `infantry_ticket_related_files` (TICKET-FILES, contract 1.137.0), which were built into the CLI's MCP server before.
 * Same names, schemas, answers and gating: each is one `ticket_files` frame on the caller's own connection.
 */
class BoardDaemonPlugin : DaemonPlugin {
    @Volatile private var attached: com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime? = null

    @Volatile private var moved: AutoCloseable? = null

    override fun stop() { runCatching { moved?.close() }; moved = null; attached?.detachCore(); attached = null }

    override fun start(api: DaemonApi) {
        // DP-5h-3a: with no core-built board, the plugin builds its own from the board runtime and registers the ticket frames through
        // the typed binding and the spawn-charge port (DP-5h-3a2: with the story and epic handlers built from the runtime inputs). While core still builds the
        // board (BoardWiring), only the tools below are registered.
        val runtime = api.boardRuntime
        var ownBoard: com.superiorsoftllc.infantry.backlog.TicketBoard? = null
        if (!runtime.coreBuiltBoard && runtime.host !== NoBoardHost) {
            val board = BoardFromRuntime.build(runtime, BoardFromRuntime.contextOf(runtime))
            ownBoard = board
            val h = BoardBuild.handlers(runtime, board)
            BoardBuild.startup(runtime, board)
            registerBoardFrames(api.frames.table, board, api.spawns.charge, h.epics, h.stories, h.storyBoard)
            runtime.attachCore(PluginCoreAccess(board, h.epicStore, h.epicInputs))
            attached = runtime
        }
        // SCRIPT-5: a ticket entering a lane is the named event `ticket.moved` (lane-enter hooks of Infantry_Scripting ride it).
        moved = runCatching { TicketMovedEvent.attach(api) }.onFailure { api.log("ticket.moved event unavailable: ${it.message}") }.getOrNull()
        registerPipeline(api)
        api.tools.register(PipelineTools.ShowTool)
        api.tools.register(PipelineTools.RecordTool)
        // TICKET-COMMENTS-2: comments, createdBy and the notify list (plugin tables); without storage the board works as before.
        var commentsError: String? = null
        val comments = runCatching { CommentsWiring.create(api) }.onFailure { commentsError = it.message; api.log("ticket comments unavailable: ${it.message}") }.getOrNull()
        // AGENT-TASKS-2: an unassign writes its handoff as a resolved `handoff` comment; the next start brief reads the latest one.
        comments?.let { store -> ownBoard?.let { b ->
            b.handoffSink = { id, project, body -> store.add(id, project, "", TicketComments.Author.daemon, "handoff", TicketComments.RESOLVED, body) }
            b.queueNoteSink = { id, project, body -> store.add(id, project, "", TicketComments.Author.daemon, "note", TicketComments.RESOLVED, body) }
            b.latestHandoff = { id -> store.read(id, "latest", "handoff", null).firstOrNull()?.body }
        } }
        api.tools.register(TicketGetTool(api, comments))
        // COMMENT-TOOL-1: the comment tools are always listed; without the store they refuse with the reason instead of vanishing from every worker's tool list.
        val why = commentsError?.let { "${CommentsWiring.UNAVAILABLE}: $it" } ?: CommentsWiring.UNAVAILABLE
        api.tools.register(TicketCommentTool(api, comments, why))
        api.tools.register(TicketCommentsTool(api, comments, why))
        if (comments != null) {
            // TICKET-COMMENTS-4: the idle watch in core posts its safety-net comment through this port.
            runCatching { api.services.provide(com.superiorsoftllc.infantry.daemon.api.tickets.TicketReports::class, TicketReportsService(api, comments)) }
                .onFailure { api.log("ticket reports unavailable: ${it.message}") }
        }
        api.tools.register(TicketFilesTools.Files)
        api.tools.register(TicketFilesTools.Related)
        // ARCHIVE-1: archive / restore / read the ticket archive.
        api.tools.register(ArchiveTools.Archive)
        api.tools.register(ArchiveTools.Restore)
        api.tools.register(ArchiveTools.Query)
        // RUNTIME-LOAD-4 (F5): the board's write tools, moved out of the CLI's MCP server.
        api.tools.register(EpicBaseTool)
        api.tools.register(TicketMoveTool(api))
        api.tools.register(TicketUnassignTool(api))
        api.tools.register(AgentQueueTool(api))
        api.tools.register(TicketAssignTool(api))
        api.tools.register(AgentSwitchTool(api))
        api.tools.register(TicketDeleteTool(api))
        api.tools.register(TicketCreateTool(api, comments))
        api.tools.register(TicketUpdateTool(api, comments))
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
        val handler = { caller: com.superiorsoftllc.infantry.daemon.api.Caller ->
            PipelineHandler(
                store = store,
                author = { id ->
                    (api.sessionReads.find(id, caller) as? com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult.Ok)
                        ?.value?.takeIf { it.id == id }?.let { "${it.role ?: "agent"} (${it.agentId})" }
                },
                push = { runtime.push(listOf(it)) },
            )
        }
        for (type in PIPELINE_FRAMES) {
            api.frames.handle(type) { frame, caller -> handler(caller).answer(frame) }
        }
    }
}

/** BOARD-FRAMES-1: the pipeline frame types [BoardDaemonPlugin] answers; each must be declared in module.json's daemon.frames. */
internal val PIPELINE_FRAMES = listOf("pipeline_config", "pipeline_show", "pipeline_record", "pipeline_check")
