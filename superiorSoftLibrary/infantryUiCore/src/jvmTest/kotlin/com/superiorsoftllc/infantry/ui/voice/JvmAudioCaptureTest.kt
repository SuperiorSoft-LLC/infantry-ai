// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import com.superiorsoftllc.infantry.plugin.api.CaptureEnd
import com.superiorsoftllc.infantry.plugin.api.CaptureOptions
import com.superiorsoftllc.infantry.plugin.api.PcmFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VOICE-SEAM-1: the JVM `audio.in` service against a fake input line (no real microphone in CI). */
class JvmAudioCaptureTest {
    /** A line that hands out [chunk]-sized reads until closed (or until [failAfter] reads, then reports a dead line). */
    private class FakeLine(private val delayMs: Long = 2, private val failAfter: Int? = null) : CaptureLine {
        val closed = AtomicBoolean(false)
        private val reads = AtomicInteger(0)
        override fun read(buffer: ByteArray): Int {
            if (closed.get()) return -1
            if (failAfter != null && reads.incrementAndGet() > failAfter) return -1
            Thread.sleep(delayMs)
            buffer.fill(7)
            return if (closed.get()) -1 else buffer.size
        }
        override fun close() { closed.set(true) }
    }

    private class Recorder {
        val bytes = AtomicLong(0)
        val chunksAfterEnd = AtomicInteger(0)
        val firstChunk = CountDownLatch(1)
        val ended = CountDownLatch(1)
        @Volatile var end: CaptureEnd? = null
        val endCalls = AtomicInteger(0)
        fun chunk(b: ByteArray) {
            if (end != null) chunksAfterEnd.incrementAndGet()
            bytes.addAndGet(b.size.toLong()); firstChunk.countDown()
        }
        fun end(e: CaptureEnd) { end = e; endCalls.incrementAndGet(); ended.countDown() }
    }

    private val monitor = CaptureMonitor()
    private fun capture(line: () -> CaptureLine?, limit: Int = 60) =
        JvmAudioCapture("Acme_Mic", monitor, { captureLimitSeconds(limit.toString(), it) }, { line() })

    @Test fun stopEndsTheStreamFreesTheSlotAndSendsNoMoreChunks() {
        val line = FakeLine()
        val r = Recorder()
        val handle = capture({ line }).start(CaptureOptions(), r::chunk, r::end)
        assertTrue(r.firstChunk.await(5, TimeUnit.SECONDS))
        assertEquals("Acme_Mic", monitor.active.value?.module, "the listening indicator shows the module")
        handle.stop()
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureEnd.STOPPED, r.end?.reason)
        assertEquals(1, r.endCalls.get())
        assertFalse(handle.active)
        assertTrue(line.closed.get())
        assertNull(monitor.active.value, "the slot is free")
        Thread.sleep(30)
        assertEquals(0, r.chunksAfterEnd.get())
    }

    @Test fun theIndicatorStopEndsTheCaptureToo() {
        val r = Recorder()
        capture({ FakeLine() }).start(CaptureOptions(), r::chunk, r::end)
        assertTrue(r.firstChunk.await(5, TimeUnit.SECONDS))
        monitor.active.value!!.stop()
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureEnd.STOPPED, r.end?.reason)
    }

    @Test fun theTimeLimitEndsTheCaptureAtExactlyThatMuchAudio() {
        val r = Recorder()
        // The host allows 1 s; the plugin asks for 5, so 1 s it is: 16 kHz x 1 channel x 2 bytes.
        capture({ FakeLine(delayMs = 0) }, limit = 1).start(CaptureOptions(PcmFormat(), maxSeconds = 5), r::chunk, r::end)
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureEnd.LIMIT, r.end?.reason)
        assertEquals(32_000L, r.bytes.get())
        assertNull(monitor.active.value)
    }

    @Test fun oneCaptureAtATimePerHost() {
        val first = Recorder()
        val running = capture({ FakeLine() }).start(CaptureOptions(), first::chunk, first::end)
        val second = Recorder()
        val refused = capture({ FakeLine() }).start(CaptureOptions(), second::chunk, second::end)
        assertEquals(CaptureEnd.BUSY, second.end?.reason, "refused before start returns")
        assertFalse(refused.active)
        assertTrue(running.active)
        running.stop()
        assertTrue(first.ended.await(5, TimeUnit.SECONDS))
    }

    @Test fun noMicrophoneOrABadFormatIsUnavailableAndLeavesTheSlotFree() {
        val r = Recorder()
        capture({ null }).start(CaptureOptions(), r::chunk, r::end)
        assertEquals(CaptureEnd.UNAVAILABLE, r.end?.reason)
        assertNull(monitor.active.value)
        val bad = Recorder()
        capture({ FakeLine() }).start(CaptureOptions(PcmFormat(sampleRateHz = 0)), bad::chunk, bad::end)
        assertEquals(CaptureEnd.UNAVAILABLE, bad.end?.reason)
    }

    @Test fun aLineThatDiesEndsFailed() {
        val r = Recorder()
        capture({ FakeLine(failAfter = 3) }).start(CaptureOptions(), r::chunk, r::end)
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureEnd.FAILED, r.end?.reason)
        assertNull(monitor.active.value)
    }

    @Test fun theLimitSettingDefaultsTo60AndCapsTheRequest() {
        assertEquals(60, captureLimitSeconds(null, null))
        assertEquals(60, captureLimitSeconds("nope", 600))
        assertEquals(10, captureLimitSeconds(null, 10))
        assertEquals(5, captureLimitSeconds("5", 30))
    }
}
