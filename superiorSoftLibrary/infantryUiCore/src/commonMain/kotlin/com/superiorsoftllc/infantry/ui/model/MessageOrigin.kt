package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract 1.125.0: who a transcript turn came from, as the daemon sends it on the `turn` frame's optional `origin`.
 * Absent means [Owner] (a pre-1.125 daemon, or an owner turn). Clients render from this value alone and never from the
 * turn's text; adding a kind here is a compile error in `OriginRenderer.kt` until every client handles it.
 *
 * The daemon twin is `protocol/MessageOrigin.kt` in infantry; change both together.
 */
@Serializable
sealed interface MessageOrigin {
    @Serializable @SerialName("owner")
    data object Owner : MessageOrigin

    /** Another agent session; [role] picks the portrait, [agentId] / [sessionId] name it when the role is unknown. */
    @Serializable @SerialName("agent")
    data class Agent(val sessionId: String, val agentId: String? = null, val role: String? = null) : MessageOrigin

    /** Something outside Infantry: shown by [senderLabel] only, never with a portrait. */
    @Serializable @SerialName("external")
    data class External(val senderLabel: String, val channel: String? = null) : MessageOrigin

    /** Infantry itself ([kind]: `handover`, `slash_command`, `scheduler`, `mail`...): label only, no portrait. */
    @Serializable @SerialName("system")
    data class System(val kind: String) : MessageOrigin
}
