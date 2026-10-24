package com.superiorsoftllc.agenthq.ui.voice.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VOICE-PLUGIN-1: speaker selection, fallback to the OS voice, timeouts and idle cost, with a fake plugin (a real child JVM). */
class PluginSpeakerTest : VoicePluginTestKit() {
    @Test fun theOsVoiceSpeaksWhenNoPluginIsEnabled() {
        val s = store(manifest("ok")).also { it.install("fake-ok") }
        val os = RecordingSpeaker(); val player = RecordingPlayer()
        val speaker = PluginSpeaker(s, os, player, log = {})
        assertTrue(speaker.speak("hello", 100, null, false))
        assertEquals(listOf("hello"), os.spoken)
        assertTrue(player.played.isEmpty())
    }

    @Test fun anEnabledPluginSpeaksAndNoProcessIsLeftAtIdle() {
        val s = store(manifest("ok")).also { it.install("fake-ok"); it.setEnabled("fake-ok", true) }
        val os = RecordingSpeaker(); val player = RecordingPlayer()
        val speaker = PluginSpeaker(s, os, player, log = {})
        assertEquals(0, livePluginProcesses(), "no plugin process before speaking")
        assertTrue(speaker.speak("build failed", 100, null, false))
        assertTrue(speaker.awaitIdle(30_000))
        assertEquals(listOf(3200), player.played)
        assertTrue(os.spoken.isEmpty())
        assertEquals(0, livePluginProcesses(), "no plugin process at idle")
    }

    @Test fun aFailingOrSilentPluginFallsBackToTheOsVoice() {
        for (mode in listOf("fail", "empty")) {
            val s = store(manifest(mode)).also { it.install("fake-$mode"); it.setEnabled("fake-$mode", true) }
            val os = RecordingSpeaker(); val player = RecordingPlayer()
            val speaker = PluginSpeaker(s, os, player, log = {})
            speaker.speak("alert $mode", 100, null, false)
            assertTrue(speaker.awaitIdle(30_000))
            assertEquals(listOf("alert $mode"), os.spoken, mode)
            assertTrue(player.played.isEmpty(), mode)
            s.remove("fake-$mode")
        }
    }

    @Test fun aPluginThatRunsPastItsTimeoutIsKilledAndTheOsVoiceSpeaks() {
        val s = store(manifest("hang", timeoutMs = 1_500)).also { it.install("fake-hang"); it.setEnabled("fake-hang", true) }
        val os = RecordingSpeaker(); val player = RecordingPlayer()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val speaker = PluginSpeaker(s, os, player, log = { logs += it })
        speaker.speak("urgent thing", 100, null, false)
        assertTrue(speaker.awaitIdle(30_000))
        assertEquals(listOf("urgent thing"), os.spoken)
        assertTrue(logs.any { "timed out" in it }, logs.toString())
        assertEquals(0, livePluginProcesses(), "the hung plugin was killed")
    }

    @Test fun aMissingOrUninstalledEnabledPluginFallsBack() {
        val s = store(manifest("ok"))
        s.setEnabled("fake-ok", true) // enabled but never installed
        val os = RecordingSpeaker(); val player = RecordingPlayer()
        val speaker = PluginSpeaker(s, os, player, log = {})
        speaker.speak("x", 100, null, false)
        assertTrue(speaker.awaitIdle(10_000))
        assertEquals(listOf("x"), os.spoken)
    }

    @Test fun aPlayerFailureFallsBack() {
        val s = store(manifest("ok")).also { it.install("fake-ok"); it.setEnabled("fake-ok", true) }
        val os = RecordingSpeaker()
        val speaker = PluginSpeaker(s, os, { _, _, _, _ -> throw IllegalStateException("no audio device") }, log = {})
        speaker.speak("y", 100, null, false)
        assertTrue(speaker.awaitIdle(30_000))
        assertEquals(listOf("y"), os.spoken)
    }

    @Test fun volumeScalesSamples() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // 16384, -16384
        val half = JavaSoundPlayer.scale(pcm, 50)
        assertEquals(listOf<Byte>(0x00, 0x20, 0x00, 0xE0.toByte()), half.toList())
        assertTrue(JavaSoundPlayer.scale(pcm, 100) === pcm)
    }
}

private class RecordingPlayer : PcmPlayer {
    val played: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList<Int>())
    override fun play(pcm: ByteArray, output: VoicePluginOutput, volumePercent: Int, cancelled: () -> Boolean) { played += pcm.size }
}
