// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/**
 * LEGACY ADAPTER -- REMOVE when no transcript recorded before contract 1.125.0 can still be replayed (a daemon older
 * than 1.125.0 builds the turn as plain text: `[Infantry AI COMMS] message <id> from <sender> · thread ...`).
 *
 * This is the ONLY place a turn's text is read to learn its origin, and it runs from exactly one call: the `turn`
 * reducer in `Model.kt`, and only when the frame carries no `origin`. A frame with an origin never reaches it.
 * Nothing else in any client may match on message text.
 */
internal object LegacyOriginAdapter {
    private const val HEADER = "[Infantry AI COMMS] message "
    private val FROM = Regex("""^\S+ from (\S+)""")
    private val OWNER_HANDLES = setOf("human", "owner", "user")

    /** The origin a pre-1.125 turn [text] implies: its mail header's sender, else [MessageOrigin.Owner]. */
    fun originOf(text: String): MessageOrigin {
        if (!text.startsWith(HEADER)) return MessageOrigin.Owner
        val sender = FROM.find(text.removePrefix(HEADER).lineSequence().first())?.groupValues?.get(1)
            ?: return MessageOrigin.Owner
        return when {
            sender.lowercase() in OWNER_HANDLES -> MessageOrigin.Owner
            sender.equals("system", ignoreCase = true) -> MessageOrigin.System("mail")
            // A session id is a long uuid-like token; any other handle is an outside sender.
            UUID_LIKE.matches(sender) -> MessageOrigin.Agent(sessionId = sender)
            else -> MessageOrigin.External(sender, "mail")
        }
    }

    private val UUID_LIKE = Regex("""[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{8,}""")
}
