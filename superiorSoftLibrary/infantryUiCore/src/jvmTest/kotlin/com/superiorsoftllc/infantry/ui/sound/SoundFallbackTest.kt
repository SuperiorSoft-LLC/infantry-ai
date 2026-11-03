package com.superiorsoftllc.infantry.ui.sound

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SoundFallbackTest {
    private class Recorder(val ok: (ByteArray) -> Boolean) : SoundPlayer {
        val played = mutableListOf<Pair<List<Byte>, Int>>()
        override fun play(mp3: ByteArray, volumePercent: Int): Boolean { played += mp3.toList() to volumePercent; return ok(mp3) }
    }

    private val default: suspend () -> ByteArray? = { byteArrayOf(1) }

    @Test fun `custom plays when it works, at the chosen volume`() = runBlocking {
        val p = Recorder { true }
        assertEquals(SoundSource.CUSTOM, playWithFallback(p, byteArrayOf(9), 40, default))
        assertEquals(listOf(listOf<Byte>(9) to 40), p.played)
    }

    @Test fun `a corrupt custom file falls back to the default`() = runBlocking {
        val p = Recorder { it[0] != 9.toByte() }
        assertEquals(SoundSource.DEFAULT, playWithFallback(p, byteArrayOf(9), 100, default))
        assertEquals(2, p.played.size)
    }

    @Test fun `a missing custom file plays the default and a throwing player is silence`() = runBlocking {
        assertEquals(SoundSource.DEFAULT, playWithFallback(Recorder { true }, null, 100, default))
        val thrower = object : SoundPlayer { override fun play(mp3: ByteArray, volumePercent: Int): Boolean = error("no audio") }
        assertEquals(SoundSource.SILENT, playWithFallback(thrower, byteArrayOf(9), 100, default))
        assertEquals(SoundSource.SILENT, playWithFallback(null, null, 100, default))
        assertEquals(SoundSource.SILENT, playWithFallback(Recorder { true }, null, 100) { null })
    }
}
