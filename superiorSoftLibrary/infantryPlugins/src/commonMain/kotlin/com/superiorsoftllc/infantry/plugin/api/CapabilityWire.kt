// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * PLUGIN-BRIDGES-1 (docs/wiki/Bridges.md): the frames between the daemon and a client that hosts client-side capabilities (the
 * UI halves it loaded). Plain JSON in the envelope's `data`; both ends use these types, so the daemon and every client agree.
 *
 * - [HOST] client -> daemon: what this connection hosts now ([CapabilityHosting]); each one replaces the last, empty = nothing.
 * - [CALL] daemon -> client: run one capability ([CapabilityCallFrame]).
 * - [RESULT] client -> daemon: its answer ([CapabilityResultFrame]), matched by `callId`.
 * - [EVENT] client -> daemon: a module's declared event ([CapabilityEventFrame]), published on the daemon's event bus.
 */
object CapabilityWire {
    const val HOST = "capability_host"
    const val CALL = "capability_call"
    const val RESULT = "capability_result"
    const val EVENT = "capability_event"
    val CLIENT_FRAMES = setOf(HOST, RESULT, EVENT)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(h: CapabilityHosting): String = json.encodeToString(CapabilityHosting.serializer(), h)
    fun encode(c: CapabilityCallFrame): String = json.encodeToString(CapabilityCallFrame.serializer(), c)
    fun encode(r: CapabilityResultFrame): String = json.encodeToString(CapabilityResultFrame.serializer(), r)
    fun encode(e: CapabilityEventFrame): String = json.encodeToString(CapabilityEventFrame.serializer(), e)

    fun hosting(data: String): CapabilityHosting? = runCatching { json.decodeFromString(CapabilityHosting.serializer(), data) }.getOrNull()
    fun call(data: String): CapabilityCallFrame? = runCatching { json.decodeFromString(CapabilityCallFrame.serializer(), data) }.getOrNull()
    fun result(data: String): CapabilityResultFrame? = runCatching { json.decodeFromString(CapabilityResultFrame.serializer(), data) }.getOrNull()
    fun event(data: String): CapabilityEventFrame? = runCatching { json.decodeFromString(CapabilityEventFrame.serializer(), data) }.getOrNull()
}

/** What one client hosts: its [platform] (`desktop`, `android`, `web`), a display [label] and, per module, the client-side capabilities it has handlers for. */
@Serializable
data class CapabilityHosting(
    val platform: String = "",
    val label: String = "",
    val modules: List<HostedModule> = emptyList(),
)

/** One module's client-side [capabilities] (only those with a registered handler) and its declared [emits]. */
@Serializable
data class HostedModule(
    val module: String,
    val capabilities: List<CapabilityDeclaration> = emptyList(),
    val emits: List<EmitDeclaration> = emptyList(),
)

@Serializable
data class CapabilityCallFrame(val callId: String, val capability: String, val args: JsonObject = JsonObject(emptyMap()))

/** [ok] with [result], or not ok with [error]. */
@Serializable
data class CapabilityResultFrame(val callId: String, val ok: Boolean, val result: JsonElement? = null, val error: String? = null)

@Serializable
data class CapabilityEventFrame(val module: String, val event: String, val payload: JsonObject = JsonObject(emptyMap()))
