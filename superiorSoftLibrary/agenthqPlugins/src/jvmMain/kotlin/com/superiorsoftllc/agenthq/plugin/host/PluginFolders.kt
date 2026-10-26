package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.PluginSettings
import com.superiorsoftllc.agenthq.plugin.api.PluginStorage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** PLUGINS-1 (JVM): where the plugins live and what is in them. Nothing here loads code. */
object PluginFolders {
    const val ROOT_ENV = "AGENTHQ_PLUGINS_ROOT"
    const val ROOT_PROPERTY = "agenthq.plugins.root"
    const val DEMO_LOCK_ENV = "AGENTHQ_DEMO_LOCK"

    /**
     * The plugins root: AGENTHQ_PLUGINS_ROOT, else the `agenthq.plugins.root` system property (the dev run tasks set it
     * to the repository's plugins/), else `<state dir>/plugins`. Never a fixed path.
     */
    fun root(stateDir: File, env: (String) -> String? = System::getenv, property: (String) -> String? = System::getProperty): File =
        env(ROOT_ENV)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: property(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(stateDir, "plugins")

    /** AGENTHQ_DEMO_LOCK set to anything but empty, 0, false or off. */
    fun demoLock(env: (String) -> String? = System::getenv): Boolean =
        env(DEMO_LOCK_ENV)?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "0" && it != "false" && it != "off" } ?: false

    /** `<root>/modules.json`; a missing file is the defaults, an unreadable one too (and [log] says so). */
    fun config(root: File, log: (String) -> Unit = {}): ModulesConfig {
        val f = File(root, "modules.json")
        if (!f.isFile) return ModulesConfig()
        return runCatching { PluginJson.decodeFromString(ModulesConfig.serializer(), f.readText()) }
            .getOrElse { log("[plugins] ${f.path}: invalid, using the defaults (${it.message?.lineSequence()?.first()})"); ModulesConfig() }
    }

    /** Every module folder under the configured subfolders, in folder order then name. A folder without module.json is not a module. */
    fun scan(root: File, config: ModulesConfig): List<ModuleCandidate> =
        config.folders.flatMap { folder ->
            File(root, folder.path).listFiles { f -> f.isDirectory && File(f, "module.json").isFile }.orEmpty()
                .sortedBy { it.name }
                .map { dir ->
                    val parsed = runCatching { PluginJson.decodeFromString(ModuleManifest.serializer(), File(dir, "module.json").readText()) }
                    ModuleCandidate(dir.name, folder.tier, dir.absolutePath, parsed.getOrNull(), parsed.exceptionOrNull()?.message?.lineSequence()?.first())
                }
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
