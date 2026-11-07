// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest

/**
 * SCRIPT-ARSENAL-1: the app-wide script scope. One folder in the plugin's state dir (`<state>/global-scripts/<name>.<ext>` plus a
 * `<name>.<ext>.meta.json` sidecar). A global script is only SOURCE: it runs as the calling project and agent (the invoker carries the
 * project), so global never means cross-project powers. A project script of the same name wins ([ScriptStore.read]).
 */
class GlobalScripts(private val dir: File, private val extensions: () -> List<String>) {
    private fun file(name: String, ext: String) = File(dir, "$name.$ext")

    fun read(name: String): ScriptStore.Script? {
        ScriptStore.checkName(name)
        for (ext in extensions()) readExt(name, ext)?.let { return it }
        return null
    }

    /** The script stored under exactly [ext], whether or not an engine for it is loaded (the seeder runs before engines publish). */
    fun readExt(name: String, ext: String): ScriptStore.Script? {
        ScriptStore.checkName(name)
        val f = file(name, ext)
        return if (f.isFile) ScriptStore.Script(name, ext, f.readText(Charsets.UTF_8), ScriptStore.GLOBAL) else null
    }

    fun names(): List<String> = entries().map { it.name }

    fun entries(): List<ScriptStore.Entry> {
        val exts = extensions().toSet()
        val all = dir.listFiles { f -> f.isFile && !f.name.endsWith(".meta.json") } ?: return emptyList()
        return all.mapNotNull { f ->
            val ext = exts.firstOrNull { f.name.endsWith(".$it") } ?: return@mapNotNull null
            val by = File(dir, f.name + ".meta.json").takeIf { it.isFile }?.let {
                runCatching { (Json.parseToJsonElement(it.readText()) as JsonObject)["savedBy"]?.let { v -> (v as JsonPrimitive).content } }.getOrNull()
            }
            ScriptStore.Entry(f.name.removeSuffix(".$ext"), ext, f.length(), f.lastModified(), by, ScriptStore.GLOBAL)
        }.sortedBy { it.name }
    }

    /** Writes the script and its sidecar; true when it replaced a script of that name (any extension). */
    fun write(name: String, ext: String, source: String, savedBy: String): Boolean {
        ScriptStore.checkName(name)
        dir.mkdirs()
        val had = storedExts(name)
        for (o in had) if (o != ext) { file(name, o).delete(); File(dir, "$name.$o.meta.json").delete() }
        file(name, ext).writeText(source, Charsets.UTF_8)
        File(dir, "$name.$ext.meta.json").writeText(buildJsonObject { put("savedBy", JsonPrimitive(savedBy)) }.toString(), Charsets.UTF_8)
        return had.isNotEmpty()
    }

    fun delete(name: String): Boolean {
        ScriptStore.checkName(name)
        var any = false
        for (o in storedExts(name)) if (file(name, o).delete()) { any = true; File(dir, "$name.$o.meta.json").delete() }
        return any
    }

    private fun storedExts(name: String): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("$name.") && !f.name.endsWith(".meta.json") } ?: emptyArray())
            .map { it.name.removePrefix("$name.") }.filter { !it.contains('.') }
}

/** One script the app ships (a resource of this plugin). */
data class BundledScript(val name: String, val ext: String, val source: String) {
    val hash: String get() = sha256(source)
}

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * SCRIPT-ARSENAL-1: the starter set. Resources `global-scripts/index.json` (a JSON array of file names) and the files beside it.
 * [seed] copies a missing one into the global scope; an app update refreshes one only while nobody edited it (the hash last seeded,
 * kept in `bundled.json`, still equals the file's hash); an edited global and every project copy are never overwritten.
 */
object BundledScripts {
    const val RESOURCE_DIR = "global-scripts"
    const val SEEDED_BY = "bundled"
    private const val STATE = "bundled.json"

    fun load(read: (String) -> String?, log: (String) -> Unit = {}): List<BundledScript> {
        val index = read("$RESOURCE_DIR/index.json") ?: return emptyList()
        val names = runCatching { (Json.parseToJsonElement(index) as JsonArray).map { (it as JsonPrimitive).content } }
            .getOrElse { log("global-scripts/index.json unreadable: ${it.message}"); return emptyList() }
        return names.mapNotNull { file ->
            val src = read("$RESOURCE_DIR/$file") ?: run { log("bundled script $file listed but missing"); return@mapNotNull null }
            BundledScript(file.substringBeforeLast('.'), file.substringAfterLast('.'), src)
        }
    }

    fun fromJar(log: (String) -> Unit = {}): List<BundledScript> =
        load({ p -> BundledScripts::class.java.classLoader.getResourceAsStream(p)?.use { it.readBytes().toString(Charsets.UTF_8) } }, log)

    /** Returns the names written (created or refreshed). */
    fun seed(global: GlobalScripts, stateDir: File, bundled: List<BundledScript>, log: (String) -> Unit = {}): List<String> {
        stateDir.mkdirs()
        val stateFile = File(stateDir, STATE)
        val seeded = HashMap<String, String>()
        runCatching { (Json.parseToJsonElement(stateFile.readText()) as JsonObject).forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { seeded[k] = it } } }
        val written = ArrayList<String>()
        for (b in bundled) {
            val existing = global.readExt(b.name, b.ext)
            when {
                existing == null -> { global.write(b.name, b.ext, b.source, SEEDED_BY); seeded[b.name] = b.hash; written += b.name }
                sha256(existing.source) == b.hash -> seeded[b.name] = b.hash
                seeded[b.name] == sha256(existing.source) -> { global.write(b.name, b.ext, b.source, SEEDED_BY); seeded[b.name] = b.hash; written += b.name }
                else -> log("global script ${b.name} was edited; the bundled update is not applied")
            }
        }
        stateFile.writeText(buildJsonObject { seeded.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString(), Charsets.UTF_8)
        return written
    }
}
