// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** PLUGIN-PLATFORMS-1: the platform names a module.json `platforms` may list. */
val PLATFORMS = listOf("desktop", "android", "web")

/** PLUGINS-1: the plugin API version this build speaks. A module that asks for another is refused. RUNTIME-26: 2 (coreShell/appShell and the core tier are gone; every module.json says 2). */
const val PLUGIN_API_VERSION = 2

/**
 * RUNTIME-2: additive revisions (carried into API 2 by RUNTIME-26, so the revision stays 2) under [PLUGIN_API_VERSION] (additions only; a module never has to name it, and an older
 * module still loads). 1 = `Tier.OFFICIAL` and the `official` plugins folder. 2 = a host tab's sub-tabs through
 * `TabContext.SubTabs` / `subTab` / `pickSubTab` (RUNTIME-5). 3 = the `speech.out` and `audio.in` services (VOICE-SEAM-1).
 * 4 = module.json `capabilities` and `emits` (PLUGIN-BRIDGES-1, plugin API 2.2.0). 5 = [PluginScope], the host-owned lifetime
 * of a plugin half (`UiRegistrar.scope`, `DaemonApi.scope`) that makes a live swap clean (PLUGIN-HOTSWAP-1, plugin API 2.3.0).
 * 6 = the core notification bus: [Notification], `Notifications.notify(Notification)` / `present` / `onAction` / `dismiss`
 * and `DaemonApi.notifications` (NOTIFY-CORE-1, plugin API 2.4.0).
 */
const val PLUGIN_API_REVISION = 6

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
    /** JOBFEED-1: false = an optional module: off until the owner enables it (Settings > Plugins), whatever its tier. Default true (core and official are on). */
    val defaultEnabled: Boolean = true,
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
    /**
     * PLUGIN-PLATFORMS-1: the platforms the module works on, from [PLATFORMS] (`desktop`, `android`, `web`). Absent = all.
     * A host on a platform not listed skips the module ("not available on this platform"); an unknown value refuses it.
     */
    val platforms: List<String>? = null,
    /** Daemon routes the module may call, `METHOD /path` (e.g. `GET /health`). */
    val routes: List<String> = emptyList(),
    /** PLUGIN-ENDPOINTS-1: the backends the module may call by id (service `endpoints`). */
    val endpoints: List<EndpointDeclaration> = emptyList(),
    /** Events the module may publish or observe. */
    val events: List<String> = emptyList(),
    /** Platform services the module may use, by [ServiceKey.id]. */
    val services: List<String> = emptyList(),
    val tabs: List<TabDeclaration> = emptyList(),
    /** PLUGINS-3: sections the module adds to the window's side panels (`left`, `right`). */
    val panels: List<PanelDeclaration> = emptyList(),
    /** SLOTS-1: named child slots this module (the host) draws with `ChildSlot`; any module may add `panels` sections to one by name. */
    val slots: List<String> = emptyList(),
    /** PLUGIN-TREE-UI-1: modules this one is a child of, besides the owners of the slots its `panels` fill. */
    val parents: List<String> = emptyList(),
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
     * NS3: an entry is a node id (the module's code registers the tree) or `{"node", "layout"}` (a composition: the tree
     * is the page JSON at `layout`, instantiated through the block-type registry; a community one needs the node in its
     * `allowOverride` grant).
     */
    val fills: List<@Serializable(FillDeclarationSerializer::class) FillDeclaration> = emptyList(),
    /**
     * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s A): config fields per node id the module owns or is granted. Merged with
     * core's into one tree ([com.superiorsoftllc.infantry.ui.plugin.config.ConfigTree]); a second declaration of a key is refused.
     */
    val config: List<ConfigDeclaration> = emptyList(),
    /**
     * P1 step 2 (6s D): the implementations the module registers (`UiRegistrar.implementation`), by registry and key.
     * A config `implementation` field stores the key; an undeclared registration is refused.
     */
    val implementations: List<ImplementationDeclaration> = emptyList(),
    /**
     * NS1 / PB1 (docs/BLOCKS-LAYOUT-DESIGN.md 6t.2): the block TYPES the module registers (`UiRegistrar.blockType`). The
     * config tree holds instances of them; an undeclared registration is refused.
     */
    val blockTypes: List<BlockTypeDeclaration> = emptyList(),
    /** CONFIG-5: the config field type ids the module registers (`UiRegistrar.fieldType`); an undeclared registration is refused. */
    val fieldTypes: List<String> = emptyList(),
    /** NS3 (docs/NORTH-STAR-DESIGN.md 1a, 4c): the daemon capabilities the module needs and the optional ones it wants. */
    val daemon: DaemonCapabilities? = null,
    /**
     * HARNESS-PLUGIN-3 H4 (docs/HARNESS-PLUGINS.md section 3.2): the module ships a harness (a harness API 2.x jar). Read by
     * the daemon only; trust and grants as for a daemon half. Null = no harness.
     */
    val harness: HarnessDeclaration? = null,
    /**
     * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.2): the agents the module contributes to the daemon's agent registry,
     * as data (no jar needed). Read by the daemon only; a community module needs the `agents.contribute` grant.
     */
    val agents: List<AgentDefinition> = emptyList(),
    /** POLICY-1 (docs/POLICY.md section 1): the named actions the module's controls perform; a policy may turn each off. */
    val actions: List<ActionDeclaration> = emptyList(),
    /**
     * PLUGIN-BRIDGES-1 (docs/wiki/Bridges.md): the named functions this module offers to scripts, agents and other plugins, each run
     * by its daemon half (`runs: daemon`, `DaemonApi.capabilities.handle`) or by the client that loaded its UI half (`runs: client`,
     * `UiRegistrar.capability`). Called through the host only (`infantry_capability_call`); an undeclared handler is refused.
     */
    val capabilities: List<CapabilityDeclaration> = emptyList(),
    /**
     * PLUGIN-BRIDGES-1: the named events this module emits (published on the daemon's event bus, so a script hook can subscribe with
     * `on = "<name>"`). A client-side emit of a name not listed here is refused.
     */
    val emits: List<EmitDeclaration> = emptyList(),
    /**
     * GAP-AUDIT-1 (b): per-node flags the module declares on node ids it draws. `{"id": "<node id>", "overridable": false}`
     * locks the node like the shell's own locked set: no module may replace, wrap,
     * place a block beside or inject into it. A community module may lock only ids under its own name (`<name>.`).
     */
    val nodes: List<NodeDeclaration> = emptyList(),
    /**
     * UPDATE-ENGINE-1 (docs/wiki/Updates.md): where THIS module's new versions come from. Null = the module is not updated on
     * its own (it ships with the app). The engine reads it for every installed module, 3rd-party ones included.
     */
    val update: UpdateDeclaration? = null,
) {
    /** NS3: the node ids of [fills]. */
    val fillNodes: List<String> get() = fills.map { it.node }
}

/**
 * NS3: a fill of shell slot [node]; [layout] (a `layout/<name>.json` path in the module folder) when the tree is data.
 * CONFIG-10b: [replaces] names the module whose fill of [node] this one takes over (that module stays loaded, unused); absent = first fill wins.
 */
@Serializable
data class FillDeclaration(val node: String, val layout: String? = null, val replaces: String? = null)

/** GAP-AUDIT-1 (b): a node the module draws; [overridable] false = locked against every other module. */
@Serializable
data class NodeDeclaration(val id: String, val overridable: Boolean = true)

/** NS3: a fill entry is a plain node id string or the object form. */
object FillDeclarationSerializer : JsonTransformingSerializer<FillDeclaration>(FillDeclaration.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonObject(mapOf("node" to element)) else element
}

/**
 * HARNESS-PLUGIN-3 H4: module.json `harness`. The manifest is a truthful index the UI can show without loading code: the
 * plugin must register exactly [ids], and its `pluginId` must be the module name.
 */
@Serializable
data class HarnessDeclaration(
    /** The harness jar, a path inside the module folder (e.g. `harness/build/libs/Acme_EchoHarness-harness.jar`). */
    val jar: String? = null,
    /** The harness API version the jar was built against (`2.0.0`); must match the plugin's own `contractVersion`. */
    val contract: String? = null,
    /** The harness ids the plugin registers (stored ids: sessions, roles, tickets, routing). */
    val ids: List<String> = emptyList(),
    /** Alternative names for an id (`{"pi-agent": "pi"}`); an alias never shadows a registered id. */
    val aliases: Map<String, String> = emptyMap(),
    /** The harness's own CLI tool, when it has one. */
    val cli: HarnessCliDeclaration? = null,
    /** An icon inside the module folder (svg/png), for pickers and pills (H5a: drawn for [ids] and [aliases]). */
    val icon: String? = null,
    /** Setting keys the harness reads through its host. */
    val settings: List<String> = emptyList(),
    /** Host hooks the harness uses: watch, mcp, ask (calls), sandbox, stats, usage (contributions, later). */
    val hooks: List<String> = emptyList(),
)

/**
 * HARNESS-PLUGIN-3 H4: `harness.cli`, the CLI the harness drives; [install] is how the harness installer gets it.
 * H5a: [command] is a bare name searched on PATH (default [id]); [label] is how Settings names it (default [command]).
 */
@Serializable
data class HarnessCliDeclaration(
    val id: String,
    val command: String? = null,
    val install: HarnessCliInstall? = null,
    val label: String? = null,
)

/** `harness.cli.install`: an npm package and the bin script inside it. */
@Serializable
data class HarnessCliInstall(val npm: String? = null, val bin: String? = null)

/** NS3 (1a): `daemon.needs` (refused = the module does not work) and `daemon.wants` (refused = it degrades). */
@Serializable
data class DaemonCapabilities(
    val needs: List<String> = emptyList(),
    val wants: List<String> = emptyList(),
    /** CONFIG-8: the module's daemon-side config, the same schema as [ModuleManifest.config]; only `scope:"daemon"` fields are served. */
    val config: List<ConfigDeclaration> = emptyList(),
    /**
     * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the class implementing the daemon API's `DaemonPlugin`, in [jar]. Both null =
     * the module runs no daemon code (every key below is then ignored).
     */
    val entry: String? = null,
    /** DAEMON-PLUGINS-1: the daemon half's jar, a path inside the module folder (e.g. `daemon/build/libs/Acme_Hello-daemon.jar`). */
    val jar: String? = null,
    /** DAEMON-PLUGINS-1: the lowest daemon API version (semver) the daemon half needs; a newer one refuses the module. */
    val apiAtLeast: String? = null,
    /** DAEMON-PLUGINS-1: the tools the daemon half registers, each with its own policy action. */
    val tools: List<DaemonToolDeclaration> = emptyList(),
    /** DAEMON-PLUGINS-1: the client frames the daemon half answers, each with its own policy action. */
    val frames: List<DaemonFrameDeclaration> = emptyList(),
    /** DAEMON-PLUGINS-1: the hook kinds the daemon half registers (`frameGate`, `httpGate`, `admission`, ...). */
    val hooks: List<String> = emptyList(),
    /** DAEMON-PLUGINS-1: top-level routes (`GET /path`) the daemon half answers; core/official only. */
    val routes: List<String> = emptyList(),
    /** DAEMON-PLUGINS-1: routes answered without the bearer token; core/official only. */
    val publicRoutes: List<String> = emptyList(),
    /** DAEMON-PLUGINS-1: environment variables the daemon half may read. */
    val env: List<String> = emptyList(),
    /** DAEMON-PLUGINS-1: keystore secrets the daemon half may read. */
    val secrets: List<String> = emptyList(),
    /** DAEMON-PLUGINS-1: per-plugin limits; unset fields take the host's defaults. */
    val limits: DaemonPluginLimits = DaemonPluginLimits(),
    /** DAEMON-STORAGE-1: plugin-owned tables; `adopt` lists existing core tables an OFFICIAL module takes over. */
    val storage: DaemonStorageDeclaration = DaemonStorageDeclaration(),
    /**
     * UPDATE-ENGINE-1: the update source TYPES this daemon half registers (`DaemonApi.updates.registerSource`), for example
     * `github`. An undeclared registration is refused; a community module also needs the grant `daemon.updateSource:<type>`.
     */
    val updateSources: List<String> = emptyList(),
    /**
     * RUNNER-POOL-7 (docs/RUNNER-POOL.md 2.3): the side of every frame and tool of this daemon half, `runner` (the default: answered
     * by the daemon the caller talks to) or `orchestrator` (it reads or writes orchestrator state: a runner forwards it from the
     * workers its remote orchestrator started). A frame's or tool's own `side` wins.
     */
    val side: String? = null,
)

/** DAEMON-STORAGE-1: [adopt] = core tables an official module takes over (a community module declaring any is refused). */
@Serializable
data class DaemonStorageDeclaration(val adopt: List<String> = emptyList())

/** DAEMON-PLUGINS-1: one daemon tool: its name, its policy action id and kind, and a one-line description. */
@Serializable
data class DaemonToolDeclaration(
    val name: String,
    val action: String,
    val kind: String = "read",
    val description: String = "",
    /** RUNNER-POOL-7: `runner` | `orchestrator`; null = the daemon half's [DaemonCapabilities.side]. */
    val side: String? = null,
)

/** DAEMON-PLUGINS-1: one plugin-defined frame; [ownerOnly] frames are refused to a community module. */
@Serializable
data class DaemonFrameDeclaration(
    val type: String,
    val action: String,
    val kind: String = "read",
    val ownerOnly: Boolean = false,
    /** RUNNER-POOL-7: `runner` | `orchestrator`; null = the daemon half's [DaemonCapabilities.side]. */
    val side: String? = null,
)

/** DAEMON-PLUGINS-1: a daemon half's limits; null = the host default. [memoryMbHint] is reported, not enforced. */
@Serializable
data class DaemonPluginLimits(
    val callTimeoutMs: Long? = null,
    val toolTimeoutMs: Long? = null,
    val cpuMsPerCall: Long? = null,
    val routeRequestsPerMinute: Int? = null,
    val memoryMbHint: Int? = null,
)

/**
 * NS1 / PB1 (6t.2): a block type, `vendor.name` (core's layout primitives are `layout.row` ...). [fields] are the
 * per-instance config fields (6s A declarations, instantiated on each instance id); [takesChildren] whether an instance
 * hosts children; [needs] the kind of parent it must sit in (`any | row | box | column`); [parents] the node ids or
 * types it may sit in (empty = any); [max] instances per tree (null = no cap); [template] (NS2) the instance is drawn
 * once per item of its parent, never as a fixed instance.
 */
@Serializable
data class BlockTypeDeclaration(
    val type: String,
    val title: String = type,
    val fields: List<ConfigFieldDeclaration> = emptyList(),
    val takesChildren: Boolean = false,
    val needs: String = BlockNeeds.ANY,
    val parents: List<String> = emptyList(),
    val max: Int? = null,
    val template: Boolean = false,
)

/** NS1: the parent kinds a [BlockTypeDeclaration.needs] may name. */
object BlockNeeds {
    const val ANY = "any"
    const val ROW = "row"
    const val BOX = "box"
    const val COLUMN = "column"
    val ALL = setOf(ANY, ROW, BOX, COLUMN)
}

/** P1 step 2: the module registers an implementation under [key] of registry [registry] (e.g. `tabs.sorter`, `byTitle`). */
@Serializable
data class ImplementationDeclaration(val registry: String, val key: String)

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
 * `replace` (the core block is not drawn) or `wrap` (the module draws around the core block, which it gets as `inner`);
 * OVERRIDE-1 adds `before` / `after` (the module's block is drawn next to the node, in its parent; the node is untouched).
 * [node] may also be a path selector (docs/PLUGIN-OVERRIDES.md); [priority]: higher wins a replace and goes first among
 * `before` / `after` (ties: load order).
 */
@Serializable
data class ShellOverrideDeclaration(val node: String, val name: String, val mode: String = "replace", val priority: Int = 0) {
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

/**
 * PLUGIN-ENDPOINTS-1 (D1): one backend the module calls by [id], never by URL. [url] is one origin plus a path pattern (`*` = one
 * segment, `**` = the rest); https only (http for localhost, 127.0.0.1, 10.0.2.2), exact host. The owner may override it per install.
 */
@Serializable
data class EndpointDeclaration(val id: String, val url: String, val methods: List<String> = listOf("GET"), val auth: EndpointAuth = EndpointAuth())

/** [kind] is `none | bearer | header | basic`; [name] is the header name for `header`; [field] the keystore field (D3, later). */
@Serializable
data class EndpointAuth(val kind: String = "none", val field: String? = null, val name: String? = null)

/**
 * PLUGIN-BRIDGES-1: one capability (docs/wiki/Bridges.md). [id] is dotted lower case and unique among installed modules
 * (`readaloud.speak`); [params] and [result] are JSON Schema objects (documentation and the listing; the handler validates).
 * [runs] is [CapabilityRuns.DAEMON] or [CapabilityRuns.CLIENT]. [action] / [kind] gate a call through the active policy
 * preset like a tool's (null action = `capability.<id>`); [grant], when set, is a grant a COMMUNITY plugin must hold to call
 * it (agents and scripts are held by the policy action). [timeoutMs] null = the host default.
 */
@Serializable
data class CapabilityDeclaration(
    val id: String,
    val description: String = "",
    val params: JsonObject? = null,
    val result: JsonObject? = null,
    val runs: String = CapabilityRuns.DAEMON,
    val action: String? = null,
    val kind: String = "write",
    val grant: String? = null,
    val timeoutMs: Long? = null,
) {
    /** The policy action id a call is gated by. */
    val actionId: String get() = action?.takeIf { it.isNotBlank() } ?: "capability.$id"
}

/** PLUGIN-BRIDGES-1: where a capability runs. */
object CapabilityRuns {
    const val DAEMON = "daemon"
    const val CLIENT = "client"
    val ALL = setOf(DAEMON, CLIENT)
    /** Dotted lower-case ids, two segments at least (`vendor.name`), each `[a-z][a-z0-9_-]*`. */
    val ID = Regex("""^[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)+$""")
}

/** PLUGIN-BRIDGES-1: one event a module emits: its dotted [event] name, what it means and the payload it carries (JSON Schema). */
@Serializable
data class EmitDeclaration(val event: String, val description: String = "", val payload: JsonObject? = null)
