package com.superiorsoftllc.infantry.plugins.official.pi.harness

import java.io.File

/** The pi extension files this plugin ships as resources and loads with `-e <file>`. */
internal object PiExtensions {
    /** TODO-PROGRESS-1: pi has no to-do tool of its own; this extension registers `todowrite` in opencode's full-list shape (drives the to-do box and the progress bar). */
    const val TODO = "infantry-todo.ts"

    /** STALL-3 / watch: rewrites a shell tool call so it runs through the watched runner (the daemon's one rule set), when the session's watch is on. */
    const val WATCH = "infantry-watch.ts"

    fun source(name: String): String =
        requireNotNull(PiExtensions::class.java.getResource("/pi/$name")) { "missing resource /pi/$name" }.readText()

    /** Writes extension [name] into [dir] (only when missing or different) and returns the file. */
    fun install(dir: File, name: String): File {
        val file = File(dir, name)
        val text = source(name)
        if (!file.isFile || file.readText() != text) {
            dir.mkdirs()
            file.writeText(text)
        }
        return file
    }

    /** `-e <file>`, the file re-installed if it went missing; nothing if it cannot be written. */
    fun launchArgs(dir: File, name: String): List<String> =
        runCatching { install(dir, name) }.getOrNull()?.let { listOf("-e", it.absolutePath) } ?: emptyList()
}
