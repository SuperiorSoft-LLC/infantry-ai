// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import com.superiorsoftllc.infantry.plugin.api.SpeakOptions
import com.superiorsoftllc.infantry.plugin.api.SpeechOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VOICE-SEAM-1: the `speech.out` service over the host's [Speaker]. On desktop that speaker is the VOICE-PLUGIN-1 engine
 * the owner enabled, falling back to the OS voice; elsewhere the OS voice.
 */
class SpeakerSpeechOutput(private val speaker: Speaker) : SpeechOutput {
    override fun speak(text: String, options: SpeakOptions): Boolean =
        text.isNotBlank() && speaker.speak(text, options.volumePercent.coerceIn(0, 100), options.voice?.takeIf { it.isNotBlank() }, options.interrupt)

    override fun stop() = speaker.stop()

    override fun voices(): List<String> = speaker.voices()
}

/**
 * VOICE-SEAM-1: the host's one microphone slot (one capture at a time per host) and what the core-drawn listening
 * indicator shows. A capture that cannot take the slot ends BUSY; the indicator's Stop calls [Active.stop].
 */
class CaptureMonitor {
    class Active internal constructor(val module: String, val stop: () -> Unit)

    private val state = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = state

    /** Takes the slot for [module]; null = another capture holds it. Give the returned value to [end]. */
    fun begin(module: String, stop: () -> Unit): Active? = Active(module, stop).takeIf { state.compareAndSet(null, it) }

    /** Frees the slot [taken] holds (a stale one is ignored). */
    fun end(taken: Active) { state.compareAndSet(taken, null) }
}

/** VOICE-SEAM-1: the host setting (a UI pref, else this environment variable) for the longest capture, in seconds. */
const val AUDIO_CAPTURE_MAX_SECONDS_PREF_KEY = "audioCaptureMaxSeconds"
const val AUDIO_CAPTURE_MAX_SECONDS_ENV = "INFANTRY_AUDIO_CAPTURE_MAX_SECONDS"
const val AUDIO_CAPTURE_MAX_SECONDS_DEFAULT = 60

/** The seconds one capture may run: the plugin's [requested] limit, capped by the host's [setting] (default 60). */
fun captureLimitSeconds(setting: String?, requested: Int?): Int {
    val host = setting?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: AUDIO_CAPTURE_MAX_SECONDS_DEFAULT
    return requested?.takeIf { it > 0 }?.coerceAtMost(host) ?: host
}
