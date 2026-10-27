package com.superiorsoftllc.agenthq.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.FilterChip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.Switch
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val VOICE_TOAST_TAG = "voice-toast"
const val VOICE_ENABLED_TAG = "voice-enabled"
const val VOICE_APPLIES_TO = "Applies to: spoken alerts agents raise with `agenthq notify`, from any project (the project name is read first). Only the short text the agent wrote is spoken."

/**
 * VOICE-1: collects [spoken], speaks what [VoiceSettings] and the [SpeechGate] allow (a beep when the host has no voice),
 * and shows each fresh alert as a toast in the bottom-left corner of the enclosing box.
 */
@Composable
fun VoiceAlerts(spoken: Flow<SpokenNotice>, settings: VoiceSettings, modifier: Modifier = Modifier) {
    val host = LocalPlatformHost.current
    val gate = remember { SpeechGate() }
    val current by rememberUpdatedState(settings)
    var toast by remember { mutableStateOf<SpokenNotice?>(null) }
    LaunchedEffect(spoken) {
        spoken.collect { n ->
            val now = Clock.System.now()
            val hour = now.toLocalDateTime(TimeZone.currentSystemDefault()).hour
            val decision = gate.decide(n, current, now.toEpochMilliseconds(), hour, host.windowFocused())
            if (decision == SpeechDecision.SKIP_DUPLICATE) return@collect
            toast = n
            if (decision.speaks) {
                val spokeAloud = host.speaker?.speak(
                    spokenText(n), current.volumePercent, current.voiceName, interrupt = decision == SpeechDecision.SPEAK_INTERRUPT,
                ) == true
                if (!spokeAloud) host.speaker?.beep()
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(8_000); toast = null }
    }
    toast?.let { n ->
        Box(modifier.padding(16.dp).background(Palette.Border, RoundedCornerShape(8.dp)).padding(12.dp).testTag(VOICE_TOAST_TAG)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOfNotNull(n.projectName, VoiceLevel.fromWire(n.level).label).joinToString(" - "), color = Palette.Muted, fontSize = 11.sp)
                Text(n.say, fontSize = 13.sp)
            }
        }
    }
}

/** VOICE-1: the "Spoken alerts" block of the configuration modal. */
@Composable
fun VoiceSettingsSection(settings: VoiceSettings, onChange: (VoiceSettings) -> Unit) {
    val voices = LocalPlatformHost.current.speaker?.voices().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(settings.enabled, { onChange(settings.copy(enabled = it)) }, Modifier.testTag(VOICE_ENABLED_TAG))
            Text(if (settings.enabled) "Speak alerts" else "Alerts are silent (toast only)", fontSize = 12.sp)
        }
        Text("Minimum level to speak", color = Palette.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceLevel.entries.forEach { level ->
                FilterChip(settings.minLevel == level, { onChange(settings.copy(minLevel = level)) }, { Text(level.label, fontSize = 12.sp) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(settings.onlyWhenUnfocused, { onChange(settings.copy(onlyWhenUnfocused = it)) })
            Text("Only when the app window is not focused", fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(settings.quietHoursEnabled, { onChange(settings.copy(quietHoursEnabled = it)) })
            Text("Quiet hours", fontSize = 12.sp)
        }
        if (settings.quietHoursEnabled) {
            HourSlider("From", settings.quietStartHour) { onChange(settings.copy(quietStartHour = it)) }
            HourSlider("Until", settings.quietEndHour) { onChange(settings.copy(quietEndHour = it)) }
        }
        com.superiorsoftllc.agenthq.ui.VolumeSlider(settings.volumePercent, { onChange(settings.copy(volumePercent = it)) })
        if (voices.isNotEmpty()) {
            Text("Voice", color = Palette.Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(settings.voiceName == null, { onChange(settings.copy(voiceName = null)) }, { Text("Default", fontSize = 12.sp) })
                voices.take(4).forEach { v ->
                    FilterChip(settings.voiceName == v, { onChange(settings.copy(voiceName = v)) }, { Text(v, fontSize = 12.sp) })
                }
            }
        }
    }
}

@Composable
private fun HourSlider(label: String, hour: Int, onHour: (Int) -> Unit) {
    Text("$label ${hour.toString().padStart(2, '0')}:00", color = Palette.Muted, fontSize = 11.sp)
    Slider(hour / 23f, { onHour((it * 23).toInt().coerceIn(0, 23)) }, steps = 22)
}
