// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.orchestrator.FrameSide
import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * DP-5d: the frame table `frame type -> handler`. The owner of a frame registers it; the connection loop looks a
 * frame up here after the policy / owner / plugin gates, which therefore apply to every registered frame unchanged.
 * A handler gets the request and whether it is `direct` (BUG-MCP-RACE-1: it carried a `requestId`).
 */
class FrameTable {
    private val handlers = LinkedHashMap<String, (Envelope, Boolean) -> Envelope?>()
    private val sides = HashMap<String, String>()

    /**
     * RUNNER-POOL-7: [side] is the entry's generic side attribute ([FrameSide.RUNNER] by default, [FrameSide.ORCHESTRATOR] for a frame
     * that reads or writes orchestrator state). It is consulted only for a session a remote orchestrator started on this runner.
     */
    fun register(vararg types: String, side: String = FrameSide.RUNNER, handler: (envelope: Envelope, direct: Boolean) -> Envelope?) {
        for (type in types) {
            handlers[type] = handler
            sides[type] = FrameSide.of(side)
        }
    }

    fun handles(type: String): Boolean = type in handlers

    /** RUNNER-POOL-7: the side of a registered frame, null when this table does not answer [type]. */
    fun side(type: String): String? = sides[type]

    fun answer(envelope: Envelope, direct: Boolean): Envelope? = handlers.getValue(envelope.type)(envelope, direct)
}

/** The board-owned frames a daemon answers only when a board registered them. */
object BoardFrames {
    val TYPES: Set<String> = setOf(
        Envelope.TICKET_SAVE, Envelope.TICKET_GET, Envelope.TICKET_LIST, Envelope.TICKET_MOVE, Envelope.TICKET_LINK,
        Envelope.TICKET_UNLINK, Envelope.TICKET_REORDER, Envelope.TICKET_DELETE, Envelope.TICKET_START_NEXT, Envelope.TICKET_UNASSIGN, Envelope.AGENT_QUEUE, Envelope.TICKET_ASSIGN, Envelope.AGENT_SWITCH,
        Envelope.STORY_SAVE, Envelope.STORY_GET, Envelope.STORY_MOVE, Envelope.STORY_LIST,
        Envelope.EPIC_SAVE, Envelope.EPIC_LIST, Envelope.EPIC_BASE,
    )

    /** DP-6: the pipeline frames, owned by the board plugin (module.json `daemon.frames`). */
    val PIPELINE_TYPES: Set<String> = setOf(Envelope.PIPELINE_CONFIG, Envelope.PIPELINE_SHOW, Envelope.PIPELINE_RECORD, Envelope.PIPELINE_CHECK)

    /** DP-7c: the decisions and issues frames, owned by the notes plugin (module.json `daemon.frames`). */
    val KNOWLEDGE_RECORD_TYPES: Set<String> = setOf(
        Envelope.DECISION_ADD, Envelope.DECISION_LIST, Envelope.DECISION_GET,
        Envelope.ISSUE_OPEN, Envelope.ISSUE_ATTEMPT, Envelope.ISSUE_FIX, Envelope.ISSUE_LIST, Envelope.ISSUE_GET, Envelope.ISSUE_PRECHECK,
    )

    /** DP-7c: a decision or issue frame with no plugin answering: a refusal, never a hang. */
    fun noKnowledgeRecords(type: String): Envelope =
        Envelope(Envelope.ERROR, "$type refused: this daemon has no ${if (type.startsWith("decision")) "decisions" else "issues"}")

    /** DP-7f: the memory frames, owned by the notes plugin (module.json `daemon.frames`). */
    val MEMORY_TYPES: Set<String> = setOf(
        Envelope.MEMORY_SEARCH, Envelope.MEMORY_SAVE, Envelope.MEMORY_LIST, Envelope.MEMORY_GET, Envelope.MEMORY_DELETE, Envelope.MEMORY_PURGE_ROLE,
    )

    /** DP-7f: a memory frame with no plugin answering: a refusal, never a hang. */
    fun noMemory(type: String): Envelope = Envelope(Envelope.ERROR, "$type refused: this daemon has no memory")

    /** AR-4b-ii-b: the role frames, owned by the roles plugin (module.json `daemon.frames`). */
    val ROLE_TYPES: Set<String> = setOf(
        Envelope.ROLE_LIST, Envelope.ROLE_SAVE, Envelope.ROLE_DELETE, Envelope.ROLE_BINDING_LIST, Envelope.ROLE_BINDING_GET, Envelope.ROLE_BINDING_SET,
        Envelope.ROLE_TRAITS_LIST, Envelope.ROLE_TRAITS_GET, Envelope.ROLE_TRAITS_SET,
    )

    /** AR-4b-ii-b: a role frame with no plugin answering: a refusal, never a hang. */
    fun noRoles(type: String): Envelope = Envelope(Envelope.ERROR, "$type refused: this daemon has no roles")

    /** DP-6: a pipeline frame with no plugin answering: a refusal, never a hang. */
    fun noPipeline(type: String): Envelope = Envelope(Envelope.ERROR, "$type refused: this daemon has no pipeline")

    /** DP-5h-2: project and workspace frames are core's, registered by core (see `registerCoreProjectFrames`), never board-owned. */
    val CORE_PROJECT_TYPES: Set<String> = setOf(
        Envelope.PROJECT_SAVE, Envelope.PROJECT_GET, Envelope.PROJECT_LIST, Envelope.PROJECT_BASE, Envelope.PROJECT_CONFIG,
        Envelope.WORKSPACE_SAVE, Envelope.WORKSPACE_LIST,
    )

    /** What a board-family frame with no registered handler answers: a refusal, never a hang. */
    fun noBoard(type: String): Envelope = Envelope(Envelope.ERROR, "$type refused: this daemon has no board")
}

/**
 * RUNNER-POOL-7: the side attribute of the core frames answered inline by the connection loop (not through a [FrameTable]). The
 * mailbox is the orchestrator's: an agent on a remote runner mails its colleagues there. Every other inline frame is runner side.
 */
object CoreFrameSides {
    private val orchestrator = setOf(
        Envelope.MAIL_SEND, Envelope.MAIL_READ, Envelope.MAIL_INBOX, Envelope.MAIL_OUTBOX,
        Envelope.MAIL_THREAD, Envelope.MAIL_THREADS, Envelope.MAIL_WAITING,
    )

    fun side(type: String): String? = if (type in orchestrator) FrameSide.ORCHESTRATOR else null
}
