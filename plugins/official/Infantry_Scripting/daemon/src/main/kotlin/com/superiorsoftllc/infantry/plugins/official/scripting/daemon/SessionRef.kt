// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

/**
 * HARNESS-GAP (G21): a session reference a script passes (`ih.chat_note{sessionId=...}`, `ih.session_read{...}`) names ONE session the way
 * `infantry_prompt` does: the full id when it exists, else the one session whose id starts with the reference (the 8-char ids the board prints).
 * A reference that matches nothing, or more than one session, is refused and the refusal names the matches.
 */
internal sealed interface SessionRef {
    data class Found(val id: String) : SessionRef
    data class Refused(val why: String) : SessionRef

    companion object {
        /** [exists] answers for a full id; [allIds] lists every session id, read only when [ref] is not a full id. */
        fun of(ref: String, exists: (String) -> Boolean, allIds: () -> List<String>): SessionRef {
            val requested = ref.trim()
            if (requested.isEmpty()) return Refused("needs sessionId")
            if (exists(requested)) return Found(requested)
            val matches = allIds().filter { it.startsWith(requested) }
            return when (matches.size) {
                0 -> Refused("unknown session $requested")
                1 -> Found(matches.single())
                else -> Refused("session id '$requested' is ambiguous: ${matches.joinToString(", ")}")
            }
        }
    }
}
