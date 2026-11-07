// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JOBFEED-1 step C: the box's logic. Every setting changes behaviour, and the defaults are the weak-PC ones. */
class JobFeedModelTest {
    @Test fun `defaults suit a weak machine`() {
        val s = JobFeedSettings()
        assertEquals(4, s.effectiveUpdates); assertEquals(250L, s.pollMs); assertEquals(300, s.effectiveMaxLines)
        assertFalse(s.openByDefault); assertTrue(s.autoCollapseUnfocused)
    }

    @Test fun `low power lowers the rate to one a second and the lines to a hundred`() {
        val s = JobFeedSettings(lowPower = true)
        assertEquals(1, s.effectiveUpdates); assertEquals(1000L, s.pollMs); assertEquals(100, s.effectiveMaxLines)
        // a user who already chose less keeps it
        assertEquals(50, JobFeedSettings(maxLines = 50, lowPower = true).effectiveMaxLines)
    }

    @Test fun `each limit can be changed`() {
        assertEquals(100L, JobFeedSettings(updatesPerSecond = 10).pollMs)
        assertEquals(1000L, JobFeedSettings(updatesPerSecond = 0).pollMs) // below the floor: one a second, never a busy loop
        assertEquals(1000, JobFeedSettings(maxLines = 1000).effectiveMaxLines)
    }

    @Test fun `the buffer keeps only the newest lines`() {
        val b = LineBuffer { 3 }
        b.append("a\nb\nc\nd\n"); b.append("e\n")
        assertEquals(listOf("c", "d", "e"), b.snapshot())
        b.clear(); assertEquals(0, b.size)
        b.append(""); assertEquals(0, b.size)
    }

    @Test fun `failure markers highlight and can be changed or emptied`() {
        val on = JobFeedSettings().markers
        assertTrue(isFailureLine("> Task :x FAILED", on)); assertTrue(isFailureLine("e: file.kt: error: boom", on))
        assertFalse(isFailureLine("BUILD ok", on))
        assertTrue(isFailureLine("panic!", JobFeedSettings(failureMarkers = "panic").markers))
        assertFalse(isFailureLine("FAILED", JobFeedSettings(failureMarkers = "").markers))
    }

    @Test fun `status text and elapsed time`() {
        assertEquals("running", jobStatusText(true, null)); assertEquals("done exit 0", jobStatusText(false, 0))
        assertEquals("failed exit 2", jobStatusText(false, 2)); assertEquals("ended", jobStatusText(false, null))
        assertEquals("5s", elapsedText(0, 5_000)); assertEquals("2m 3s", elapsedText(0, 123_000)); assertEquals("1h 1m", elapsedText(0, 3_660_000))
    }

    @Test fun `the neutral quiet notice shows after the quiet time and can be switched off`() {
        assertNull(quietNotice(true, 0, 299_000, 300))
        assertNotNull(quietNotice(true, 0, 300_000, 300))
        assertNull(quietNotice(false, 0, 999_000, 300)); assertNull(quietNotice(true, 0, 999_000, 0))
    }

    @Test fun `the read answer is parsed and a bad answer is null`() {
        val c = JobFeedWire.parseChunk("""{"jobId":"j","text":"x\n","throttled":false,"known":true,"next":2,"size":2,"reset":false,"label":"gradle","startedAt":5,"running":false,"exitCode":1}""")
        assertNotNull(c); assertEquals("x\n", c.text); assertEquals(1, c.exitCode); assertTrue(c.known)
        assertNull(JobFeedWire.parseChunk("not json"))
        assertTrue(JobFeedWire.readBody("s", "j", -1).contains("\"jobId\":\"j\""))
    }
}
