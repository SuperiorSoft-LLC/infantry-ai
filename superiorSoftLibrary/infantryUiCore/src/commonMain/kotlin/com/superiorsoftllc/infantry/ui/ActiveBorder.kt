// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** BG-ACTIVE-2: how a session's card border reads. THINKING = a turn is running; BACKGROUND = only a bg job runs. */
enum class ActiveBorderStyle { NONE, THINKING, BACKGROUND }

/** Setting key (on by default) that turns the animation off; the border then stays static-bright while active. */
const val ACTIVE_BORDER_SETTING_KEY = "ui.activeBorderAnimation"

fun activeBorderStyle(state: SessionState, turnActive: Boolean?, hasBackground: Boolean): ActiveBorderStyle = when {
    state == SessionState.STARTING -> ActiveBorderStyle.THINKING
    state != SessionState.BUSY -> ActiveBorderStyle.NONE
    turnActive == false && hasBackground -> ActiveBorderStyle.BACKGROUND
    else -> ActiveBorderStyle.THINKING
}

fun SessionRecord.activeBorderStyle(): ActiveBorderStyle =
    activeBorderStyle(state, turnActive, backgroundWork != null || bgJobs.isNotEmpty())

/** Pulse period in ms: thinking is quick, background-only is slower, so the two read differently from afar. */
fun ActiveBorderStyle.periodMs(): Int = when (this) { ActiveBorderStyle.THINKING -> 1200; ActiveBorderStyle.BACKGROUND -> 2800; else -> 0 }

/** Pulse alpha 0.35..1 for [style] at [timeMs]; 1 (static bright) when [animate] is false. */
fun activeBorderAlpha(style: ActiveBorderStyle, timeMs: Long, animate: Boolean): Float {
    val p = style.periodMs()
    if (!animate || p == 0) return 1f
    return 0.675f + 0.325f * sin(2 * PI * (timeMs % p) / p).toFloat()
}

/** Settings + the ONE shared animation clock (a single ticker for every card; it runs only while a card needs it). */
object ActiveBorder {
    var enabled: Boolean by mutableStateOf(true)
    var reducedMotion: Boolean by mutableStateOf(false)
    val animate: Boolean get() = enabled && !reducedMotion

    /** Milliseconds, advanced ~20 times a second by the single ticker. */
    var clockMs by mutableFloatStateOf(0f)
        private set

    private var users = 0
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun acquire() {
        users++
        if (ticker == null) ticker = scope.launch {
            var t = 0L
            while (isActive) { delay(50); t += 50; clockMs = t.toFloat() }
        }
    }

    fun release() {
        if (--users <= 0) { users = 0; ticker?.cancel(); ticker = null }
    }
}

/** Border colour from theme tokens: Info while thinking, Warn for background-only. */
fun activeBorderColor(style: ActiveBorderStyle): Color = if (style == ActiveBorderStyle.BACKGROUND) Palette.Warn else Palette.Info

/** The 3dp pulsing (or static-bright) border; reads the shared clock only while animating. */
fun Modifier.activeBorder(style: ActiveBorderStyle, shape: Shape): Modifier {
    if (style == ActiveBorderStyle.NONE) return this
    val alpha = activeBorderAlpha(style, if (ActiveBorder.animate) ActiveBorder.clockMs.toLong() else 0L, ActiveBorder.animate)
    return border(3.dp, activeBorderColor(style).copy(alpha = alpha), shape)
}
