// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import com.superiorsoftllc.infantry.plugin.api.PcmFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VOICEINPUT-MIC-1: format choice for devices without 16 kHz mono, and the software downmix/resample. */
class CaptureFormatTest {
    private val want = PcmFormat(16000, 1, 16)

    @Test fun exactFormatWinsWhenOffered() {
        assertEquals(want, selectCaptureFormat(want) { true })
    }

    @Test fun deviceOnly48kStereoIsChosen() {
        val dev = PcmFormat(48000, 2, 16)
        assertEquals(dev, selectCaptureFormat(want) { it == dev })
    }

    @Test fun prefersMonoAt48kOver44k() {
        val got = selectCaptureFormat(want) { it.sampleRateHz >= 44100 }
        assertEquals(PcmFormat(48000, 1, 16), got)
    }

    @Test fun noDeviceGivesNull() {
        assertNull(selectCaptureFormat(want) { false })
    }

    private fun pcm(vararg v: Int) = ByteArray(v.size * 2).also { b -> v.forEachIndexed { i, x -> b[i * 2] = x.toByte(); b[i * 2 + 1] = (x shr 8).toByte() } }
    private fun samples(b: ByteArray) = IntArray(b.size / 2) { ((b[it * 2].toInt() and 0xff) or (b[it * 2 + 1].toInt() shl 8)).toShort().toInt() }

    @Test fun downmixesStereoToMono() {
        val c = PcmConverter(PcmFormat(16000, 2, 16), want)
        assertEquals(listOf(200, 400), samples(c.convert(pcm(100, 300, 300, 500, 0, 0))).take(2))
    }

    @Test fun resamples48kTo16kKeepingDuration() {
        val c = PcmConverter(PcmFormat(48000, 1, 16), want)
        val out = c.convert(pcm(*IntArray(4800) { 1000 }))
        assertTrue(out.size / 2 in 1599..1600, "got ${out.size / 2}")
        assertTrue(samples(out).all { it == 1000 })
    }

    @Test fun chunkedEqualsWhole() {
        val data = IntArray(960) { (it * 37) % 2000 - 1000 }
        val whole = PcmConverter(PcmFormat(48000, 1, 16), want).convert(pcm(*data))
        val c = PcmConverter(PcmFormat(48000, 1, 16), want)
        val parts = data.toList().chunked(100).map { c.convert(pcm(*it.toIntArray())) }.reduce { a, b -> a + b }
        assertEquals(samples(whole).toList(), samples(parts).toList())
    }
}
