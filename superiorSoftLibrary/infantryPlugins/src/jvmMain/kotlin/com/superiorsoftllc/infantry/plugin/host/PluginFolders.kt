// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.PluginSettings
import com.superiorsoftllc.infantry.plugin.api.PluginFolder
import com.superiorsoftllc.infantry.plugin.api.PluginStorage
import com.superiorsoftllc.infantry.plugin.api.Tier
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** PLUGINS-1 (JVM): where the plugins live and what is in them. Nothing here loads code. */
object PluginFolders {
    const val ROOT_ENV = "INFANTRY_PLUGINS_ROOT"
    const val ROOT_PROPERTY = "infantry.plugins.root"
    const val LOCKDOWN_ENV = "INFANTRY_LOCKDOWN"
    private const val LEGACY_LOCKDOWN_ENV = "INFANTRY_DEMO_LOCK" // deployments that still set the old switch; removed with DAEMON-PLUGINS-1 follow-up

    /**
     * The plugins root: INFANTRY_PLUGINS_ROOT, else the `infantry.plugins.root` system property (the dev run tasks set it
     * to the repository's plugins/), else `<state dir>/plugins`. Never a fixed path.
     */
    fun root(stateDir: File, env: (String) -> String? = System::getenv, property: (String) -> String? = System::getProperty): File =
        env(ROOT_ENV)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: property(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(stateDir, "plugins")

    /** UI-PLUGIN-BRIDGE-1: the file in the state dir where the daemon records the plugins root it loads from. */
    const val RECORDED_ROOT_FILE = "plugins.root"

    /** UI-PLUGIN-BRIDGE-1: the daemon records its plugins root, so a UI on the same state dir follows it. Best effort: a failure is ignored. */
    fun recordRoot(stateDir: File, root: File) {
        runCatching { File(stateDir, RECORDED_ROOT_FILE).also { it.parentFile?.mkdirs() }.writeText(root.absolutePath) }
    }

    /** UI-PLUGIN-BRIDGE-1: the root the daemon recorded in [stateDir], when that folder exists; else null. */
    fun recordedRoot(stateDir: File): File? =
        runCatching { File(File(stateDir, RECORDED_ROOT_FILE).readText().trim()).takeIf { it.isDirectory } }.getOrNull()

    /**
     * UI-PLUGIN-BRIDGE-1: the plugins root a desktop UI loads from: INFANTRY_PLUGINS_ROOT, else the root the daemon on this state dir
     * recorded ([recordRoot]), else what [root] answers. The UI follows the daemon's directory; there is no setting to type.
     */
    fun uiRoot(stateDir: File, env: (String) -> String? = System::getenv, property: (String) -> String? = System::getProperty): File =
        env(ROOT_ENV)?.takeIf { it.isNotBlank() }?.let(::File) ?: recordedRoot(stateDir) ?: root(stateDir, env, property)

    /** INFANTRY_LOCKDOWN (or the legacy INFANTRY_DEMO_LOCK) set to anything but empty, 0, false or off. */
    fun lockdown(env: (String) -> String? = System::getenv): Boolean =
        (env(LOCKDOWN_ENV) ?: env(LEGACY_LOCKDOWN_ENV))?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "0" && it != "false" && it != "off" } ?: false

    /** S6: safe mode, no community module loads: INFANTRY_SAFE_MODE, or the `infantry.safeMode` property (`--safe-mode`). */
    const val SAFE_MODE_ENV = "INFANTRY_SAFE_MODE"
    const val SAFE_MODE_PROPERTY = "infantry.safeMode"

    fun safeMode(env: (String) -> String? = System::getenv, property: (String) -> String? = System::getProperty): Boolean =
        (env(SAFE_MODE_ENV) ?: property(SAFE_MODE_PROPERTY))?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "0" && it != "false" && it != "off" } ?: false

    /**
     * RUNTIME-2: the bundled modules root of the install (`<install>/modules`, holding `official/`): INFANTRY_BUNDLED_MODULES,
     * else the `infantry.plugins.bundled` property (the packaged launcher sets it). Null = none (a dev run from the repo).
     */
    const val BUNDLED_ENV = "INFANTRY_BUNDLED_MODULES"
    const val BUNDLED_PROPERTY = "infantry.plugins.bundled"

    fun bundledRoot(env: (String) -> String? = System::getenv, property: (String) -> String? = System::getProperty): File? =
        (env(BUNDLED_ENV)?.takeIf { it.isNotBlank() } ?: property(BUNDLED_PROPERTY)?.takeIf { it.isNotBlank() })?.let(::File)

    /**
     * RUNTIME-2: `infantry.plugins.devTrust=true` lets an unsigned official jar load (dev runs from the repo root only).
     * A system property only, never an environment variable; the packaged launcher never sets it.
     */
    const val DEV_TRUST_PROPERTY = "infantry.plugins.devTrust"

    fun devTrust(property: (String) -> String? = System::getProperty): Boolean = property(DEV_TRUST_PROPERTY)?.trim()?.lowercase() == "true"

    /** `<root>/modules.json`; a missing file is the defaults, an unreadable one too (and [log] says so). */
    fun config(root: File, log: (String) -> Unit = {}): ModulesConfig {
        val f = File(root, "modules.json")
        if (!f.isFile) return ModulesConfig()
        return runCatching { PluginJson.decodeFromString(ModulesConfig.serializer(), f.readText()) }
            .getOrElse { log("[plugins] ${f.path}: invalid, using the defaults (${it.message?.lineSequence()?.first()})"); ModulesConfig() }
    }

    /**
     * Every module folder under the configured subfolders, in folder order then name. A folder without module.json is
     * not a module; nor is one whose name starts with `_` (PLUGINS-2: `_template`, a copy being worked on).
     */
    fun scan(root: File, config: ModulesConfig): List<ModuleCandidate> = scanFolders(root, folders(config))

    /**
     * RUNTIME-2: the folders scanned: modules.json's, plus `official` when it names no official folder (a modules.json
     * written before the official tier must not hide every official plugin).
     */
    fun folders(config: ModulesConfig): List<PluginFolder> =
        if (config.folders.any { it.tier == Tier.OFFICIAL }) config.folders
        else config.folders.flatMap { if (it.tier == Tier.CORE) listOf(it, OFFICIAL_FOLDER) else listOf(it) }
            .let { if (OFFICIAL_FOLDER in it) it else it + OFFICIAL_FOLDER }

    /**
     * RUNTIME-2: every module of the user [root] and, after them, of the bundled root's `official/` folder.
     * PLUGIN-VERSION-WIN-1: when both hold the same name the higher [SemVer] version loads; equal versions prefer the user
     * copy; an unparseable version counts as the lowest (and the status says so). The loser stays in the list with a
     * `refusal` "shadowed by <winner path> <version>", the channel `GET /plugins` and the plugin list already show.
     */
    fun scan(root: File, config: ModulesConfig, bundled: File?): List<ModuleCandidate> {
        val user = scan(root, config).toMutableList()
        if (bundled == null || bundled.canonicalFile == root.canonicalFile) return user
        fun key(c: ModuleCandidate) = c.manifest?.name ?: c.folderName
        fun ver(c: ModuleCandidate) = c.manifest?.version?.let(SemVer::parseOrNull)
        fun note(c: ModuleCandidate) = c.manifest?.version?.let { v -> if (SemVer.parseOrNull(v) == null) " (version '$v' is not valid, counted as lowest)" else "" } ?: ""
        fun shadowed(loser: ModuleCandidate, winner: ModuleCandidate) =
            loser.copy(refusal = "shadowed by ${winner.dir} ${winner.manifest?.version ?: "?"}" + note(loser) + note(winner))
        val out = mutableListOf<ModuleCandidate>()
        val extra = mutableListOf<ModuleCandidate>()
        for (b in scanFolders(bundled, listOf(OFFICIAL_FOLDER))) {
            val i = user.indexOfFirst { key(it) == key(b) && it.refusal == null }
            if (i < 0) { extra += b; continue }
            val u = user[i]
            val uv = ver(u); val bv = ver(b)
            val bundledWins = bv != null && (uv == null || bv > uv)
            if (bundledWins) { user[i] = shadowed(u, b); extra += b } else extra += shadowed(b, u)
        }
        out += user; out += extra
        return out
    }

    private val OFFICIAL_FOLDER = PluginFolder("official", Tier.OFFICIAL)

    private fun scanFolders(root: File, folders: List<PluginFolder>): List<ModuleCandidate> =
        folders.flatMap { folder ->
            File(root, folder.path).listFiles { f -> f.isDirectory && !f.name.startsWith("_") && File(f, "module.json").isFile }.orEmpty()
                .sortedBy { it.name }
                .map { dir ->
                    val parsed = runCatching { PluginJson.decodeFromString(ModuleManifest.serializer(), File(dir, "module.json").readText()) }
                    ModuleCandidate(dir.name, folder.tier, dir.absolutePath, parsed.getOrNull(), parsed.exceptionOrNull()?.message?.lineSequence()?.first())
                }
        }
}

/**
 * PLUGINS-2: writes the owner's enable list back to `<root>/modules.json` after Settings changed it. Only each
 * module's `enabled` (and, for a removed module, its entry) changes; every other key, known or not, stays as it was.
 */
object ModulesFile {
    fun write(root: File, before: ModulesConfig, after: ModulesConfig) {
        val f = File(root, "modules.json")
        val tree = f.takeIf { it.isFile }?.let { runCatching { PluginJson.parseToJsonElement(it.readText()).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val modules = (tree["modules"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for (name in before.modules.keys - after.modules.keys) modules.remove(name)
        for ((name, setting) in after.modules) {
            if (before.modules[name]?.enabled == setting.enabled && name in modules) continue
            val entry = (modules[name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            setting.enabled?.let { entry["enabled"] = JsonPrimitive(it) } ?: entry.remove("enabled")
            modules[name] = JsonObject(entry)
        }
        save(root, tree, modules)
    }

    /**
     * S6: the consent dialog's answer for module [name]: on, with exactly the node grants the owner left ticked
     * (`allowOverride`, `allowInject`). Every other key of its entry, and of the file, stays as it was.
     */
    fun grant(root: File, name: String, allowOverride: List<String>, allowInject: List<String>) {
        val (tree, modules) = read(root)
        val entry = (modules[name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        entry["enabled"] = JsonPrimitive(true)
        fun put(key: String, ids: List<String>) { if (ids.isEmpty()) entry.remove(key) else entry[key] = JsonArray(ids.distinct().map(::JsonPrimitive)) }
        put("allowOverride", allowOverride)
        put("allowInject", allowInject)
        modules[name] = JsonObject(entry)
        save(root, tree, modules)
    }

    /**
     * PLUGIN-MANAGER-1: sets module [name]'s `enabled` and, when given, exactly its `allow`, `allowOverride` and `allowInject`
     * lists (null = leave that key as it is, an empty list removes it). Every other key of the entry and of the file stays.
     */
    fun update(root: File, name: String, enabled: Boolean? = null, allow: List<String>? = null, allowOverride: List<String>? = null, allowInject: List<String>? = null) {
        val (tree, modules) = read(root)
        val entry = (modules[name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        enabled?.let { entry["enabled"] = JsonPrimitive(it) }
        fun put(key: String, ids: List<String>?) { if (ids == null) return; if (ids.isEmpty()) entry.remove(key) else entry[key] = JsonArray(ids.distinct().map(::JsonPrimitive)) }
        put("allow", allow)
        put("allowOverride", allowOverride)
        put("allowInject", allowInject)
        modules[name] = JsonObject(entry)
        save(root, tree, modules)
    }

    /** S6: drops module [name]'s entry (it was uninstalled); nothing else changes. */
    fun forget(root: File, name: String) {
        val (tree, modules) = read(root)
        if (modules.remove(name) != null) save(root, tree, modules)
    }

    private fun read(root: File): Pair<JsonObject, MutableMap<String, JsonElement>> {
        val f = File(root, "modules.json")
        val tree = f.takeIf { it.isFile }?.let { runCatching { PluginJson.parseToJsonElement(it.readText()).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        return tree to ((tree["modules"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
    }

    private fun save(root: File, tree: JsonObject, modules: Map<String, JsonElement>) {
        root.mkdirs()
        File(root, "modules.json").writeText(PluginJson.encodeToString(JsonObject.serializer(), JsonObject(tree + ("modules" to JsonObject(modules)))) + "\n")
    }
}

/** A module's private data area: files directly in [dir] (names cannot climb out of it). */
class FilePluginStorage(private val dir: File) : PluginStorage {
    private fun file(name: String): File {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != "..") { "plain file names only: '$name'" }
        return File(dir, name)
    }

    override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()
    override fun write(name: String, text: String) {
        dir.mkdirs()
        file(name).writeText(text)
    }
    override fun list(): List<String> = dir.listFiles { f -> f.isFile }.orEmpty().map { it.name }.sorted()
}

/** A module's settings, one JSON object in its data area. */
class FilePluginSettings(private val storage: PluginStorage) : PluginSettings {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private fun all(): Map<String, String> = storage.read(FILE)?.let { runCatching { PluginJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    override fun get(key: String): String? = all()[key]
    override fun set(key: String, value: String) = storage.write(FILE, PluginJson.encodeToString(serializer, all() + (key to value)))

    private companion object { const val FILE = "settings.json" }
}
