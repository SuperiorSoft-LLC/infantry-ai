package com.superiorsoftllc.infantry.ui.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** VOICE-1: Android's TextToSpeech. Speaks nothing until the engine reports it is ready (false, so the caller beeps). */
class AndroidSpeaker(context: Context) : Speaker {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    override fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean {
        if (!ready) return false
        voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumePercent.coerceIn(0, 100) / 100f) }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return tts.speak(text, mode, params, "ahq-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    override fun beep() {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
    }

    override fun voices(): List<String> = if (!ready) emptyList() else runCatching { tts.voices.orEmpty().map { it.name }.sorted() }.getOrDefault(emptyList())

    fun shutdown() { runCatching { tts.shutdown() } }
}
