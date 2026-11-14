package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * UI-CPU-1: pacing for small looping decorations (spinner, shimmer, pulsing dot). A Compose infinite transition asks for
 * a frame every vsync, and on desktop each frame redraws and flushes the whole window, so one 10 dp spinner kept a
 * core busy. A stepped ticker asks for [maxFps] frames a second instead. Trade-off knob: 0 = every vsync (the old look).
 */
object AnimationPacing {
    /** Frames per second a looping decoration may ask for; 0 = unlimited. */
    var maxFps: Int = 10
}

/** A value looping 0..1 every [periodMs], refreshed at most [AnimationPacing.maxFps] times a second; [reverse] makes it ping-pong. */
@Composable
fun rememberSteppedFraction(periodMs: Int, reverse: Boolean = false): State<Float> {
    val out = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs, reverse) {
        var start = -1L
        while (true) {
            val fps = AnimationPacing.maxFps
            withFrameNanos { t ->
                if (start < 0) start = t
                val phase = (((t - start) / 1_000_000L) % periodMs).toFloat() / periodMs
                out.floatValue = if (reverse) (if (phase < 0.5f) phase * 2f else (1f - phase) * 2f) else phase
            }
            if (fps > 0) delay(1000L / fps)
        }
    }
    return out
}
