// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** READALOUD-TRUNC-1: the desktop OS voice queues speech, never blocks the caller and never cuts long text. */
class JvmSpeakerQueueTest {
    /** A voice process that "speaks" until [finish] (or destroy). */
    private class FakeVoice(val text: String) : Process() {
        private val done = CountDownLatch(1)
        var destroyed = false
        fun finish() = done.countDown()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { done.await(); return 0 }
        override fun exitValue(): Int = if (done.count == 0L) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = done.count > 0
        override fun destroy() { destroyed = true; done.countDown() }
    }

    private val started = CopyOnWriteArrayList<FakeVoice>()
    private val speaker = JvmSpeaker("Mac OS X") { cmd -> FakeVoice(cmd.last()).also { started += it } }

    private fun waitStarted(n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (started.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n, started.size, "voices started: ${started.map { it.text }}")
    }

    @Test fun `a queued line returns at once and plays only after the current one`() {
        assertTrue(speaker.speak("one", 100, null, interrupt = true))
        val t0 = System.nanoTime()
        assertTrue(speaker.speak("two", 100, null, interrupt = false))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000, "speak blocked the caller")
        Thread.sleep(100)
        assertEquals(listOf("one"), started.map { it.text }, "two must wait for one (no overlap)")
        started[0].finish()
        waitStarted(2)
        assertEquals("two", started[1].text)
        started[1].finish()
        assertTrue(speaker.awaitIdle(5_000))
    }

    @Test fun `an interrupt stops the current line and drops the queue`() {
        speaker.speak("one", 100, null, interrupt = true)
        speaker.speak("two", 100, null, interrupt = false)
        speaker.speak("three", 100, null, interrupt = true)
        assertTrue(started[0].destroyed)
        started.last().finish()
        assertTrue(speaker.awaitIdle(5_000))
        assertEquals(listOf("one", "three"), started.map { it.text })
    }

    @Test fun `stop drops what is queued`() {
        speaker.speak("one", 100, null, interrupt = true)
        speaker.speak("two", 100, null, interrupt = false)
        speaker.stop()
        assertTrue(speaker.awaitIdle(5_000))
        assertEquals(listOf("one"), started.map { it.text })
    }

    @Test fun `text longer than one process takes is spoken whole, in pieces, in order`() {
        val sentence = "This is sentence number %d of a very long agent reply. "
        val text = (1..400).joinToString("") { sentence.format(it) }.trim()
        assertTrue(text.length > JvmSpeaker.MAX_PIECE_CHARS * 2)
        assertTrue(speaker.speak(text, 100, null, interrupt = true))
        var n = 1
        while (true) {
            waitStarted(n)
            started[n - 1].finish()
            if (!speaker.awaitIdle(200)) n++ else break
        }
        val pieces = started.map { it.text }
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.all { it.length <= JvmSpeaker.MAX_PIECE_CHARS })
        assertTrue(pieces.dropLast(1).all { it.endsWith(".") }, "pieces end on a sentence")
        assertEquals(text, pieces.joinToString(" "))
        assertFalse(pieces.any { it.contains("truncat", ignoreCase = true) })
    }

    @Test fun `pieces split on words when a sentence is too long`() {
        val text = "word ".repeat(50).trim()
        val pieces = speechPieces(text, 32)
        assertTrue(pieces.all { it.length <= 32 })
        assertEquals(text, pieces.joinToString(" "))
        assertEquals(listOf("abcdefghij"), speechPieces("abcdefghij", 10))
        assertEquals(listOf("abcde", "fghij"), speechPieces("abcdefghij", 5))
    }
}
