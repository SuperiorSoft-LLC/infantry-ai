// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.RunPill
import com.superiorsoftllc.infantry.ui.model.RunPillKind
import com.superiorsoftllc.infantry.ui.net.Controller

/**
 * RUN-CONSOLE: opens a run's console window, supplied by the platform shell (the desktop: JediTerm over the daemon's
 * `run_bytes`). Null where the platform has none (web, Android): a pill then shows the run's plain-text lines in the
 * Run tab's output pane instead.
 */
fun interface RunConsoleHost {
    fun open(runId: String, controller: Controller)
}

val LocalRunConsole = staticCompositionLocalOf<RunConsoleHost?> { null }

fun runPillTag(runId: String) = "run-pill-$runId"

private fun RunPillKind.color(): Color = when (this) {
    RunPillKind.RUNNING -> Palette.Info
    RunPillKind.DONE -> Palette.Ok
    RunPillKind.FAILED -> Palette.Danger
    RunPillKind.STARTING, RunPillKind.STOPPED -> Palette.Muted
}

/** A run's pill, in the app's chip style: a drawn state icon, the label, the state ("running 0:42", "failed, exit 3"). */
@Composable
fun RunPillChip(pill: RunPill, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = pill.kind.color()
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.clip(shape)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(runPillTag(pill.runId)),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunStateIcon(pill.kind, color)
        Text(pill.label, color = Palette.Text, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(pill.status, color = color, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

/** Drawn, never a text glyph: a turning arc while the run is going, else a check, a cancel mark or a stop square. */
@Composable
private fun RunStateIcon(kind: RunPillKind, color: Color) {
    val m = Modifier.size(10.dp)
    when (kind) {
        RunPillKind.RUNNING, RunPillKind.STARTING -> {
            val turn by rememberInfiniteTransition(label = "run-pill-spin").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "run-pill-angle",
            )
            Canvas(m) {
                val w = size.minDimension * 0.18f
                drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(w))
                drawArc(color, turn, 100f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        RunPillKind.DONE -> Icon(AhqIcons.Check, contentDescription = null, tint = color, modifier = m)
        RunPillKind.FAILED -> Icon(AhqIcons.Cancel, contentDescription = null, tint = color, modifier = m)
        RunPillKind.STOPPED -> Icon(AhqIcons.Stop, contentDescription = null, tint = color, modifier = m)
    }
}
