// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.IconActionTooltipBox
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.clickable

/** VOICE-SEAM-1: the listening indicator's test tag. */
const val LISTENING_INDICATOR_TAG = "listening-indicator"

/** The compact label: a dot, the elapsed time, one tap stops. The module name is in the tooltip. */
fun listeningElapsed(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/**
 * VOICE-SEAM-1 / UI-VOICE-LISTEN-1: drawn by the shell root over every block (no plugin can replace or hide it) while a
 * plugin captures the microphone. One small pill (red dot, elapsed time, stop square) that fits a phone; tapping it
 * stops the capture; the tooltip names the module.
 */
@Composable
fun ListeningIndicator(monitor: CaptureMonitor?, modifier: Modifier = Modifier) {
    monitor ?: return
    val active by monitor.active.collectAsState()
    val a = active ?: return
    var seconds by remember(a) { mutableStateOf(0) }
    LaunchedEffect(a) { while (true) { delay(1000); seconds++ } }
    IconActionTooltipBox("Listening: ${a.module}. Tap to stop", modifier = modifier.padding(4.dp), describe = false) { tip ->
        Row(
            tip.background(Palette.Danger, RoundedCornerShape(16.dp)).clickable(onClickLabel = "Stop listening") { a.stop() }
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag(LISTENING_INDICATOR_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(Color.White, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(listeningElapsed(seconds), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).background(Color.White, RoundedCornerShape(1.dp)))
        }
    }
}
