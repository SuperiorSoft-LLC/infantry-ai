// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PLUGIN-UPDATE-LOCK-1 (docs/wiki/Plugin-lifecycle.md): the copy of a plugin's jars a class loader reads, so nothing ever
 * holds the files of the install folder (`plugins/<tier>/<Module>`). On Windows a jar a class loader has open cannot be
 * moved or deleted, nor can the folder that holds it; the plugin manager moves that folder on every update, rollback and
 * remove. With the copy, the folder is free at all times: the manager swaps it while the old version keeps running from
 * its copy, and the live swap then loads the new version from the folder into a new class loader (and a new copy).
 *
 * Every copy lives in this process's own folder, `<base>/<pid>/<label>-<n>/`, the files keep their path relative to the
 * module folder. [close] deletes the copy once the class loader is closed; a file the OS still holds (a native library
 * stays mapped until its class loader is collected) is retried on the next copy, and the folder of a process that has
 * ended is removed by the next process that makes a copy. When a copy cannot be made (no space, no temp folder) the
 * original files are used, exactly as before: the plugin loads, only an update while it runs may then be refused.
 */
class PluginJarShadow private constructor(
    /** The module folder the files come from (relative paths and native libraries are resolved against it). */
    val source: File,
    /** The copy's own folder; equal to [source] when no copy could be made ([shadowed] false). */
    val dir: File,
    /** The files a class loader reads, in the order given: the copies, or the originals when [shadowed] is false. */
    val files: List<File>,
    val shadowed: Boolean,
) : AutoCloseable {
    @Volatile private var closed = false

    /** The class path of [files]. */
    fun urls(): Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()

    /**
     * The file to load for the native library [original] (a file in the module folder): a copy next to the jars, with
     * the other files of its folder (a library's own dependencies are found beside it). The original when not shadowed,
     * when it is outside the module folder, or when the copy fails.
     */
    fun native(original: File): File {
        if (!shadowed || closed) return original
        val rel = relative(source, original) ?: return original
        val target = File(dir, rel)
        if (target.isFile) return target
        return try {
            original.parentFile.listFiles { f -> f.isFile }.orEmpty().forEach { f ->
                val t = File(target.parentFile, f.name)
                if (!t.exists()) { t.parentFile.mkdirs(); Files.copy(f.toPath(), t.toPath(), StandardCopyOption.COPY_ATTRIBUTES) }
            }
            target.takeIf { it.isFile } ?: original
        } catch (_: IOException) {
            original
        }
    }

    /** Deletes the copy; call it after the class loader that reads it is closed. Safe to call twice. */
    override fun close() {
        if (closed) return
        closed = true
        if (!shadowed) return
        active.remove(dir)
        if (!dir.deleteRecursively()) pending += dir
    }

    companion object {
        /** Where every process keeps its copies; tests point it elsewhere. */
        @Volatile var base: File = File(System.getProperty("java.io.tmpdir"), "infantry-plugin-jars")

        private val counter = AtomicLong()
        private val active: MutableSet<File> = ConcurrentHashMap.newKeySet()
        /** Copies whose delete failed (a file still held); retried on every new copy. */
        private val pending: MutableSet<File> = ConcurrentHashMap.newKeySet()
        @Volatile private var sweptBase: File? = null

        private fun processDir(): File = File(base, ProcessHandle.current().pid().toString())

        /**
         * Copies [jars] (files of the module folder [source]; one outside it is copied by its name) into a new folder of
         * this process. [label] names that folder (the module id). Duplicates are copied once.
         */
        fun of(source: File, jars: List<File>, label: String): PluginJarShadow {
            val unique = jars.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            sweep()
            val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "plugin" }
            val dir = File(processDir(), "$safe-${counter.incrementAndGet()}")
            return try {
                val copies = unique.map { f ->
                    val t = File(dir, relative(source, f) ?: f.name)
                    t.parentFile.mkdirs()
                    Files.copy(f.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    t
                }
                active += dir
                PluginJarShadow(source, dir, copies, shadowed = true)
            } catch (_: IOException) {
                dir.deleteRecursively()
                PluginJarShadow(source, source, unique, shadowed = false)
            } catch (_: SecurityException) {
                dir.deleteRecursively()
                PluginJarShadow(source, source, unique, shadowed = false)
            }
        }

        /** Retries the copies whose delete failed, and (once per [base]) removes the folders of processes that have ended. */
        private fun sweep() {
            pending.toList().forEach { if (it.deleteRecursively() || !it.exists()) pending.remove(it) }
            val b = base
            if (sweptBase == b) return
            sweptBase = b
            val mine = ProcessHandle.current().pid()
            b.listFiles { f -> f.isDirectory }.orEmpty().forEach { d ->
                val pid = d.name.toLongOrNull() ?: return@forEach
                if (pid != mine && !ProcessHandle.of(pid).isPresent) d.deleteRecursively()
            }
        }

        private fun relative(root: File, f: File): String? {
            val r = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return null
            val p = runCatching { f.canonicalFile.toPath() }.getOrNull() ?: return null
            return if (p.startsWith(r) && p != r) r.relativize(p).toString() else null
        }
    }
}

/**
 * PLUGIN-UPDATE-LOCK-1: a class loader over a [PluginJarShadow]: it reads the copy, never the install folder, and deletes
 * the copy when it is closed. Every plugin half (UI, daemon, harness) loads its jars through one, so a plugin can be
 * updated, rolled back or removed while it runs.
 */
open class ShadowJarClassLoader(name: String?, val shadow: PluginJarShadow, parent: ClassLoader?) :
    URLClassLoader(name, shadow.urls(), parent) {
    /** Copies [jars] of the module folder [source] (see [PluginJarShadow.of]) and reads the copy. */
    constructor(name: String?, source: File, jars: List<File>, parent: ClassLoader?) :
        this(name, PluginJarShadow.of(source, jars, name ?: source.name), parent)

    override fun close() {
        try { super.close() } finally { shadow.close() }
    }
}
