// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed.daemon

import com.superiorsoftllc.infantry.daemon.api.JobInfo
import com.superiorsoftllc.infantry.daemon.api.JobLogChunk
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JOBFEED-1 step B: rate cap, shaping, unknown jobs, and that every limit can be changed or switched off. */
class JobFeedServiceTest {
    private class Fake(var text: String = "line\n") : Sessions {
        var reads = 0
        override fun list() = emptyList<SessionInfo>()
        override fun get(id: String): SessionInfo? = null
        override fun spawn(provider: String, model: String, role: String, projectId: String): SessionInfo = error("unused")
        override fun resume(id: String, prompt: String): SessionInfo = error("unused")
        override fun restart(id: String) {}
        private val job = JobInfo("j1", "gradle test", 1L, true)
        override fun jobs(id: String) = if (id == "s1") listOf(job) else emptyList()
        override fun jobLog(id: String, jobId: String, fromOffset: Long, maxBytes: Int): JobLogChunk? {
            if (id != "s1" || jobId != "j1") return null
            reads++
            return JobLogChunk(text, fromOffset.coerceAtLeast(0) + text.length, 100, false, job)
        }
    }

    private var now = 1_000L
    private fun service(f: Fake, gap: Long = 200, cut: Int = 300, ansi: Boolean = true, tracked: Int = 200) =
        JobFeedService(f, { gap }, { cut }, { ansi }, { tracked }, { now })

    @Test fun `a read that comes too soon is throttled and does not touch the log`() {
        val f = Fake(); val s = service(f)
        assertFalse(s.read("s1", "j1", 0, 1024).throttled)
        now += 50
        assertTrue(s.read("s1", "j1", 0, 1024).throttled)
        assertEquals(1, f.reads)
        now += 200
        assertFalse(s.read("s1", "j1", 0, 1024).throttled)
        assertEquals(2, f.reads)
    }

    @Test fun `the rate cap can be switched off`() {
        val f = Fake(); val s = service(f, gap = 0)
        repeat(5) { assertFalse(s.read("s1", "j1", 0, 1024).throttled) }
        assertEquals(5, f.reads)
    }

    @Test fun `ansi is stripped and long lines are cut, each switchable`() {
        val raw = "\u001B[31mred\u001B[0m " + "x".repeat(50) + "\n"
        val f = Fake(raw)
        assertEquals("red " + "x".repeat(10) + "...\n", service(f, gap = 0, cut = 14).read("s1", "j1", 0, 1024).text)
        assertEquals("red " + "x".repeat(50) + "\n", service(f, gap = 0, cut = 0).read("s1", "j1", 0, 1024).text)
        assertEquals(raw, service(f, gap = 0, cut = 0, ansi = false).read("s1", "j1", 0, 1024).text)
    }

    @Test fun `an unknown job or session is not an error`() {
        val s = service(Fake(), gap = 0)
        assertNull(s.read("s1", "nope", 0, 1024).chunk)
        assertNull(s.read("zz", "j1", 0, 1024).chunk)
        assertEquals(emptyList(), s.jobs("zz"))
        assertEquals(1, s.jobs("s1").size)
    }

    @Test fun `the rate table is bounded`() {
        val f = Fake(); val s = service(f, tracked = 2)
        listOf("a", "b", "c").forEach { s.read("s1", it, 0, 10) }
        now += 10
        // "a" was dropped from the table, so it is not throttled; "c" is still remembered and is.
        assertFalse(s.read("s1", "a", 0, 10).throttled)
        assertTrue(s.read("s1", "a", 0, 10).throttled)
    }
}
