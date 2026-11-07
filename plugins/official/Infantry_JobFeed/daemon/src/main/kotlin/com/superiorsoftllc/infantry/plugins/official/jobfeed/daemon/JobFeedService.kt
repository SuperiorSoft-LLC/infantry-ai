// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed.daemon

import com.superiorsoftllc.infantry.daemon.api.JobInfo
import com.superiorsoftllc.infantry.daemon.api.JobLogChunk
import com.superiorsoftllc.infantry.daemon.api.Sessions

/**
 * JOBFEED-1: the daemon half of the Job Feed box. A client PULLS: `jobfeed_jobs` lists a session's watched jobs, `jobfeed_read`
 * returns the lines appended since the client's offset. Nothing runs between reads (no timer, no stream, no subscriber list), so a
 * closed box or a disabled plugin costs the daemon nothing. [minIntervalMs] caps how often one (session, job) is read (0 = no cap):
 * a read that comes too soon is answered `throttled` with the same offset. Lines are ANSI-stripped and cut to [maxLineChars].
 */
class JobFeedService(
    private val sessions: Sessions,
    private val minIntervalMs: () -> Long,
    private val maxLineChars: () -> Int,
    private val stripAnsi: () -> Boolean,
    /** How many (session, job) read times are remembered for the rate cap (`jobfeed.maxTracked`); the oldest is dropped past it. */
    private val maxTracked: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastRead = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())

    fun jobs(sessionId: String): List<JobInfo> = sessions.jobs(sessionId)

    /** The result of one read; [throttled] = nothing was read, ask again at [fromOffset]. */
    data class Read(val chunk: JobLogChunk?, val text: String, val throttled: Boolean)

    fun read(sessionId: String, jobId: String, fromOffset: Long, maxBytes: Int): Read {
        val key = "$sessionId/$jobId"
        val now = clock()
        val gap = minIntervalMs()
        if (gap > 0) {
            val last = lastRead[key]
            if (last != null && now - last < gap) return Read(null, "", true)
        }
        synchronized(lastRead) {
            lastRead.remove(key)
            lastRead[key] = now
            while (lastRead.size > maxTracked().coerceAtLeast(1)) lastRead.remove(lastRead.keys.first())
        }
        val chunk = sessions.jobLog(sessionId, jobId, fromOffset, maxBytes) ?: return Read(null, "", false)
        return Read(chunk, shape(chunk.text), false)
    }

    private fun shape(text: String): String {
        if (text.isEmpty()) return text
        val cut = maxLineChars()
        val strip = stripAnsi()
        if (cut <= 0 && !strip) return text
        return text.split('\n').joinToString("\n") { raw ->
            var l = if (strip) ANSI.replace(raw, "").replace("\r", "") else raw
            if (cut > 0 && l.length > cut) l = l.substring(0, cut) + "..."
            l
        }
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    }
}
