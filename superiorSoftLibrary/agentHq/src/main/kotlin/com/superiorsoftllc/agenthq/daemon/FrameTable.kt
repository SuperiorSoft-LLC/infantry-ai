package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope

/**
 * DP-5d: the frame table `frame type -> handler`. The owner of a frame registers it; the connection loop looks a
 * frame up here after the policy / owner / plugin gates, which therefore apply to every registered frame unchanged.
 * A handler gets the request and whether it is `direct` (BUG-MCP-RACE-1: it carried a `requestId`).
 */
class FrameTable {
    private val handlers = LinkedHashMap<String, (Envelope, Boolean) -> Envelope?>()

    fun register(vararg types: String, handler: (envelope: Envelope, direct: Boolean) -> Envelope?) {
        for (type in types) handlers[type] = handler
    }

    fun handles(type: String): Boolean = type in handlers

    fun answer(envelope: Envelope, direct: Boolean): Envelope? = handlers.getValue(envelope.type)(envelope, direct)
}

/** The board-owned frames a daemon answers only when a board registered them. */
object BoardFrames {
    val TYPES: Set<String> = setOf(
        Envelope.TICKET_SAVE, Envelope.TICKET_GET, Envelope.TICKET_LIST, Envelope.TICKET_MOVE, Envelope.TICKET_LINK,
        Envelope.TICKET_UNLINK, Envelope.TICKET_REORDER, Envelope.TICKET_DELETE, Envelope.TICKET_START_NEXT,
        Envelope.STORY_SAVE, Envelope.STORY_GET, Envelope.STORY_MOVE, Envelope.STORY_LIST,
        Envelope.EPIC_SAVE, Envelope.EPIC_LIST, Envelope.EPIC_BASE,
    )

    /** DP-5h-2: project and workspace frames are core's, registered by core (see `registerCoreProjectFrames`), never board-owned. */
    val CORE_PROJECT_TYPES: Set<String> = setOf(
        Envelope.PROJECT_SAVE, Envelope.PROJECT_GET, Envelope.PROJECT_LIST, Envelope.PROJECT_BASE, Envelope.PROJECT_CONFIG,
        Envelope.WORKSPACE_SAVE, Envelope.WORKSPACE_LIST,
    )

    /** What a board-family frame with no registered handler answers: a refusal, never a hang. */
    fun noBoard(type: String): Envelope = Envelope(Envelope.ERROR, "$type refused: this daemon has no board")
}
