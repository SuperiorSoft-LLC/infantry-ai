// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** One 0-100% volume control, shared by the spoken alerts and the incoming-comms sound. */
@Composable
fun VolumeSlider(percent: Int, onPercent: (Int) -> Unit, label: String = "Volume") {
    Text("$label $percent%", color = Palette.Muted, fontSize = 11.sp)
    Slider(percent / 100f, { onPercent((it * 100).toInt().coerceIn(0, 100)) })
}
