// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.capabilities

import com.superiorsoftllc.infantry.daemon.api.Caller
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * PLUGIN-BRIDGES-1 (DaemonApi 1.48.0, additive; docs/wiki/Bridges.md): the capability bridge. A module declares capabilities in
 * module.json `capabilities`; its daemon half answers the `runs: daemon` ones here ([handle]), its UI half the `runs: client` ones
 * on the clients that loaded it. Every installed capability is callable, under the same gates, by scripts and agents
 * (`infantry_capability_call`) and by other plugins ([call]): never by class access across plugins.
 */
interface Capabilities {
    /**
     * Answers capability [id], which this module declares with `runs: daemon`; anything else throws [IllegalStateException]. One
     * handler per id. Closing the handle, or unloading the plugin, removes it. The handler runs under the plugin's guard and the
     * capability's timeout.
     */
    fun handle(id: String, handler: CapabilityHandler): AutoCloseable

    /**
     * Calls capability [id] through the host, as this plugin: the policy action of the capability, the declared `grant` for a community
     * caller, then the daemon handler or a client host (see [CapabilityTarget]). Never throws for a refusal: the answer is a
     * [CapabilityResult.Failed]. Blocks until the answer or the capability's timeout.
     */
    fun call(id: String, args: JsonObject = JsonObject(emptyMap()), target: String? = null): CapabilityResult

    /** Every capability installed now (daemon and client side), with where it can run right now. */
    fun list(): List<CapabilityInfo>
}

/** One daemon-side capability's code. Return the result as JSON; throw to fail the call (the message is the error). */
fun interface CapabilityHandler {
    fun call(call: CapabilityCall): JsonElement
}

/** What a handler is called with: the arguments, who called (the caller's connection identity) and, for a plugin, which module. */
data class CapabilityCall(
    val capability: String,
    val args: JsonObject,
    val caller: Caller,
    /** The calling module for a plugin-to-plugin call, `session:<id>` / `script:<name>` / `cli` otherwise. */
    val source: String = "",
)

/** The answer of [Capabilities.call]. */
sealed interface CapabilityResult {
    /** [value] from [host] (`daemon:<module>` or the client host id); for target `all`, a JSON array of `{host, ok, result|error}`. */
    data class Ok(val value: JsonElement, val host: String) : CapabilityResult
    data class Failed(val error: String) : CapabilityResult
}

/**
 * One installed capability. [runs] `daemon` or `client`; [hosts] the client hosts that can run a client-side one now (empty = no
 * host: a call fails at once). [available] = a call could run now.
 */
data class CapabilityInfo(
    val id: String,
    val module: String,
    val description: String,
    val runs: String,
    val params: JsonObject?,
    val result: JsonObject?,
    val action: String,
    val grant: String?,
    val hosts: List<CapabilityHostInfo> = emptyList(),
    val available: Boolean = false,
)

/** A connected client hosting client-side capabilities: its [id] (a call's `target`), platform, label and last activity. */
data class CapabilityHostInfo(val id: String, val platform: String, val label: String, val lastActiveAt: Long)

/**
 * The `target` of a call to a client-side capability. Null / blank = [DEFAULT]: the most recently active DESKTOP host, else the
 * most recently active host of any platform. [ALL] = every host (the result is an array). Anything else is one host id
 * ([CapabilityHostInfo.id]). A daemon-side capability ignores the target.
 */
object CapabilityTarget {
    const val DEFAULT = "default"
    const val ALL = "all"
}

/** A daemon without the bridge: nothing installed, every call fails. */
object NoCapabilities : Capabilities {
    override fun handle(id: String, handler: CapabilityHandler): AutoCloseable = error("this daemon has no capability bridge")
    override fun call(id: String, args: JsonObject, target: String?): CapabilityResult = CapabilityResult.Failed("this daemon has no capability bridge")
    override fun list(): List<CapabilityInfo> = emptyList()
}
