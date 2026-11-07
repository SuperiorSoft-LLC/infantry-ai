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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.clickable

/** VOICE-SEAM-1: the listening indicator's test tag. */
const val LISTENING_INDICATOR_TAG = "listening-indicator"

/**
 * VOICE-SEAM-1: drawn by the shell root over every block (no plugin can replace or hide it) while a plugin captures the
 * microphone: which module listens, and a Stop that ends the capture.
 */
@Composable
fun ListeningIndicator(monitor: CaptureMonitor?, modifier: Modifier = Modifier) {
    monitor ?: return
    val active by monitor.active.collectAsState()
    val a = active ?: return
    Row(
        modifier.padding(8.dp).background(Palette.Danger, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp).testTag(LISTENING_INDICATOR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Listening: ${a.module}", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text("Stop", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClickLabel = "Stop listening") { a.stop() })
    }
}
