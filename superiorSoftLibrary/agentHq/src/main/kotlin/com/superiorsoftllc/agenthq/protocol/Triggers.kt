package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.14.0 (story 9.5 wiring): what a trigger does when a run starts. Served: `start_next`
 * (hand out the top startable ticket, optionally only one assigned to [agentId] -- a profile id or
 * `role:<role>`) and, since 1.41.0, `prompt` (start a session for [agentId], REQUIRED, whose first message
 * is [prompt], with no ticket). A `pod` action is specified (PHASE9-SPEC 4.1) but not served yet, and is
 * refused rather than accepted and ignored; so is any other kind.
 */
@Serializable
data class TriggerAction(
    val kind: String = "start_next",
    val agentId: String? = null,
    /** Contract 1.41.0: for kind `prompt`, the first message of the session the trigger starts; non-blank, at most [MAX_PROMPT_CHARS]. Refused on any other kind. */
    val prompt: String? = null,
) {
    companion object {
        const val MAX_PROMPT_CHARS = 4000
    }
}

/**
 * Contract 1.14.0: the `trigger_save` payload, decoded strictly. No [id] creates a trigger; an [id]
 * edits it (and keeps its state). Served kinds: `manual`, `schedule`, `ship`, `webhook` (since P9.6, see [webhookSecret]). `pod` actions are specified
 * but not served yet.
 */
@Serializable
data class TriggerSave(
    val id: String? = null,
    val name: String,
    val kind: String = "manual",
    val enabled: Boolean = true,
    /** Fixed coalescing window opened by the first event; 0 = start immediately (events mid-run still fold). */
    val coalesceMs: Long = 120_000,
    /** At most [ceilingRuns] starts per [ceilingWindowMs]; an event past it is refused and trips the trigger. */
    val ceilingRuns: Int = 6,
    val ceilingWindowMs: Long = 3_600_000,
    val action: TriggerAction = TriggerAction(),
    /** Contract 1.16.0: for kind `schedule`, the period between due events in ms; null = never due. */
    val intervalMs: Long? = null,
    /**
     * P9.6: for kind `webhook`, the per-hook HMAC secret (`POST /hooks/{id}` signs with it). REQUIRED on create,
     * 32..256 characters, no whitespace or control characters; on edit, absent keeps the stored one. WRITE-ONLY:
     * it is never echoed in a [TriggerView], an event or a log line. Refused on any other kind.
     */
    val webhookSecret: String? = null,
) {
    companion object {
        fun decode(text: String): TriggerSave = StrictJson.decode(serializer(), text, "trigger_save payload")
    }
}

/**
 * Contract 1.14.0: a trigger as the daemon holds it (`trigger` / `triggers`). [state] is `armed`,
 * `running`, `tripped` or `disabled`; a tripped trigger carries [trippedAt] and [trippedReason] and starts
 * nothing until `trigger_arm`.
 */
@Serializable
data class TriggerView(
    val id: String,
    val name: String,
    val kind: String,
    val action: TriggerAction,
    val enabled: Boolean,
    val state: String,
    val coalesceMs: Long,
    val ceilingRuns: Int,
    val ceilingWindowMs: Long,
    val createdAt: Long,
    val trippedAt: Long? = null,
    val trippedReason: String? = null,
    /** Contract 1.16.0: for kind `schedule`, the stored period in ms; null = not timed (or never due). */
    val intervalMs: Long? = null,
)

/**
 * Contract 1.14.0: one durable event row (`trigger_events`): every event gets one, oldest first, the last
 * 500 per trigger kept. [disposition] is lower snake case: `started`, `folded`, `followup_folded`,
 * `dropped_after_failure`, `refused_ceiling`, `suppressed_tripped`, `suppressed_disabled`, `duplicate`,
 * `missed`, `nothing_to_start`, `refused_launch_gate`, `wont_fix`.
 */
@Serializable
data class TriggerEventView(
    val eventId: String,
    val triggerId: String,
    val receivedAt: Long,
    val source: String,
    val disposition: String,
    val deliveryId: String? = null,
    val digest: String? = null,
)
