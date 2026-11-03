// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The wire form of an attention item (the `AttentionItem` record clients decode): every key present, absent ids as null. */
internal object AttentionJson {
    fun item(e: AttentionEntry): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(e.id),
            "at" to JsonPrimitive(e.at),
            "kind" to JsonPrimitive(e.kind),
            "triggerId" to (e.triggerId?.let(::JsonPrimitive) ?: JsonNull),
            "ticketId" to (e.ticketId?.let(::JsonPrimitive) ?: JsonNull),
            "podId" to (e.podId?.let(::JsonPrimitive) ?: JsonNull),
            "sessionId" to (e.sessionId?.let(::JsonPrimitive) ?: JsonNull),
            "outcome" to JsonPrimitive(e.outcome),
            "detail" to JsonPrimitive(e.detail),
            "ackedAt" to (e.ackedAt?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    fun itemText(e: AttentionEntry): String = item(e).toString()

    fun listText(items: List<AttentionEntry>): String = JsonArray(items.map<AttentionEntry, JsonElement>(::item)).toString()
}
