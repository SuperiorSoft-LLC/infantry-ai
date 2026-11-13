// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * PLUGIN-SCRIPTS-1: the `plugin` script scope. The scripts other modules declare in module.json `scripts`, copied from the module
 * folder into this plugin's state dir (`<state>/plugin-scripts/<module>/<name>.<ext>` plus a `.meta.json` sidecar) by [sync].
 * The lifecycle mirrors ROLE-PLUGIN-1's plugin roles:
 *
 * - Seed: a missing copy is written; the module's own unedited copy is refreshed when the declared file changed (the hash last
 *   seeded is kept in `plugin-scripts-state.json`); a copy whose hash differs from the one seeded is an owner edit and is kept.
 * - Separate scope: a project or global script of the same name is never touched, and wins a lookup by plain name ([ScriptStore.read]).
 * - Uninstall: nothing is deleted. A script whose module is not loaded (or no longer declares it) reads disabled: it is listed, never
 *   run, and its hooks are not attached ([hooks] is derived from the loaded set at each call). A reinstall enables it again.
 *
 * A plugin script is only source: it runs through the same engines, dispatcher, limits and policy gates as any other script.
 */
class PluginScripts(
    private val root: File,
    private val loaded: () -> List<LoadedModule>?,
    private val log: (String) -> Unit = {},
    /** The largest declared script file seeded, bytes (setting `scripting.plugins.maxScriptBytes`). */
    private val maxBytes: () -> Long = { DEFAULT_MAX_SCRIPT_BYTES },
) {
    /** One stored plugin script. [enabled] = its module is loaded and still declares it; [edited] = it differs from the copy seeded. */
    data class Entry(
        val module: String, val name: String, val extension: String, val size: Long, val modifiedMillis: Long,
        val savedBy: String?, val enabled: Boolean, val edited: Boolean, val description: String,
    ) {
        val scope: String get() = scopeOf(module)
    }

    /** One sync's outcome: the `module/name` keys written (added or refreshed) and the declarations refused, with the reason. */
    data class Outcome(val written: List<String>, val problems: List<String>)

    private val stateFile = File(root, STATE)

    private fun dirOf(module: String) = File(root, module)

    /** Seeds the scripts every loaded module declares; never throws (a bad declaration is reported in [Outcome.problems]). */
    @Synchronized
    fun sync(): Outcome {
        val modules = loaded() ?: return Outcome(emptyList(), emptyList())
        val seeded = readState()
        val written = ArrayList<String>()
        val problems = ArrayList<String>()
        for (m in modules) {
            if (m.manifest.scripts.isEmpty()) continue
            if (!MODULE.matches(m.id)) { problems += "${m.id.take(64)}: module id not usable as a script folder"; continue }
            val seen = HashSet<String>()
            for (d in m.manifest.scripts) runCatching {
                val name = d.name.trim()
                ScriptStore.checkName(name)
                require(seen.add(name)) { "script '$name' is declared twice" }
                val (ext, source) = declaredSource(d.file, m.dir)
                val key = "${m.id}/$name"
                val hash = sha256(source)
                val existing = stored(m.id, name)
                when {
                    existing == null -> { write(m.id, name, ext, source, seededBy(m.id)); seeded[key] = hash; written += key }
                    sha256(existing.second) == hash -> seeded[key] = hash
                    seeded[key] == sha256(existing.second) -> { write(m.id, name, ext, source, seededBy(m.id)); seeded[key] = hash; written += key }
                    else -> log("plugin script $key was edited; the module's update is not applied")
                }
                // Hooks are checked here so a bad one is reported once per sync; they are attached by [hooks], not stored.
                for (h in d.hooks) hookProblem(m.id, h)?.let { problems += "${m.id} script '$name' hook '${h.id.take(40)}': $it" }
            }.onFailure { problems += "${m.id} script '${d.name.take(64)}': ${it.message}" }
        }
        writeState(seeded)
        return Outcome(written, problems)
    }

    /** Every stored plugin script, enabled or not, by module then name. */
    fun entries(): List<Entry> {
        val live = liveDeclarations()
        val seeded = readState()
        val dirs = root.listFiles { f -> f.isDirectory && MODULE.matches(f.name) } ?: return emptyList()
        return dirs.sortedBy { it.name }.flatMap { dir ->
            (dir.listFiles { f -> f.isFile && !f.name.endsWith(META) } ?: emptyArray()).mapNotNull { f ->
                val name = f.name.substringBeforeLast('.', "")
                val ext = f.name.substringAfterLast('.', "")
                if (name.isEmpty() || ext.isEmpty() || runCatching { ScriptStore.checkName(name) }.isFailure) return@mapNotNull null
                val decl = live[dir.name]?.get(name)
                val text = f.readText(Charsets.UTF_8)
                Entry(dir.name, name, ext, f.length(), f.lastModified(), savedBy(f), decl != null,
                    seeded["${dir.name}/$name"]?.let { it != sha256(text) } ?: false, decl?.description?.trim().orEmpty())
            }.sortedBy { it.name }
        }
    }

    /** The plugin script [name] of [module], or null when none is stored. Throws [IllegalStateException] when it is disabled. */
    fun read(module: String, name: String): ScriptStore.Script? {
        ScriptStore.checkName(name)
        if (!MODULE.matches(module)) return null
        val (ext, source) = stored(module, name) ?: return null
        if (liveDeclarations()[module]?.containsKey(name) != true) throw IllegalStateException(disabledReason(module, name))
        return ScriptStore.Script(name, ext, source, scopeOf(module))
    }

    /**
     * The ENABLED plugin script [name] (the first loaded module that declares it), or null. When only disabled copies exist this
     * throws [IllegalStateException] naming the module, so a run says why instead of "no script".
     */
    fun find(name: String): ScriptStore.Script? {
        ScriptStore.checkName(name)
        val live = liveDeclarations()
        for ((module, decls) in live) if (name in decls) stored(module, name)?.let { (ext, src) -> return ScriptStore.Script(name, ext, src, scopeOf(module)) }
        val gone = (root.listFiles { f -> f.isDirectory && MODULE.matches(f.name) } ?: emptyArray()).sortedBy { it.name }.firstOrNull { stored(it.name, name) != null }
        if (gone != null) throw IllegalStateException(disabledReason(gone.name, name))
        return null
    }

    /** The modules that store a plugin script [name] (enabled or not). */
    fun modulesOf(name: String): List<String> = entries().filter { it.name == name }.map { it.module }

    /**
     * An owner (or agent) edit of an existing plugin script: the source is replaced, and because its hash no longer equals the
     * seeded one a later sync keeps it. Refused for a script no module stored ([IllegalArgumentException]).
     */
    @Synchronized
    fun edit(module: String, name: String, ext: String, source: String, savedBy: String) {
        ScriptStore.checkName(name)
        require(MODULE.matches(module) && stored(module, name) != null) { "no plugin script '$name' of plugin $module" }
        write(module, name, ext, source, savedBy)
    }

    /**
     * The hooks the loaded modules declare, for scripts that are stored: id `<module>.<id>`, flagged with their plugin. Derived
     * at each call, so a module that unloads takes its hooks with it and a reinstall brings them back. A hook whose kind the
     * Scripting plugin does not know is left out (and reported by [sync]).
     */
    fun hooks(): List<Hook> {
        val modules = loaded() ?: return emptyList()
        return modules.flatMap { m ->
            if (!MODULE.matches(m.id)) return@flatMap emptyList()
            m.manifest.scripts.flatMap { d ->
                val name = d.name.trim()
                if (runCatching { ScriptStore.checkName(name) }.isFailure || stored(m.id, name) == null) return@flatMap emptyList()
                d.hooks.mapNotNull { h ->
                    if (hookProblem(m.id, h) != null) return@mapNotNull null
                    Hook("${m.id}.${h.id.trim()}", HookOn.parse(h.on), name, h.args ?: JsonObject(emptyMap()), global = h.global, plugin = m.id)
                }
            }
        }
    }

    private fun hookProblem(module: String, h: com.superiorsoftllc.infantry.plugin.api.ScriptHookDeclaration): String? {
        val id = h.id.trim()
        if (!HookStore.validId("$module.$id") || id.isEmpty()) return "bad hook id (letters, digits, '_', '-', '.'; at most 64 with the module name)"
        val on = runCatching { HookOn.parse(h.on) }.getOrElse { return "unknown hook kind: ${it.message}" }
        // A mail hook names one session it runs for; a module cannot know one, so that kind is not attachable from module.json.
        if (on is HookOn.Named && on.pattern == MAIL_INCOMING) return "'$MAIL_INCOMING' hooks name a session and cannot be declared by a plugin"
        return null
    }

    /** module -> (declared script name -> declaration), for the loaded modules. */
    private fun liveDeclarations(): Map<String, Map<String, com.superiorsoftllc.infantry.plugin.api.ScriptDeclaration>> =
        (loaded() ?: emptyList()).filter { MODULE.matches(it.id) && it.manifest.scripts.isNotEmpty() }
            .associate { m -> m.id to m.manifest.scripts.associateBy { it.name.trim() } }

    private fun disabledReason(module: String, name: String) =
        "plugin script '$name' is disabled: plugin $module is not loaded or no longer declares it (reinstall or enable the plugin to run it)"

    /** (extension, source) of the file [file] inside [dir]; never outside it (the canonical path must stay under the folder). */
    private fun declaredSource(file: String, dir: File?): Pair<String, String> {
        val f = file.trim()
        val ext = f.substringAfterLast('.', "").lowercase()
        require(f.isNotEmpty() && EXT.matches(ext) && !f.contains("..")) { "file '${f.take(80)}' must be a path inside the module folder with an extension (scripts/name.lua)" }
        requireNotNull(dir) { "module has no folder on disk (a compiled-in module cannot carry script files)" }
        val base = dir.canonicalFile
        val target = File(base, f).canonicalFile
        require(target.path.startsWith(base.path + File.separator) && target.isFile) {
            "file '${f.take(80)}' is not in the module folder (a packaged install carries it when module.json `files` names its folder)"
        }
        val cap = maxBytes()
        require(target.length() <= cap) { "file '${f.take(80)}' is larger than $cap bytes (setting scripting.plugins.maxScriptBytes)" }
        return ext to target.readText(Charsets.UTF_8)
    }

    /** (extension, source) of the stored copy, or null. */
    private fun stored(module: String, name: String): Pair<String, String>? {
        val dir = dirOf(module)
        val f = (dir.listFiles { x -> x.isFile && x.name.startsWith("$name.") && !x.name.endsWith(META) } ?: return null)
            .firstOrNull { !it.name.removePrefix("$name.").contains('.') } ?: return null
        return f.name.substringAfterLast('.') to f.readText(Charsets.UTF_8)
    }

    private fun write(module: String, name: String, ext: String, source: String, savedBy: String) {
        val dir = dirOf(module).also { it.mkdirs() }
        (dir.listFiles { x -> x.isFile && x.name.startsWith("$name.") } ?: emptyArray()).forEach { x ->
            val rest = x.name.removePrefix("$name.")
            if (rest != ext && rest != "$ext$META" && !rest.removeSuffix(META).contains('.')) x.delete()
        }
        File(dir, "$name.$ext").writeText(source, Charsets.UTF_8)
        File(dir, "$name.$ext$META").writeText(buildJsonObject { put("savedBy", JsonPrimitive(savedBy)) }.toString(), Charsets.UTF_8)
    }

    private fun savedBy(f: File): String? = File(f.parentFile, f.name + META).takeIf { it.isFile }?.let {
        runCatching { ((Json.parseToJsonElement(it.readText()) as JsonObject)["savedBy"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }

    private fun readState(): HashMap<String, String> {
        val m = HashMap<String, String>()
        runCatching { (Json.parseToJsonElement(stateFile.readText()) as JsonObject).forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { m[k] = it } } }
        return m
    }

    private fun writeState(seeded: Map<String, String>) {
        root.mkdirs()
        stateFile.writeText(buildJsonObject { seeded.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString(), Charsets.UTF_8)
    }

    companion object {
        const val SCOPE = "plugin"
        private const val STATE = "plugin-scripts-state.json"
        private const val META = ".meta.json"
        private const val MAIL_INCOMING = "mail.incoming"
        /** Default of the `scripting.plugins.maxScriptBytes` setting (equal to its module.json default). */
        const val DEFAULT_MAX_SCRIPT_BYTES = 1_048_576L
        private val MODULE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        private val EXT = Regex("[a-z0-9]{1,16}")

        fun scopeOf(module: String) = "$SCOPE:$module"
        fun seededBy(module: String) = "plugin $module"
    }
}
