/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 2π as a float constant. */
internal const val TAU: Float = (2.0 * PI).toFloat()

/** Clamps [v] to [lo]..[hi]. */
internal fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float =
    if (v < lo) lo else if (v > hi) hi else v

/** Linear interpolation. */
internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Transitions measured off the reference video: exponential-style ease-outs,
 * no overshoot of the body. The only springy effects are local (notification
 * pop, eye opening) and are written directly in their state.
 */
internal object Easings {
    fun easeOutCubic(t: Float): Float {
        val x = clamp(t)
        return 1f - (1f - x).pow3()
    }

    fun easeInOutCubic(t: Float): Float {
        val x = clamp(t)
        return if (x < 0.5f) 4f * x.pow3() else 1f - (-2f * x + 2f).pow3() / 2f
    }

    fun easeOutQuint(t: Float): Float {
        val x = clamp(t)
        return 1f - (1f - x).pow5()
    }

    /**
     * State-transition curve with a touch of pantomime: a brief ~4% counter-
     * move toward the origin (anticipation) before the main travel, then a
     * soft ~3% overshoot past the target and settle on arrival. Endpoints and
     * continuity match [easeOutQuint] (0 -> 0, 1 -> 1); the excursion beyond
     * [0, 1] is intentional and small enough that blended decor alphas stay
     * clamped downstream.
     */
    fun easeSettle(t: Float): Float {
        val x = clamp(t)
        val ant = 0.14f
        if (x < ant) {
            // anticipation: dip back toward the departure pose first
            return -0.04f * sin((x / ant) * kotlin.math.PI.toFloat())
        }
        val u = (x - ant) / (1f - ant)
        val c1 = 0.9f
        val c3 = c1 + 1f
        val d = u - 1f
        return 1f + c3 * d * d * d + c1 * d * d
    }
}

private fun Float.pow3(): Float {
    val y = this * this * this
    return y
}

private fun Float.pow5(): Float {
    val y = this * this
    return y * y * this
}

/** Periodic 1D noise: loops seamlessly over [period], used for gaze drift. */
internal fun loopNoise(t: Float, period: Float, seed: Float = 0f): Float {
    val p = (t / period) * TAU
    return 0.55f * sin(p + seed) +
        0.3f * sin(2f * p + seed * 1.7f + 1.1f) +
        0.15f * sin(3f * p + seed * 2.3f + 2.4f)
}

/**
 * Deterministic PRNG (mulberry32): same sequence on every read.
 * Returns values in [0, 1).
 */
internal class Mulberry32(seed: Int) {
    private var a = seed.toLong() and 0xFFFFFFFFL

    operator fun invoke(): Float {
        a = (a + 0x6D2B79F5L) and 0xFFFFFFFFL
        // Int arithmetic overflows wrap, matching JS Math.imul semantics.
        var t: Int = (a xor (a ushr 15)).toInt() * (a.toInt() or 1)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        val out = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        return out / 4294967296f
    }
}