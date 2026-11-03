package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles

/**
 * SCRIPT-4: named scripts, per project, read as data from `<project dir>/.infantry/scripts/<name>.<ext>` through the project file
 * surface (`api.files`), the extension picking the engine. A name is one plain segment; nothing outside that folder is reachable.
 */
class ScriptStore(private val files: (String) -> ProjectFiles, private val extensions: () -> List<String>) {
    data class Script(val name: String, val extension: String, val source: String)

    fun read(projectId: String, name: String): Script? {
        require(VALID.matches(name)) { "bad script name '$name' (letters, digits, '_', '-', '.'; at most 64)" }
        val f = files(projectId)
        for (ext in extensions()) f.get("$DIR/$name.$ext")?.let { return Script(name, ext, it.toString(Charsets.UTF_8)) }
        return null
    }

    fun names(projectId: String): List<String> {
        val exts = extensions().toSet()
        return files(projectId).list(DIR).map { it.name }.filter { n -> exts.any { n.endsWith(".$it") } }.map { it.substringBeforeLast('.') }.sorted()
    }

    /** SCRIPT-SAVE-1: one stored script; [savedBy] is from the sidecar `<name>.<ext>.meta.json`. */
    data class Entry(val name: String, val extension: String, val size: Long, val modifiedMillis: Long, val savedBy: String?)

    fun checkName(name: String) = require(VALID.matches(name)) { "bad script name '$name' (letters, digits, '_', '-', '.'; at most 64)" }

    /** Writes the script and its sidecar; true when it replaced a script of that name (any extension). */
    fun write(projectId: String, name: String, ext: String, source: String, savedBy: String): Boolean {
        checkName(name)
        val f = files(projectId)
        val existed = extensions().any { f.stat("$DIR/$name.$it") != null }
        for (other in extensions()) if (other != ext && f.stat("$DIR/$name.$other") != null) { f.delete("$DIR/$name.$other"); f.delete("$DIR/$name.$other.meta.json") }
        f.put("$DIR/$name.$ext", source.toByteArray(Charsets.UTF_8))
        f.put("$DIR/$name.$ext.meta.json", kotlinx.serialization.json.buildJsonObject { put("savedBy", kotlinx.serialization.json.JsonPrimitive(savedBy)) }.toString().toByteArray(Charsets.UTF_8))
        return existed
    }

    fun delete(projectId: String, name: String): Boolean {
        checkName(name)
        val f = files(projectId)
        var any = false
        for (ext in extensions()) if (f.delete("$DIR/$name.$ext")) { any = true; f.delete("$DIR/$name.$ext.meta.json") }
        return any
    }

    fun entries(projectId: String): List<Entry> {
        val f = files(projectId)
        val exts = extensions().toSet()
        return f.list(DIR).mapNotNull { e ->
            val ext = exts.firstOrNull { e.name.endsWith(".$it") } ?: return@mapNotNull null
            val by = f.get("$DIR/${e.name}.meta.json")?.let {
                runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject)["savedBy"]?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
            }
            Entry(e.name.removeSuffix(".$ext"), ext, e.size, e.modifiedMillis, by)
        }.sortedBy { it.name }
    }

    companion object {
        const val DIR = "scripts"
        private val VALID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    }
}
