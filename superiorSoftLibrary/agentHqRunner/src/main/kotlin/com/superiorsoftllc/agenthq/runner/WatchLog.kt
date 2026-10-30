package com.superiorsoftllc.agenthq.runner

import java.io.File
import java.io.RandomAccessFile

/**
 * STALL-4 (ticket 14ac9383) item 4: the watched runner's job log under limited space. A [CappedLog] never grows past
 * [maxBytes]: past it the head is cut (the newest half is kept, behind a `truncated N bytes` marker line), so the tail a
 * notice reads stays. Below [minFreeBytes] of free disk ([freeSpace], injectable for tests) it stops writing, once, and
 * says why ([pausedReason]); the command's output still streams to the console. Nothing here ever fails the command.
 */
class CappedLog(
    private val file: File,
    /** 0 = no cap. */
    private val maxBytes: Long,
    /** 0 = never paused. */
    private val minFreeBytes: Long,
    private val freeSpace: (File) -> Long = { f -> (f.absoluteFile.parentFile ?: f.absoluteFile).usableSpace },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How often the free space is checked (`limits.watch.spaceCheckSeconds`). */
    private val spaceCheckMs: Long = 5_000L,
) {
    private var out = runCatching { java.io.FileOutputStream(file, false).buffered() }.getOrNull()
    private var size = 0L
    private var nextSpaceCheck = 0L

    /** Bytes cut from the head so far. */
    var truncated = 0L
        private set

    /** Why the log is no longer written (low disk space); null while it is. */
    var pausedReason: String? = null
        private set

    /** Appends [line] and a newline; returns false when the log is (now) paused or unwritable. */
    @Synchronized
    fun appendLine(line: String): Boolean {
        if (pausedReason != null) return false
        val o = out ?: return false
        val now = clock()
        if (minFreeBytes > 0 && now >= nextSpaceCheck) {
            nextSpaceCheck = now + spaceCheckMs
            val free = runCatching { freeSpace(file) }.getOrDefault(Long.MAX_VALUE)
            if (free in 0 until minFreeBytes) {
                pausedReason = "free disk space ${free / MB} MB is below the floor of ${minFreeBytes / MB} MB (limits.watch.minFreeMB)"
                runCatching { o.flush() }
                return false
            }
        }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        return runCatching {
            o.write(bytes)
            o.flush()
            size += bytes.size
            if (maxBytes > 0 && size > maxBytes) cut()
            true
        }.getOrDefault(false)
    }

    /** Keeps the newest half of the cap behind a marker line, starting at a line boundary. */
    private fun cut() {
        runCatching { out?.close() }
        val keep = maxBytes / 2
        val tail = RandomAccessFile(file, "r").use { r ->
            val len = r.length()
            val from = (len - keep).coerceAtLeast(0)
            val buf = ByteArray((len - from).toInt())
            r.seek(from)
            r.readFully(buf)
            val nl = buf.indexOf('\n'.code.toByte())
            val start = if (from > 0 && nl >= 0) nl + 1 else 0
            truncated += from + start
            buf.copyOfRange(start, buf.size)
        }
        val marker = (WatchNotices.text("runner.logTruncated", "bytes" to truncated, "cap" to "${maxBytes / MB} MB") + "\n").toByteArray(Charsets.UTF_8)
        file.writeBytes(marker + tail)
        size = (marker.size + tail.size).toLong()
        out = java.io.FileOutputStream(file, true).buffered()
    }

    @Synchronized
    fun close() { runCatching { out?.close() }; out = null }

    companion object {
        const val MB = 1024L * 1024L
    }
}

/**
 * STALL-4 item 4a: old job logs are pruned when a job starts. A job is the group of files `<id>.*` in the log dir; only a
 * FINISHED job (its `<id>.exit` is written) is pruned by count and total size, oldest first; an unfinished one only when
 * it is older than [keepDays] (its runner is long gone). [keepIds] (the job starting now) are never touched.
 */
object LogPrune {
    fun prune(dir: File, keepJobs: Long, keepDays: Long, totalBytes: Long, keepIds: Set<String>, now: Long = System.currentTimeMillis()): Int {
        val all = dir.listFiles()?.filter { it.isFile } ?: return 0
        class Job(val id: String, val files: List<File>) {
            val newest = files.maxOf { it.lastModified() }
            val bytes = files.sumOf { it.length() }
            val finished = files.any { it.name == "$id.exit" }
        }
        val jobs = all.groupBy { it.name.substringBefore('.') }.filterKeys { it.isNotEmpty() && it !in keepIds }
            .map { (id, fs) -> Job(id, fs) }.sortedByDescending { it.newest }
        val drop = LinkedHashSet<Job>()
        if (keepDays > 0) jobs.filter { now - it.newest > keepDays * 86_400_000L }.forEach { drop += it }
        val finished = jobs.filter { it.finished && it !in drop }
        if (keepJobs > 0) finished.drop(keepJobs.toInt()).forEach { drop += it }
        if (totalBytes > 0) {
            var sum = 0L
            for (j in finished) { if (j in drop) continue; sum += j.bytes; if (sum > totalBytes) drop += j }
        }
        drop.forEach { j -> j.files.forEach { runCatching { it.delete() } } }
        return drop.size
    }
}
