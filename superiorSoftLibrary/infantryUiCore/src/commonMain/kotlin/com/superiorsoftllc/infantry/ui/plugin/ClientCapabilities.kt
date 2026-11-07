// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.CapabilityCallFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityEventFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityHosting
import com.superiorsoftllc.infantry.plugin.api.CapabilityResultFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityRuns
import com.superiorsoftllc.infantry.plugin.api.CapabilityWire
import com.superiorsoftllc.infantry.plugin.api.HostedModule
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * PLUGIN-BRIDGES-1 (docs/wiki/Bridges.md): a UI half's answer to one of its `runs: client` capabilities. Return the result as JSON;
 * throw to fail the call (the message is the error). Runs on the window's scope; the daemon's timeout bounds the wait.
 */
fun interface ClientCapabilityHandler {
    suspend fun call(args: JsonObject): JsonElement
}

/** PLUGIN-BRIDGES-1: how a UI half publishes an event it declares in module.json `emits` (the daemon's event bus; hooks hear it). */
fun interface CapabilityEvents {
    /** Publishes [event] (a declared full name, e.g. `vendor.spoken`) with [payload]; dropped while no daemon link is live. */
    fun emit(event: String, payload: JsonObject)
}

/**
 * PLUGIN-BRIDGES-1: the window's side of the bridge. [run] tells the daemon what the loaded modules host (`capability_host`),
 * answers its `capability_call` frames with the modules' [InfantryModule.capabilities], and carries their [CapabilityEvents].
 * One window link at a time ([sink]); a reconnect runs it again, which announces again.
 */
object ClientCapabilityHost {
    /** Where the modules' events go now: the live link's sender, null while none. */
    private var sink: ((Envelope) -> Boolean)? = null

    /** `desktop`, `android` or `web`, from [platform] ([com.superiorsoftllc.infantry.ui.platformName]). */
    fun platformOf(platform: String): String = when {
        platform.startsWith("Android", ignoreCase = true) -> "android"
        platform.startsWith("Web", ignoreCase = true) -> "web"
        else -> "desktop"
    }

    /** What [modules] host: per module, its declared client-side capabilities that have a handler, and its declared events. */
    fun hosting(modules: List<InfantryModule>, platform: String, label: String): CapabilityHosting = CapabilityHosting(
        platform = platform,
        label = label,
        modules = modules.mapNotNull { m ->
            val caps = m.manifest.capabilities.filter { it.runs == CapabilityRuns.CLIENT && it.id in m.capabilities }
            if (caps.isEmpty() && m.manifest.emits.isEmpty()) null else HostedModule(m.manifest.name, caps, m.manifest.emits)
        },
    )

    /** The answer to [call]: the handler of the module hosting it, its failure as the error, or "not hosted". */
    suspend fun answer(modules: List<InfantryModule>, call: CapabilityCallFrame): CapabilityResultFrame {
        val handler = modules.firstNotNullOfOrNull { it.capabilities[call.capability] }
            ?: return CapabilityResultFrame(call.callId, ok = false, error = "this client does not host ${call.capability}")
        return try {
            CapabilityResultFrame(call.callId, ok = true, result = handler.call(call.args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CapabilityResultFrame(call.callId, ok = false, error = e.message ?: e::class.simpleName ?: "failed")
        }
    }

    /**
     * Hosts [modules] on one live link until cancelled: announces, then answers each call on its own coroutine. [send] is the
     * link's sender; [frames] its incoming frames. Hosts nothing (sends nothing) when no module hosts or emits anything.
     */
    suspend fun run(modules: List<InfantryModule>, platform: String, label: String, frames: Flow<Envelope>, send: suspend (Envelope) -> Boolean, trySend: (Envelope) -> Boolean) {
        val hosting = hosting(modules, platform, label)
        if (hosting.modules.isEmpty()) return
        sink = trySend
        try {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    frames.collect { env ->
                        if (env.type != CapabilityWire.CALL) return@collect
                        val call = CapabilityWire.call(env.data) ?: return@collect
                        launch { send(Envelope(CapabilityWire.RESULT, CapabilityWire.encode(answer(modules, call)))) }
                    }
                }
                send(Envelope(CapabilityWire.HOST, CapabilityWire.encode(hosting)))
            }
        } finally {
            if (sink === trySend) sink = null
        }
    }

    /** The [CapabilityEvents] of [module]: only an event its module.json `emits` declares goes out. */
    internal fun eventsOf(module: String, declared: Set<String>): CapabilityEvents = CapabilityEvents { event, payload ->
        if (event in declared) sink?.invoke(Envelope(CapabilityWire.EVENT, CapabilityWire.encode(CapabilityEventFrame(module, event, payload))))
    }
}
