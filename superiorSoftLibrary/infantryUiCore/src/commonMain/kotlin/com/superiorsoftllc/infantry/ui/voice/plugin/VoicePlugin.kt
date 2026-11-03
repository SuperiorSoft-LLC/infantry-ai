// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice.plugin

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * VOICE-PLUGIN-1: the plugin API a voice plugin's `plugin.json` names. Exact match, like the harness registry's
 * `HARNESS_CONTRACT_VERSION`: a manifest naming another version is ignored and the OS voice speaks.
 */
const val VOICE_PLUGIN_API = "1.0.0"

/**
 * VOICE-PLUGIN-1: a voice plugin's `plugin.json`. The core only READS this; it loads no plugin code. [entry] is an argv
 * (never a shell) run as a separate short-lived process per alert: the text goes on its stdin, raw PCM ([output]) comes
 * back on its stdout, and it must exit within [timeoutMs]. `{dir}` in an argv token is the plugin's own folder.
 */
@Serializable
data class VoicePluginManifest(
    val id: String,
    val name: String,
    val version: String,
    /** `tts` (later `stt`). */
    val kind: String,
    /** Must equal [VOICE_PLUGIN_API]. */
    val pluginApi: String,
    /** `windows-x64`, `macos-arm64`, `linux-x64`, ... (see the JVM store's platform id). */
    val platforms: List<String>,
    val entry: List<String>,
    val output: VoicePluginOutput = VoicePluginOutput(),
    val timeoutMs: Long = 10_000,
    val assets: List<VoicePluginAsset> = emptyList(),
    /** What the user is told before Install: every licence that applies, the bundled parts included. */
    val license: String = "",
    val description: String = "",
) {
    /** What Install downloads, in bytes. */
    val downloadBytes: Long get() = assets.sumOf { it.size }
}

/** The audio a plugin writes to stdout: little-endian signed 16-bit PCM. */
@Serializable
data class VoicePluginOutput(val format: String = "pcm-s16le", val sampleRate: Int = 22_050, val channels: Int = 1)

/**
 * One downloaded file, stored at [path] under the plugin folder and verified by [size] and [sha256] before the plugin is
 * installed. [unpack] = a zip that is extracted into the plugin folder (then deleted).
 */
@Serializable
data class VoicePluginAsset(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val license: String = "",
    val unpack: Boolean = false,
)

object VoicePluginManifests {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val SHA = Regex("[0-9a-f]{64}")

    /** Parses one manifest; a malformed document is a failure with the parser's reason, never an exception. */
    fun parse(text: String): Result<VoicePluginManifest> = try {
        Result.success(json.decodeFromString(VoicePluginManifest.serializer(), text))
    } catch (e: SerializationException) {
        Result.failure(IllegalArgumentException("plugin.json is not a valid manifest: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Result.failure(IllegalArgumentException("plugin.json is not a valid manifest: ${e.message}"))
    }

    /** A catalog: a JSON array of manifests. */
    fun parseCatalog(text: String): Result<List<VoicePluginManifest>> = try {
        Result.success(json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(VoicePluginManifest.serializer()), text))
    } catch (e: SerializationException) {
        Result.failure(IllegalArgumentException("catalog is not valid: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        Result.failure(IllegalArgumentException("catalog is not valid: ${e.message}"))
    }

    fun encode(m: VoicePluginManifest): String = json.encodeToString(VoicePluginManifest.serializer(), m)

    /**
     * Every reason [m] cannot run on [platform]; empty = usable. [platform] null skips the platform check (a catalog
     * entry is validated on every machine, and only offered where it runs).
     */
    fun problems(m: VoicePluginManifest, platform: String? = null): List<String> = buildList {
        if (!ID.matches(m.id)) add("id '${m.id}' must be lowercase letters, digits and '-'")
        if (m.name.isBlank()) add("name is missing")
        if (m.kind != "tts") add("kind '${m.kind}' is not supported (only tts)")
        if (m.pluginApi != VOICE_PLUGIN_API) add("needs plugin API ${m.pluginApi}, this app has $VOICE_PLUGIN_API")
        if (m.entry.isEmpty() || m.entry.first().isBlank()) add("entry is missing")
        if (platform != null && platform !in m.platforms) add("not built for $platform (only ${m.platforms.joinToString()})")
        if (m.output.format != "pcm-s16le") add("output format '${m.output.format}' is not supported (only pcm-s16le)")
        if (m.output.sampleRate !in 8_000..48_000) add("output sampleRate ${m.output.sampleRate} is out of range")
        if (m.output.channels !in 1..2) add("output channels ${m.output.channels} is out of range")
        if (m.timeoutMs !in 1_000..60_000) add("timeoutMs ${m.timeoutMs} is out of range (1000..60000)")
        for (a in m.assets) {
            if (!safeRelative(a.path)) add("asset path '${a.path}' must be a plain relative path")
            if (!a.url.startsWith("https://")) add("asset ${a.path}: url must be https")
            if (!SHA.matches(a.sha256)) add("asset ${a.path}: sha256 must be 64 lowercase hex digits")
            if (a.size <= 0) add("asset ${a.path}: size must be positive")
        }
    }

    /** No absolute path, drive, backslash or `..` segment: an asset can only land inside its plugin folder. */
    fun safeRelative(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** One plugin as Configuration > Plugins shows it: a catalog entry, and what is installed on this machine. */
data class VoicePluginRow(
    val id: String,
    val name: String,
    val description: String,
    val license: String,
    val downloadBytes: Long,
    /** `missing` | `installed` | `broken` (installed but unusable: [note] says why). */
    val state: String,
    val installedVersion: String? = null,
    val enabled: Boolean = false,
    val note: String? = null,
)

/** `installed` | `removed` | `failed` ([message] says why). */
data class VoicePluginOutcome(val result: String, val message: String? = null)

/**
 * VOICE-PLUGIN-1: the host's voice plugins (desktop only; null = no Plugins section). Every call may touch the disk or
 * the network, so the UI calls them off the main thread. [install] downloads only when the user clicked Install.
 */
interface VoicePlugins {
    fun rows(): List<VoicePluginRow>
    fun install(id: String, onProgress: (String) -> Unit = {}): VoicePluginOutcome
    fun remove(id: String): VoicePluginOutcome
    /** Only one voice plugin speaks: enabling one disables the others. */
    fun setEnabled(id: String, enabled: Boolean)
}

/** VOICE-PLUGIN-1: how Configuration > Plugins words a row. */
object PluginsSetting {
    const val TITLE = "Plugins"
    const val APPLIES_TO = "This computer. Optional voices downloaded on demand; each runs as a separate program only while it speaks. " +
        "The system voice is used when none is enabled, and whenever a plugin fails."

    fun statusText(r: VoicePluginRow): String = when (r.state) {
        "installed" -> "Installed" + r.installedVersion?.let { " $it" }.orEmpty() + if (r.enabled) ", in use" else ", not in use"
        "broken" -> "Installed but unusable: ${r.note ?: "unknown reason"} (the system voice is used)"
        else -> "Not installed"
    }

    fun installLabel(r: VoicePluginRow): String = "Install (${sizeText(r.downloadBytes)} download)"

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
        else -> "$bytes B"
    }
}
