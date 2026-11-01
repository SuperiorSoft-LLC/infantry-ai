package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.files.ProjectFiles

/**
 * SCRIPT-4: named scripts, per project, read as data from `<project dir>/.agenthq/scripts/<name>.<ext>` through the project file
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

    companion object {
        const val DIR = "scripts"
        private val VALID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    }
}
