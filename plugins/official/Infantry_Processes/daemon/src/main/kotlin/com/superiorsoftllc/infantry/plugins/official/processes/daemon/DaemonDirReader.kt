// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessDaemonDir
import java.io.File
import java.io.RandomAccessFile

/*
 * PROC-LEAK-2 (ticket 07448ac7): a detached build daemon whose client is gone has no parent to chain to and a command line
 * without the worktree, so the ledger could never claim it (it sat under "not ours, never reaped"). A tool profile's
 * `daemonDir` says, as data, where such a daemon names the directory it last built in (Gradle: `Received command: Build{...
 * currentDir=...}` in its per-pid daemon log). This reader resolves that file and follows it incrementally: each call reads
 * only what was appended since the last one, so a long-lived daemon's big log is read once.
 */
class DaemonDirReader(
    private val env: (String) -> String? = System::getenv,
    private val home: String = System.getProperty("user.home").orEmpty(),
) {
    private class Cursor(val startedAt: Long, val file: File, var offset: Long = 0L, var dir: String? = null)

    private val cursors = HashMap<Long, Cursor>()

    /** The directory daemon [p] last built in according to [spec], or null when its file or a match is not found (yet). */
    @Synchronized
    fun dirOf(p: ProcInfo, spec: ProcessDaemonDir): String? {
        val started = p.startedAt ?: return null
        val rx = runCatching { Regex(spec.match) }.getOrNull() ?: return null
        val cursor = cursors[p.pid]?.takeIf { it.startedAt == started }
            ?: (locate(p.pid, started, spec.paths) ?: return null).let { Cursor(started, it) }.also { cursors[p.pid] = it }
        runCatching { follow(cursor, rx) }
        return cursor.dir
    }

    /** Forget the pids no longer alive. */
    @Synchronized
    fun retain(pids: Set<Long>) {
        cursors.keys.retainAll(pids)
    }

    private fun follow(c: Cursor, rx: Regex) {
        val len = c.file.length()
        if (len < c.offset) { c.offset = 0L; c.dir = null } // rewritten: start over
        if (len == c.offset) return
        RandomAccessFile(c.file, "r").use { raf ->
            raf.seek(c.offset)
            val buf = ByteArray(CHUNK)
            var carry = ByteArray(0)
            var pos = c.offset
            while (pos < len) {
                val n = raf.read(buf, 0, minOf(CHUNK.toLong(), len - pos).toInt())
                if (n <= 0) break
                pos += n
                val bytes = carry + buf.copyOf(n)
                val lastNl = bytes.lastIndexOf('\n'.code.toByte())
                if (lastNl < 0) { carry = bytes; continue }
                String(bytes, 0, lastNl, Charsets.UTF_8).lineSequence().forEach { line ->
                    rx.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { c.dir = it }
                }
                carry = bytes.copyOfRange(lastNl + 1, bytes.size)
                c.offset = pos - carry.size // a partial last line is read again next time
            }
        }
    }

    /** The newest existing file of [templates] for [pid] written since the process started (an older one is a recycled pid's). */
    private fun locate(pid: Long, startedAt: Long, templates: List<String>): File? = templates.asSequence()
        .mapNotNull { expand(it, pid) }
        .flatMap { glob(it).asSequence() }
        .filter { it.isFile && it.lastModified() >= startedAt - MTIME_SLACK_MS }
        .maxByOrNull { it.lastModified() }

    /** `${pid}`, `${home}`, `${env:NAME}`; null when a variable is unset (the template does not apply). */
    internal fun expand(template: String, pid: Long): String? {
        var unset = false
        val out = VAR.replace(template) { m ->
            val name = m.groupValues[1]
            when {
                name == "pid" -> pid.toString()
                name == "home" -> home.ifEmpty { unset = true; "" }
                name.startsWith("env:") -> env(name.removePrefix("env:"))?.takeIf { it.isNotBlank() } ?: run { unset = true; "" }
                else -> { unset = true; "" }
            }
        }
        return if (unset) null else out
    }

    /** [path] with `*` segments (one directory level each) expanded to the existing entries. */
    private fun glob(path: String): List<File> {
        val parts = path.replace('\\', '/').split('/')
        // `C:` alone is drive-relative on Windows: the root is `C:/`; a leading `/` gives an empty first part.
        val first = parts.first().let { if (it.isEmpty() || it.endsWith(":")) "$it/" else it }
        var current = listOf(File(first))
        for (part in parts.drop(1)) {
            if (part.isEmpty()) continue
            current = if ('*' in part) {
                val rx = Regex(part.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
                current.flatMap { d -> d.listFiles()?.filter { rx.matches(it.name) }.orEmpty() }
            } else current.map { File(it, part) }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    companion object {
        private val VAR = Regex("""\$\{([^}]+)}""")
        private const val CHUNK = 256 * 1024
        /** A file written this long before the process's start instant still counts as its own (clock granularity). */
        private const val MTIME_SLACK_MS = 2_000L
    }
}
