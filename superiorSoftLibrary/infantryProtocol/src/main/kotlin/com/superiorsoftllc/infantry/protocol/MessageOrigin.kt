// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract 1.125.0: WHO a transcript turn came from, as data. Set where the turn is created (the mailbox / router, the
 * harness translation layer, the scheduler); never inferred from the turn's text. Carried on the `turn` frame as the
 * optional `origin` field; an absent origin means [Owner], so a frame from an older daemon reads exactly as before.
 *
 * The text a model reads ("[Infantry AI COMMS] message ...") is only the harness-facing rendering of this value, and
 * is produced in one place (`mailbox/MessageSource.kt`). Nothing downstream parses it back.
 *
 * Wire shape: `{"type":"owner"}`, `{"type":"agent","sessionId":..,"agentId":..,"role":..}`,
 * `{"type":"external","senderLabel":..,"channel":..}`, `{"type":"system","kind":"handover"}`.
 * The client twin is `ui/model/MessageOrigin.kt` in infantryUiCore; change both together.
 */
@Serializable
sealed interface MessageOrigin {
    /** The human at a client. The default for any turn that names no origin. */
    @Serializable @SerialName("owner")
    data object Owner : MessageOrigin

    /** Another agent session. [agentId] and [role] are null when the sender's session is gone or has no profile. */
    @Serializable @SerialName("agent")
    data class Agent(val sessionId: String, val agentId: String? = null, val role: String? = null) : MessageOrigin

    /** Something outside Infantry (a webhook, an email bridge, a chat app): shown by [senderLabel] only. */
    @Serializable @SerialName("external")
    data class External(val senderLabel: String, val channel: String? = null) : MessageOrigin

    /** Infantry itself (a handover, a slash command, the scheduler); [kind] is one of [SystemKind]'s wire names. */
    @Serializable @SerialName("system")
    data class System(val kind: String) : MessageOrigin

    /**
     * One human-readable line naming the sender, shared by the CLI, the MCP tools and the harness header so no client
     * reinvents it: `from architect (dd062cba)`, `from owner`, `from <label>`, `from system (handover)`.
     */
    fun label(): String = "from " + when (this) {
        Owner -> "owner"
        is Agent -> {
            val id = (agentId ?: sessionId).take(8)
            if (role.isNullOrBlank()) id else "$role ($id)"
        }
        is External -> senderLabel
        is System -> "system ($kind)"
    }
}

/** The [MessageOrigin.System.kind] values Infantry itself uses; a new kind is a new constant here. */
object SystemKind {
    const val HANDOVER = "handover"
    const val SLASH_COMMAND = "slash_command"
    const val SCHEDULER = "scheduler"
}
