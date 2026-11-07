// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JOBFEED-1 step A: the bounded tail read of a job log. */
class JobLogTailTest {
    private fun tmp(): File = File.createTempFile("jobfeed", ".log").also { it.deleteOnExit() }

    @Test fun `reads only whole lines and continues from the offset`() {
        val f = tmp(); f.writeText("one\ntwo\nthr")
        val a = JobLogTail.read(f, 0, 1024)
        assertEquals("one\ntwo\n", a.text)
        f.appendText("ee\n")
        val b = JobLogTail.read(f, a.nextOffset, 1024)
        assertEquals("three\n", b.text)
        assertEquals(f.length(), b.nextOffset)
    }

    @Test fun `tail starts at a line boundary and one read is bounded`() {
        val f = tmp(); f.writeText((1..1000).joinToString("") { "line $it\n" })
        val t = JobLogTail.read(f, -1, 100)
        assertTrue(t.text.length <= 100 && t.text.startsWith("line ") && t.text.endsWith("\n"))
        val b = JobLogTail.read(f, 0, 100)
        assertTrue(b.text.length <= 100)
    }

    @Test fun `a cut or rotated log resets to the tail and a deleted log is empty`() {
        val f = tmp(); f.writeText("a\nb\nc\n")
        val r = JobLogTail.read(f, 9999, 1024)
        assertTrue(r.reset); assertEquals("a\nb\nc\n", r.text)
        f.delete()
        assertEquals("", JobLogTail.read(f, 0, 1024).text)
    }

    @Test fun `an endless line does not stall a reader`() {
        val f = tmp(); f.writeText("x".repeat(500))
        assertEquals(100, JobLogTail.read(f, 0, 100).text.length)
    }

    @Test fun `exit code is read beside the log`() {
        val dir = java.nio.file.Files.createTempDirectory("jf").toFile()
        val log = File(dir, "abc.log").also { it.writeText("x") }
        assertNull(JobLogTail.exitCode(log))
        File(dir, "abc.exit").writeText("3\n")
        assertEquals(3, JobLogTail.exitCode(log))
    }
}
