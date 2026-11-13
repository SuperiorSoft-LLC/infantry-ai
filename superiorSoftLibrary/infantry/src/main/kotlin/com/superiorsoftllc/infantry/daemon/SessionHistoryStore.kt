// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * CLI-PTY-5: what a session's chat showed, kept across a daemon restart. The session stream's replay buffer lives in
 * memory, so after a restart a session's chat was empty until a new turn started its CLI, which then streamed the
 * whole conversation again. This keeps the newest frames of each session's stream on disk (`<dir>/<sessionId>.jsonl`,
 * one envelope per line) and hands them back at the next start, so the chat shows the history at once, as history:
 * no process starts, no turn runs, no hook fires.
 *
 * Kept: the frames a transcript is drawn from ([KEPT]). A `status` frame is kept only when it carries a transcript line
 * (a detail or a file write), stripped of everything that is live state (run activity, to-dos, the tool now running),
 * so replaying it never makes a resting session read busy. Consecutive text and reasoning deltas are joined on load,
 * so [maxFrames] counts chat items, not stream chunks. Only the newest [maxFrames] are loaded; older ones are dropped
 * (the loaded frames are numbered after the dropped ones, so a client sees a `replay_gap` saying how many).
 *
 * Writes go through one background thread, so the publish path never waits on the disk. A file is compacted to its
 * newest [maxFrames] items once it holds [COMPACT_FACTOR] times that many lines. [maxFrames] 0 keeps nothing.
 *
 * RUNNER-RESTART-FRAMES-1: beside the chat, each session's highest stream sequence is kept (`<dir>/<sessionId>.seq`, written
 * by the same thread, whatever [maxFrames] is), so a restarted process continues a session's numbering ([highWater]) instead
 * of starting again at 1: a runner's orchestrator drops every frame at or below the last sequence it took of a session.
 */
class SessionHistoryStore(
    private val dir: File,
    private val maxFrames: () -> Int,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    /** One session's loaded history: [frames] (unsequenced, oldest first) and how many older items were [dropped]. */
    data class Loaded(val frames: List<Envelope>, val dropped: Long, val highWater: Long = 0L)

    private sealed interface Job {
        data class Append(val sessionId: String, val line: String) : Job
        data class Delete(val sessionId: String) : Job
        data class Mark(val sessionId: String, val sequence: Long) : Job
        class Flush(val done: java.util.concurrent.CountDownLatch) : Job
    }

    private val queue = LinkedBlockingQueue<Job>()
    private val lineCounts = HashMap<String, Int>()
    /** session -> its highest stream sequence known to this process (read from disk once, then kept current). */
    private val highWaters = HashMap<String, Long>()

    private val writer: Thread by lazy {
        Thread(::drain, "infantry-chat-history").also { it.isDaemon = true; it.start() }
    }

    /** Record [frame] if it is one a transcript is drawn from; never blocks. */
    fun record(frame: Envelope) {
        val sessionId = frame.sessionId?.takeIf { it.isNotBlank() && safeId(it) } ?: return
        frame.sequence?.let { mark(sessionId, it) }
        if (maxFrames() <= 0) return
        val kept = keepable(frame) ?: return
        writer
        queue.offer(Job.Append(sessionId, json.encodeToString(Envelope.serializer(), kept.copy(sequence = null))))
    }

    /**
     * RUNNER-RESTART-FRAMES-1: the highest stream sequence [sessionId] had, in this process or (from its `.seq` file) an earlier
     * one; 0 when none is known. A new stream numbers after it.
     */
    fun highWater(sessionId: String): Long {
        if (!safeId(sessionId)) return 0L
        synchronized(highWaters) { highWaters[sessionId]?.let { return it } }
        val read = readHighWater(sessionId)
        return synchronized(highWaters) { maxOf(read, highWaters[sessionId] ?: 0L).also { highWaters[sessionId] = it } }
    }

    private fun mark(sessionId: String, sequence: Long) {
        val raised = synchronized(highWaters) {
            val known = highWaters[sessionId] ?: readHighWater(sessionId)
            (sequence > known).also { highWaters[sessionId] = maxOf(known, sequence) }
        }
        if (!raised) return
        writer
        queue.offer(Job.Mark(sessionId, sequence))
    }

    private fun readHighWater(sessionId: String): Long =
        runCatching { File(dir, sessionId + SEQ_SUFFIX).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull() ?: 0L

    /** Forget [sessionId]'s history (the session was deleted). */
    fun forget(sessionId: String) {
        if (!safeId(sessionId)) return
        synchronized(highWaters) { highWaters.remove(sessionId) }
        writer
        queue.offer(Job.Delete(sessionId))
    }

    /** Wait until every queued write is on disk (tests, shutdown). */
    fun flush(timeoutMs: Long = 5_000) {
        val latch = java.util.concurrent.CountDownLatch(1)
        writer
        queue.offer(Job.Flush(latch))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Read every session's kept history, newest [maxFrames] items each. A file of a session not in [known] is deleted
     * (its session is gone). Unreadable lines are skipped.
     */
    fun loadAll(known: Set<String>): Map<String, Loaded> {
        val max = maxFrames()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        val out = HashMap<String, Loaded>()
        for (file in files) {
            val id = file.name.removeSuffix(SUFFIX)
            if (id !in known || max <= 0) {
                runCatching { file.delete() }
                continue
            }
            val read = runCatching { readItems(file) }.getOrNull() ?: continue
            if (read.items.isEmpty()) continue
            val kept = read.items.takeLast(max)
            out[id] = Loaded(kept, read.dropped + (read.items.size - kept.size), highWater(id))
            synchronized(lineCounts) { lineCounts[id] = read.items.size }
        }
        // RUNNER-RESTART-FRAMES-1: a session with a sequence but no kept chat still continues its numbering; a gone one's mark goes.
        for (file in dir.listFiles { f -> f.isFile && f.name.endsWith(SEQ_SUFFIX) }.orEmpty()) {
            val id = file.name.removeSuffix(SEQ_SUFFIX)
            if (id !in known) { runCatching { file.delete() }; continue }
            if (id !in out) highWater(id).takeIf { it > 0 }?.let { out[id] = Loaded(emptyList(), 0L, it) }
        }
        return out
    }

    private class Read(val items: List<Envelope>, val dropped: Long)

    /** The file's items (deltas joined) and how many older items earlier compactions dropped ([DROPPED_MARK] lines). */
    private fun readItems(file: File): Read {
        val all = file.readLines().mapNotNull(::decode)
        val dropped = all.filter { it.type == DROPPED_MARK }.sumOf { it.data.toLongOrNull() ?: 0L }
        return Read(coalesce(all.filter { it.type != DROPPED_MARK }), dropped)
    }

    private fun decode(line: String): Envelope? =
        line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Envelope.serializer(), it) }.getOrNull() }

    /** The frame as kept, or null when it is not one a transcript is drawn from. */
    private fun keepable(frame: Envelope): Envelope? = when (frame.type) {
        in KEPT -> frame
        Envelope.STATUS -> runCatching {
            val obj = json.parseToJsonElement(frame.data).jsonObject
            val detail = (obj["detail"] as? JsonPrimitive)?.content.orEmpty()
            val write = obj["write"]
            if (detail.isBlank() && write == null) {
                null
            } else {
                val stripped = buildMap {
                    put("state", JsonPrimitive("IDLE"))
                    if (detail.isNotBlank()) put("detail", JsonPrimitive(detail))
                    write?.let { put("write", it) }
                }
                frame.copy(data = JsonObject(stripped).toString())
            }
        }.getOrNull()
        else -> null
    }

    private fun drain() {
        while (true) {
            val first = runCatching { queue.take() }.getOrNull() ?: continue
            val batch = ArrayList<Job>().apply { add(first); queue.drainTo(this) }
            // Group appends per session, in order, so one file open serves a burst of deltas.
            val pending = LinkedHashMap<String, StringBuilder>()
            val marks = LinkedHashMap<String, Long>()
            fun writePending() {
                for ((id, text) in pending) runCatching { append(id, text.toString()) }
                pending.clear()
                for ((id, seq) in marks) runCatching { writeMark(id, seq) }
                marks.clear()
            }
            for (job in batch) when (job) {
                is Job.Append -> pending.getOrPut(job.sessionId) { StringBuilder() }.append(job.line).append('\n')
                is Job.Mark -> marks[job.sessionId] = maxOf(job.sequence, marks[job.sessionId] ?: 0L)
                is Job.Delete -> {
                    writePending()
                    runCatching { File(dir, job.sessionId + SUFFIX).delete() }
                    runCatching { File(dir, job.sessionId + SEQ_SUFFIX).delete() }
                    synchronized(lineCounts) { lineCounts.remove(job.sessionId) }
                }
                is Job.Flush -> { writePending(); job.done.countDown() }
            }
            writePending()
        }
    }

    private fun append(sessionId: String, text: String) {
        dir.mkdirs()
        val file = File(dir, sessionId + SUFFIX)
        file.appendText(text)
        val added = text.count { it == '\n' }
        val lines = synchronized(lineCounts) {
            val now = (lineCounts[sessionId] ?: countLines(file)) + if (lineCounts.containsKey(sessionId)) added else 0
            lineCounts[sessionId] = now
            now
        }
        val max = maxFrames()
        if (max > 0 && lines > max.toLong() * COMPACT_FACTOR) compact(file, sessionId, max)
    }

    /** The mark replaces the file whole (temp file, then moved over), so a crash never leaves a torn number. */
    private fun writeMark(sessionId: String, sequence: Long) {
        dir.mkdirs()
        val file = File(dir, sessionId + SEQ_SUFFIX)
        val tmp = File(dir, sessionId + SEQ_SUFFIX + ".tmp")
        tmp.writeText(sequence.toString())
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun countLines(file: File): Int = runCatching { file.useLines { it.count() } }.getOrDefault(0)

    /**
     * Rewrite [file] with its newest [max] items (deltas joined), led by one [DROPPED_MARK] line carrying how many older
     * items are gone in all, so the gap a client is shown stays true across compactions. Written to a temp file first,
     * then moved over.
     */
    private fun compact(file: File, sessionId: String, max: Int) {
        val read = readItems(file)
        val items = read.items.takeLast(max)
        val dropped = read.dropped + (read.items.size - items.size)
        val mark = if (dropped > 0) json.encodeToString(Envelope.serializer(), Envelope(DROPPED_MARK, dropped.toString(), sessionId)) + "\n" else ""
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(mark + items.joinToString("") { json.encodeToString(Envelope.serializer(), it) + "\n" })
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        synchronized(lineCounts) { lineCounts[sessionId] = items.size }
    }

    companion object {
        const val SUFFIX = ".jsonl"

        /** RUNNER-RESTART-FRAMES-1: the file holding a session's highest stream sequence. */
        const val SEQ_SUFFIX = ".seq"

        /** A file grows to this many times the kept count before it is compacted (bounds disk and load time). */
        const val COMPACT_FACTOR = 4

        /** A line only this file holds (never on the wire): how many older items compaction dropped. */
        const val DROPPED_MARK = "history_dropped"

        /** The stream frames a transcript is drawn from, kept as they are. */
        val KEPT = setOf(
            Envelope.TURN, Envelope.TEXT_DELTA, Envelope.REASONING, Envelope.STDERR,
            Envelope.EXIT, Envelope.ERROR, Envelope.FINISHED,
        )

        /** Join each run of consecutive text (or reasoning) deltas into one frame: the chat draws them as one item. */
        fun coalesce(frames: List<Envelope>): List<Envelope> {
            val out = ArrayList<Envelope>(frames.size)
            for (f in frames) {
                val last = out.lastOrNull()
                if (last != null && last.type == f.type && (f.type == Envelope.TEXT_DELTA || f.type == Envelope.REASONING)) {
                    out[out.size - 1] = last.copy(data = last.data + f.data)
                } else {
                    out += f
                }
            }
            return out
        }

        /** A session id names a file: refuse anything that could leave [dir]. */
        private fun safeId(id: String): Boolean = id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && id != "." && id != ".."
    }
}
