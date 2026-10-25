package com.superiorsoftllc.agenthq.harness.pi

import java.io.File

/**
 * TODO-PROGRESS-1: pi has no to-do tool of its own, so every pi session loads a small daemon-owned extension
 * (resource `pi/agenthq-todo.ts`) that registers `todowrite` in opencode's full-list shape. The normaliser reads the list
 * from the call's arguments like opencode's, and it drives the to-do box and the progress bar.
 */
object PiTodoExtension {
    const val RESOURCE = "/pi/agenthq-todo.ts"
    const val FILE_NAME = "agenthq-todo.ts"

    fun source(): String = requireNotNull(PiTodoExtension::class.java.getResource(RESOURCE)) { "missing resource $RESOURCE" }.readText()

    /** Writes the extension into [dir] (only when missing or different) and returns the file. */
    fun install(dir: File): File {
        val file = File(dir, FILE_NAME)
        val text = source()
        if (!file.isFile || file.readText() != text) {
            dir.mkdirs()
            file.writeText(text)
        }
        return file
    }

    /** The [PiLaunchExtras.builtIn] contributor: `-e <file>`, the file re-installed if it went missing. Nothing if it cannot be written. */
    fun launchArgs(dir: File): (File?, Map<String, String>) -> List<String> = { _, _ ->
        runCatching { install(dir) }.getOrNull()?.let { listOf("-e", it.absolutePath) } ?: emptyList()
    }
}
