package com.superiorsoftllc.agenthq.daemon.api.attention

/**
 * ATTN-1 (DaemonApi additive minor; docs/DAEMON-CORE.md section 8): attention as a provided service, published in the AR-5
 * registry (`services.find(Attention::class)`). Core registers a local provider over its attention store. Usage, the mailbox
 * monitors, triggers and the board runtime raise items through it and never import the attention package; with no provider an
 * item is dropped silently (logged once).
 */
interface Attention {
    /**
     * Raises one durable operator-facing item of [kind] (an [AttentionKinds] wire name). Returns the new item's id, or null
     * when no provider took it.
     */
    fun raise(
        kind: String,
        triggerId: String? = null,
        ticketId: String? = null,
        podId: String? = null,
        sessionId: String? = null,
        outcome: String,
        detail: String,
    ): String?

    /** Resolves (acknowledges) the item [id]; false = no such item. */
    fun resolve(id: String): Boolean

    /** The items, oldest first. [project] null = all; the local provider keeps no project key yet and returns all of them. */
    fun list(project: String? = null): List<AttentionEntry>
}

/** One attention item as the service shows it ([kind] is an [AttentionKinds] wire name). */
data class AttentionEntry(
    val id: String,
    val at: Long,
    val kind: String,
    val triggerId: String? = null,
    val ticketId: String? = null,
    val podId: String? = null,
    val sessionId: String? = null,
    val outcome: String,
    val detail: String,
    val ackedAt: Long? = null,
)

/** The stable lower-snake-case kind names (identical to the wire names of the attention records). */
object AttentionKinds {
    const val TRIGGER_TRIPPED = "trigger_tripped"
    const val DEADLINE = "deadline"
    const val STALLED = "stalled"
    const val CEILING = "ceiling"
    const val LAUNCH_BLOCKED = "launch_blocked"
    const val PREREQUISITE_DECLINED = "prerequisite_declined"
    const val QUEUE_CLEARED = "queue_cleared"
    const val UNREPORTED = "unreported"
    const val SESSION_FAILED = "session_failed"
}
