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
    /**
     * Load order only: modules that, when they load, load before this one. A name here that is absent or off is
     * ignored. What this module cannot run without goes in [dependencies].
     */
    val sequence: List<String> = emptyList(),
    /**
     * PLUGINS-2: modules this one cannot run without, each with a version range. A missing, disabled or out-of-range
     * dependency refuses this module at load, and Settings will not enable it until they are enabled. Each one also
     * loads first. Separate from [sequence] (order) and from [services] (platform services).
     */
    val dependencies: List<DependencyDeclaration> = emptyList(),
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
    /** PLUGINS-3: sections the module adds to the window's side panels (`left`, `right`). */
    val panels: List<PanelDeclaration> = emptyList(),
    val preferences: List<PreferenceDeclaration> = emptyList(),
    val interceptors: List<InterceptorDeclaration> = emptyList(),
    val observers: List<ObserverDeclaration> = emptyList(),
    val blocks: List<BlockDeclaration> = emptyList(),
    /** BLOCKS-LAYOUT L5: overrides of core shell nodes (`infantry.shell.*`), by node id. Node ids are API. */
    val shell: List<ShellOverrideDeclaration> = emptyList(),
    /** BLOCKS-INJECTORS J2: blocks the module adds among a node's children (`infantry.shell.*`), by node id. */
    val inject: List<InjectDeclaration> = emptyList(),
    /**
     * S4 (drop-in path): the shell's empty slots (`infantry.shell.*` node ids) this module fills with a tree of its own.
     * Core tier only; a fill goes in before the overrides and injections, so their node ids are addressable as if core drew them.
     */
    val fills: List<String> = emptyList(),
)

/** PLUGINS-2: module [id] (`Vendor_Module`) must be installed, enabled and at a version in [version] (e.g. `>=1.2 <2`). */
@Serializable
data class DependencyDeclaration(val id: String, val version: String = "*")

/** A tab the module contributes (the first contribution type; a panel or a whole screen come the same way later). */
@Serializable
data class TabDeclaration(val id: String, val container: String)

/**
 * PLUGINS-3: a section the module adds to side panel [panel] (`left` or `right`). Tabs in the right panel are [tabs]
 * with container `right`; a section is a block drawn above or below them.
 */
@Serializable
data class PanelDeclaration(val id: String, val panel: String)

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

/**
 * BLOCKS-LAYOUT L5: the module's code [name] (registered with `UiRegistrar.shell`) takes over core shell node [node]
 * (a node id such as `infantry.shell.footer`, or its address `infantry.shell#infantry.shell.footer`). [mode] is
 * `replace` (the core block is not drawn) or `wrap` (the module draws around the core block, which it gets as `inner`).
 */
@Serializable
data class ShellOverrideDeclaration(val node: String, val name: String, val mode: String = "replace") {
    /** The bare node id, whichever form [node] uses. */
    val nodeId: String get() = node.removePrefix("infantry.shell#")
}

/**
 * BLOCKS-INJECTORS J2 (docs/BLOCKS-INJECTORS.md section 2a): the module's code [name] (registered with
 * `UiRegistrar.inject`) is drawn as a child `<module>.<name>` of node [into] (a node id, or its address
 * `infantry.shell#<id>`). [at] is `first | last | before:<child id> | after:<child id>`; within one anchor a lower
 * [order] goes first, then load order, then name.
 */
@Serializable
data class InjectDeclaration(val into: String, val name: String, val at: String = "last", val order: Int = 0) {
    /** The bare node id, whichever form [into] uses. */
    val targetId: String get() = into.removePrefix("infantry.shell#")
}
