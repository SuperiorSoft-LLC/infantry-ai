package com.superiorsoftllc.agenthq.ui.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.VolumeSlider
import com.superiorsoftllc.agenthq.ui.generated.resources.Res
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

const val INCOMING_SOUND_TITLE = "Incoming comms sound"
const val INCOMING_SOUND_APPLIES_TO = "Applies to: a short sound when a message from an agent, an outside sender or AgentHQ itself reaches you. Your own messages and the history loaded on connect never play it; a burst plays once. Remembered across launches."
const val INCOMING_SOUND_ENABLED_TAG = "incoming-sound-enabled"
const val INCOMING_SOUND_TEST_TAG = "incoming-sound-test"
const val INCOMING_SOUND_CHOOSE_TAG = "incoming-sound-choose"
const val INCOMING_SOUND_RESET_TAG = "incoming-sound-reset"
private const val DEFAULT_SOUND_FILE = "files/incoming-comms.mp3"

/** Plays the sound the settings pick (the owner's copy, else the bundled default) at the settings' volume. */
suspend fun playIncomingSound(host: PlatformHost, settings: IncomingCommsSoundSettings): SoundSource = withContext(Dispatchers.Default) {
    val custom = if (settings.customName != null) runCatching { host.customSoundStore?.load() }.getOrNull() else null
    playWithFallback(host.soundPlayer, custom, settings.volumePercent) { Res.readBytes(DEFAULT_SOUND_FILE) }
}

/**
 * Plays the incoming-comms sound as [state] shows new messages for the owner. [onIncoming] gets every arrival (the hook a
 * visual effect can use); the sound itself is skipped when [settings] are off and coalesced by a 1.5 s cooldown.
 */
@Composable
fun IncomingCommsSoundEffect(state: StateFlow<AppState>, settings: IncomingCommsSoundSettings, onIncoming: (IncomingComms) -> Unit = {}) {
    val host = LocalPlatformHost.current
    val current by rememberUpdatedState(settings)
    val gate = remember { IncomingCommsGate() }
    val cooldown = remember { BurstCooldown() }
    LaunchedEffect(state) {
        state.collect { s ->
            val now = Clock.System.now().toEpochMilliseconds()
            val arrivals = gate.observe(now, s.connection.instance to s.connection.isLive, incomingSnapshots(s), s.historyRequested)
            if (arrivals.isEmpty()) return@collect
            arrivals.forEach(onIncoming)
            if (current.enabled && cooldown.tryAcquire(now)) launch { playIncomingSound(host, current) }
        }
    }
}

/** The "Incoming comms sound" block of the configuration modal. */
@Composable
fun IncomingCommsSoundSection(settings: IncomingCommsSoundSettings, onChange: (IncomingCommsSoundSettings) -> Unit) {
    val host = LocalPlatformHost.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(settings.enabled, { onChange(settings.copy(enabled = it)) }, Modifier.testTag(INCOMING_SOUND_ENABLED_TAG))
            Text(if (settings.enabled) "Play a sound for incoming comms" else "Incoming comms are silent", fontSize = 12.sp)
        }
        VolumeSlider(settings.volumePercent, { onChange(settings.copy(volumePercent = it)) })
        Text("Sound: ${settings.customName ?: "default"}", color = Palette.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.canPickSound && host.customSoundStore != null) {
                OutlinedButton(onClick = {
                    host.pickSound { picked ->
                        if (picked != null) {
                            if (host.customSoundStore?.save(picked.bytes) == true) { note = null; onChange(settings.copy(customName = picked.fileName)) }
                            else note = "Could not keep that file; the current sound stays."
                        }
                    }
                }, modifier = Modifier.testTag(INCOMING_SOUND_CHOOSE_TAG)) { Text("Choose mp3", fontSize = 12.sp) }
                if (settings.customName != null) {
                    OutlinedButton(onClick = {
                        host.customSoundStore?.clear(); note = null; onChange(settings.copy(customName = null))
                    }, modifier = Modifier.testTag(INCOMING_SOUND_RESET_TAG)) { Text("Reset to default", fontSize = 12.sp) }
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val used = playIncomingSound(host, settings)
                    note = when {
                        used == SoundSource.SILENT -> "No audio is available here."
                        used == SoundSource.DEFAULT && settings.customName != null -> "The chosen file is missing or unplayable; the default plays instead."
                        else -> null
                    }
                }
            }, modifier = Modifier.testTag(INCOMING_SOUND_TEST_TAG)) { Text("Test", fontSize = 12.sp) }
        }
        note?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
    }
}
