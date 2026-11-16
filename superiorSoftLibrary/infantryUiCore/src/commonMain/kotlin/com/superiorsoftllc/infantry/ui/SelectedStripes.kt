// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect

/** CARD-SELECT-1: tuning knobs for the selected-card stripes. */
const val SELECTED_STRIPE_CYCLE_MS = 3000
const val SELECTED_STRIPE_ALPHA = 0.14f
const val SELECTED_STRIPE_SPACING_PX = 28f
const val SELECTED_STRIPE_WIDTH_PX = 10f

/**
 * Slowly scrolling diagonal stripes in the accent colour, drawn behind the content. One infinite transition per
 * selected card; the phase is read only in the draw phase, so nothing recomposes per frame. [animate] false = static.
 */
@Composable
fun Modifier.selectedStripes(selected: Boolean, color: Color = Palette.Accent, animate: Boolean = ActiveBorder.animate): Modifier {
    if (!selected) return this
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "selected-stripes").animateFloat(
            0f, SELECTED_STRIPE_SPACING_PX,
            infiniteRepeatable(tween(SELECTED_STRIPE_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
    } else mutableFloatStateOf(0f)
    val stripe = color.copy(alpha = SELECTED_STRIPE_ALPHA)
    return drawBehind {
        clipRect {
            val s = SELECTED_STRIPE_SPACING_PX
            var x = -size.height + phase.value - s
            while (x < size.width + s) {
                drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), SELECTED_STRIPE_WIDTH_PX, StrokeCap.Butt)
                x += s
            }
        }
    }
}
