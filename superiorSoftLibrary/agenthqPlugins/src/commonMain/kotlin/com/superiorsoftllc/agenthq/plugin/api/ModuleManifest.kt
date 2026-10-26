package com.superiorsoftllc.agenthq.plugin.api

import kotlinx.serialization.Serializable

/** PLUGINS-1: the plugin API version this build speaks. A module that asks for another is refused. */
const val PLUGIN_API_VERSION = 1

/**
 * A module's module.json: who it is, what it needs loaded first, and everything it contributes or uses. Unknown keys
 * are ignored, so a newer module.json still parses. Declarations are the contract: the host refuses a contribution, a
 * route, an event or a service the module did not declare.
 */
@Serializable
data class ModuleManifest(
    /** `Vendor_Module`, the same as the module's folder name. */
    val name: String,
    val version: String,
    val apiVersion: Int = PLUGIN_API_VERSION,
    val author: String? = null,
    /** Required for a community module (the loader refuses one without it); the value is the module's own. */
    val license: String? = null,
    val description: String = "",
    /** A tier claimed in the file. Never trusted: the folder decides the tier; a mismatch is logged and ignored. */
    val tier: String? = null,
    /** Modules that must load before this one. */
    val sequence: List<String> = emptyList(),
    /** JVM only: the class implementing the module's plugin interface, and the jar (relative to the folder) holding it. */
    val entry: String? = null,
    val jar: String? = null,
    /** Daemon routes the module may call, `METHOD /path` (e.g. `GET /health`). */
    val routes: List<String> = emptyList(),
    /** Events the module may publish or observe. */
    val events: List<String> = emptyList(),
    /** Platform services the module may use, by [ServiceKey.id]. */
    val services: List<String> = emptyList(),
    val tabs: List<TabDeclaration> = emptyList(),
    val preferences: List<PreferenceDeclaration> = emptyList(),
    val interceptors: List<InterceptorDeclaration> = emptyList(),
    val observers: List<ObserverDeclaration> = emptyList(),
    val blocks: List<BlockDeclaration> = emptyList(),
)

/** A tab the module contributes (the first contribution type; a panel or a whole screen come the same way later). */
@Serializable
data class TabDeclaration(val id: String, val container: String)

/** The module replaces contribution [replace] (by id) with its own contribution [with]. */
@Serializable
data class PreferenceDeclaration(val replace: String, val with: String)

/** A hook named [name] on contribution [target]; [kind] is before, after or around. */
@Serializable
data class InterceptorDeclaration(val target: String, val name: String, val kind: String = "around")

/** An observer named [name] of event [event]. */
@Serializable
data class ObserverDeclaration(val event: String, val name: String)

/** A block named [name] the module adds to slot [slot]. */
@Serializable
data class BlockDeclaration(val slot: String, val name: String, val order: Int = 0)
