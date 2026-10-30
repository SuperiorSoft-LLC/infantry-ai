package com.superiorsoftllc.agenthq.daemon.plugins.manager

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * PLUGIN-MANAGER-1 (docs/PLUGIN-MANAGER.md sections 2-3): where a plugin came from. A label, never a trust level: the tier
 * (what the code may do) is still decided by the folder and the signature. A class never raises a tier.
 */
enum class PluginClass {
    OFFICIAL, COMMUNITY, CUSTOM;

    val label: String get() = name.lowercase()

    companion object {
        fun of(text: String?): PluginClass = entries.firstOrNull { it.label == text?.trim()?.lowercase() } ?: CUSTOM
    }
}

/** Version ranges (`SemVer` range syntax of the plugin host) the version works with; null = any. */
@Serializable
data class ApiRanges(val plugin: String? = null, val daemon: String? = null, val harness: String? = null)

/** One released version of a plugin: a stable [url] (https, file, or relative to the catalog), its [sha256] pin and the grants it asks for. */
@Serializable
data class CatalogVersion(
    val version: String,
    val api: ApiRanges = ApiRanges(),
    /** Exactly [RequestedGrants.of] of the package's module.json; the installer recomputes it, so a catalog cannot under-report. */
    val grants: List<String> = emptyList(),
    val url: String,
    val size: Long? = null,
    val sha256: String,
    val signer: String? = null,
    val published: String? = null,
    val notes: String? = null,
)

@Serializable
data class CatalogEntry(
    /** The module name (`ModuleManifest.name`), the folder name and the `modules.json` key. */
    val id: String,
    val description: String = "",
    /** Plain tags; stack names are tags and the manager never interprets them. */
    val tags: List<String> = emptyList(),
    /** An SPDX id; absent = shown as "licence not stated". */
    val license: String? = null,
    val homepage: String? = null,
    val versions: List<CatalogVersion> = emptyList(),
)

/** `catalog.json`, schema 1: static data, no code. */
@Serializable
data class Catalog(
    val schema: Int = 1,
    val id: String = "",
    @SerialName("class") val cls: String? = null,
    val generated: String? = null,
    val plugins: List<CatalogEntry> = emptyList(),
)

/** One catalog source (data, in `<plugins root>/sources.json`): a catalog [url] (http(s)/file) or a [dir] (catalog.json, or bare `.ihq` files). */
@Serializable
data class CatalogSource(
    val id: String,
    @SerialName("class") val cls: String = "custom",
    val url: String? = null,
    val dir: String? = null,
    val enabled: Boolean = true,
) {
    val pluginClass: PluginClass get() = PluginClass.of(cls)
}

@Serializable
data class SourcesFile(val sources: List<CatalogSource> = emptyList())

/** `<plugins root>/installed.json`: what the manager installed, from where, and what was consented. */
@Serializable
data class LedgerRow(
    val version: String,
    @SerialName("class") val cls: String,
    val tier: String,
    val source: String,
    val sha256: String,
    val signer: String? = null,
    /** The grants the owner consented to at the last install/update/grants change. */
    val granted: List<String> = emptyList(),
    /** Versions kept under `_versions/<id>/`, newest last. */
    val history: List<String> = emptyList(),
    val license: String? = null,
)

@Serializable
data class Ledger(val installed: Map<String, LedgerRow> = emptyMap())

internal val ManagerJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

/**
 * One function for every caller (CLI, MCP, catalog validator, update diff): every grant [m] would need as a community
 * module. Service grants go to modules.json `allow`; `override:<node>` to `allowOverride`; `inject:<node>` to `allowInject`.
 * Matches what [com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost] and `HarnessModules` check.
 */
object RequestedGrants {
    const val OVERRIDE = "override:"
    const val INJECT = "inject:"

    fun of(m: ModuleManifest): List<String> = buildList {
        addAll(m.services)
        val d = m.daemon
        if (d != null && (!d.entry.isNullOrBlank() || !d.jar.isNullOrBlank())) {
            add("daemon.code")
            if (d.tools.isNotEmpty()) add("daemon.tool")
            if (d.frames.isNotEmpty()) add("daemon.frame")
            d.hooks.forEach { add("daemon.hook.$it") }
            d.env.forEach { add("daemon.env:$it") }
            d.secrets.forEach { add("daemon.secret:$it") }
        }
        val h = m.harness
        if (h != null) {
            add("harness.code")
            if (h.cli != null) add("harness.cli")
            if (h.cli?.install?.npm != null) add("harness.cli.install")
            h.hooks.forEach { add("harness.hook.$it") }
            h.settings.forEach { add("harness.setting:$it") }
        }
        m.fills.forEach { add(OVERRIDE + it.node) }
        m.shell.forEach { add(OVERRIDE + it.nodeId) }
        m.inject.forEach { add(INJECT + it.targetId) }
    }.distinct().sorted()

    /** The three modules.json lists a consent set splits into. */
    fun split(granted: Collection<String>): Triple<List<String>, List<String>, List<String>> = Triple(
        granted.filter { !it.startsWith(OVERRIDE) && !it.startsWith(INJECT) }.sorted(),
        granted.filter { it.startsWith(OVERRIDE) }.map { it.removePrefix(OVERRIDE) }.sorted(),
        granted.filter { it.startsWith(INJECT) }.map { it.removePrefix(INJECT) }.sorted(),
    )
}
