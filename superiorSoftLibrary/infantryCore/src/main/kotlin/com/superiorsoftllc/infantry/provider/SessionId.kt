// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pulls a session id out of a streamed JSON line.
 *
 * The two CLIs disagree on the key name and that disagreement is exactly the kind of thing the seam
 * must absorb instead of leaking upward: `opencode --format json` emits `sessionID` (camelCase),
 * `claude --output-format stream-json` emits `session_id` (snake_case). Both are accepted here so a
 * provider does not have to know which one its CLI uses.
 */
internal object SessionId {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val keys = listOf("session_id", "sessionID", "sessionId", "session")

    fun fromLine(line: String): String? {
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        for (key in keys) {
            val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }
}
