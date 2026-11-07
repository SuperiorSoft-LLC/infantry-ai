// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice.plugin

import com.superiorsoftllc.infantry.plugin.api.SpeakOptions
import com.superiorsoftllc.infantry.ui.voice.SpeakerSpeechOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VOICE-SEAM-1: `speech.out` speaks with the enabled voice plugin engine first, else the OS voice. */
class SpeechOutServiceTest : VoicePluginTestKit() {
    @Test fun anEnabledEngineSpeaksForThePlugin() {
        val s = store(manifest("ok")).also { it.install("fake-ok"); it.setEnabled("fake-ok", true) }
        val os = RecordingSpeaker(); val player = PcmRecorder()
        val speaker = PluginSpeaker(s, os, player, log = {})
        assertTrue(SpeakerSpeechOutput(speaker).speak("build failed", SpeakOptions()))
        assertTrue(speaker.awaitIdle(30_000))
        assertEquals(listOf(3200), player.played)
        assertTrue(os.spoken.isEmpty())
    }

    @Test fun withNoEngineTheOsVoiceSpeaks() {
        val s = store(manifest("ok")).also { it.install("fake-ok") }
        val os = RecordingSpeaker(); val player = PcmRecorder()
        val out = SpeakerSpeechOutput(PluginSpeaker(s, os, player, log = {}))
        assertTrue(out.speak("hello", SpeakOptions(volumePercent = 250, voice = "  ")))
        assertEquals(listOf("hello"), os.spoken)
        assertTrue(player.played.isEmpty())
        assertFalse(out.speak("   "), "blank text speaks nothing")
    }

    @Test fun optionsReachTheSpeakerClamped() {
        var got: List<Any?> = emptyList()
        var stopped = 0
        val speaker = object : com.superiorsoftllc.infantry.ui.voice.Speaker {
            override fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean { got = listOf(text, volumePercent, voiceName, interrupt); return true }
            override fun beep() {}
            override fun voices() = listOf("Zira")
            override fun stop() { stopped++ }
        }
        val out = SpeakerSpeechOutput(speaker)
        out.speak("x", SpeakOptions(volumePercent = -5, voice = "Zira", interrupt = true))
        assertEquals(listOf<Any?>("x", 0, "Zira", true), got)
        out.stop()
        assertEquals(1, stopped)
        assertEquals(listOf("Zira"), out.voices())
    }
}

private class PcmRecorder : PcmPlayer {
    val played: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList<Int>())
    override fun play(pcm: ByteArray, output: VoicePluginOutput, volumePercent: Int, cancelled: () -> Boolean) { played += pcm.size }
}
