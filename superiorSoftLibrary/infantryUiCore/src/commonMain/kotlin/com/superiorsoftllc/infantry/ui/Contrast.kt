package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** CHART-STYLE-1: WCAG 2.x contrast math, the one place the rule in docs/CHART-STYLE.md is computed. */
object Contrast {
    /** Minimum ratio for normal text (WCAG AA). */
    const val TEXT_AA = 4.5

    /** Minimum ratio for graphics that carry meaning: bars, lines, markers (WCAG 1.4.11). */
    const val GRAPHIC_AA = 3.0

    /** Contrast ratio of [a] on [b], 1.0 (identical) to 21.0 (black on white). Alpha is ignored: pass the flattened colour. */
    fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The more readable of white and near-black text on [bg] (used where the accent itself is the surface). */
    fun onColor(bg: Color): Color = listOf(Color.White, Color(0xFF111214)).maxBy { ratio(it, bg) }
}
