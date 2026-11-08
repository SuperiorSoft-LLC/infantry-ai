/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

/**
 * Personality for the eyes, applied as a transform on whatever dimensions the
 * current state's pose prescribes — so expressions keep their character
 * (Happy squints, Listening widens) while the overall look shifts.
 *
 * Dimensions are in ball-radius units, matching Face.EYE_W / EYE_H.
 *
 * Use with [MascotConfig.eyeStyle] or pass directly to [BotEngine.sample].
 */
public enum class EyeStyle(val apply: (Float, Float) -> Pair<Float, Float>) {
    /**
     * The measured reference look: tall capsules as in the original bloub design.
     */
    CLASSIC({ w, h -> w to h }),

    /**
     * The grokbots look: eyes scaled way up so the face reads at a glance.
     */
    BIG({ w, h -> (w * 1.55f) to (h * 1.32f) }),

    /**
     * Big and perfectly circular: curious cartoon mascot.
     */
    ROUND({ w, h ->
        val s = maxOf(w, h) * 1.18f
        s to s
    }),

    /**
     * Beady little dots: understated, slightly mischievous.
     */
    DOT({ _, _ -> 0.085f to 0.085f }),

    /**
     * Wide horizontal visor slits: robotic, deadpan.
     */
    VIZOR({ w, h -> (w * 1.7f) to (h * 0.45f) })
}