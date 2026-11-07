// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.File
import java.io.RandomAccessFile

/**
 * JOBFEED-1: a bounded read of the appended part of a job log ([CappedLog]). One call reads at most [maxBytes] and never copies
 * the file. Only whole lines are returned (a half-written last line waits for the next read), except that a chunk with no newline
 * at all and a full buffer is returned as is, so a single endless line cannot stall a reader.
 */
object JobLogTail {
    /**
     * [text] the lines read, [nextOffset] where the next read starts, [size] the file length now, [reset] true when [fromOffset]
     * was no longer valid (the log was cut by the cap, rotated or deleted) and the read started at the tail instead.
     */
    data class Chunk(val text: String, val nextOffset: Long, val size: Long, val reset: Boolean)

    /** [fromOffset] < 0 = the last [maxBytes] of the file (starting at a line boundary). A missing file is an empty chunk at 0. */
    fun read(file: File, fromOffset: Long, maxBytes: Int): Chunk {
        if (maxBytes <= 0 || !file.isFile) return Chunk("", 0L, 0L, fromOffset > 0)
        return runCatching {
            RandomAccessFile(file, "r").use { r ->
                val size = r.length()
                var reset = false
                var from = fromOffset
                if (from > size) { reset = true; from = -1L }
                var skipPartial = false
                if (from < 0) {
                    from = (size - maxBytes).coerceAtLeast(0L)
                    skipPartial = from > 0
                }
                val want = (size - from).coerceAtMost(maxBytes.toLong()).toInt()
                if (want <= 0) return@use Chunk("", from, size, reset)
                val buf = ByteArray(want)
                r.seek(from)
                r.readFully(buf)
                var start = 0
                if (skipPartial) {
                    val nl = buf.indexOf('\n'.code.toByte())
                    start = if (nl >= 0) nl + 1 else 0
                }
                val lastNl = buf.lastIndexOf('\n'.code.toByte())
                val end = when {
                    lastNl >= start -> lastNl + 1
                    want >= maxBytes -> want
                    else -> start
                }
                Chunk(String(buf, start, end - start, Charsets.UTF_8), from + end, size, reset)
            }
        }.getOrDefault(Chunk("", 0L, 0L, true))
    }

    /** The exit code a finished job left in `<id>.exit` beside its `<id>.log`, else null. */
    fun exitCode(logFile: File): Int? =
        File(logFile.parentFile, logFile.name.substringBeforeLast('.') + ".exit").takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
}
