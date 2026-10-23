package com.superiorsoftllc.agenthq.attention

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A durable operator-facing record that something needs human attention. */
@Serializable
data class AttentionItem(
    val id: String,
    /** Unix epoch milliseconds. */
    val at: Long,
    val kind: AttentionKind,
    val triggerId: String? = null,
    val ticketId: String? = null,
    val podId: String? = null,
    val sessionId: String? = null,
    val outcome: String,
    val detail: String,
    /** Unix epoch milliseconds, null until an operator acknowledges the item. */
    val ackedAt: Long? = null,
)

/** Why the daemon raised an attention item. Wire names are stable lower snake case. */
@Serializable
enum class AttentionKind(val wireName: String) {
    @SerialName("trigger_tripped") TRIGGER_TRIPPED("trigger_tripped"),
    @SerialName("deadline") DEADLINE("deadline"),
    @SerialName("stalled") STALLED("stalled"),
    @SerialName("ceiling") CEILING("ceiling"),
    @SerialName("launch_blocked") LAUNCH_BLOCKED("launch_blocked"),
    @SerialName("prerequisite_declined") PREREQUISITE_DECLINED("prerequisite_declined"),
    @SerialName("queue_cleared") QUEUE_CLEARED("queue_cleared"),

    /** TRAITS-T6 (contract 1.52.0, 30.sqm): an unattended session ended without its REPORT BACK mail. */
    @SerialName("unreported") UNREPORTED("unreported");

    companion object {
        fun fromWireName(value: String): AttentionKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("unknown attention kind '$value'")
    }
}
