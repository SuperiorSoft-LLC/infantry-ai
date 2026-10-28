package com.superiorsoftllc.agenthq.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PLUGINS-1: how far a module is trusted. Decided by the folder it sits in, never by its own module.json. */
@Serializable
enum class Tier { @SerialName("core") CORE, @SerialName("community") COMMUNITY }

/** One plugin subfolder of the plugins root and the tier of everything in it. */
@Serializable
data class PluginFolder(val path: String, val tier: Tier)

/** The owner's say over one module. */
@Serializable
data class ModuleSetting(
    /** Null = the tier's default: a core module is on, a community module is off until enabled here. */
    val enabled: Boolean? = null,
    /** Services granted to a community module (a core module has every service it declares unless [deny] lists it). */
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    /** Core contributions (by id) this community module may replace. */
    val allowOverride: List<String> = emptyList(),
    /** BLOCKS-INJECTORS J2: node ids this community module may inject into (a smaller grant than [allowOverride]). */
    val allowInject: List<String> = emptyList(),
    /** PLUGIN-ENDPOINTS-1: per endpoint id, the owner's own url for this install (localhost, staging). */
    val endpoints: Map<String, EndpointSetting> = emptyMap(),
)

/** What the demo lock lets a module's daemon access through, on top of the routes the module declared. */
@Serializable
data class DemoLockConfig(val allowRoutes: List<String> = emptyList(), val allowEndpoints: List<String> = emptyList())

/** `<plugins root>/modules.json`: the folders to scan, and the owner's enable list. Editable without a rebuild. */
@Serializable
data class ModulesConfig(
    val folders: List<PluginFolder> = DEFAULT_FOLDERS,
    val modules: Map<String, ModuleSetting> = emptyMap(),
    val demoLock: DemoLockConfig = DemoLockConfig(),
) {
    fun setting(module: String): ModuleSetting = modules[module] ?: ModuleSetting()

    companion object {
        val DEFAULT_FOLDERS = listOf(PluginFolder("core", Tier.CORE), PluginFolder("community", Tier.COMMUNITY))
    }
}

@Serializable
data class EndpointSetting(val url: String? = null)
