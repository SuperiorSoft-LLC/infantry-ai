/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

/**
 * A decorative stroked ring around the bot, in ball-radius units.
 * Rendered as a circle outline in the body color; [stroke] is the line
 * width, also in ball-radius units.
 *
 * This is part of the rendered frame data returned by [BotEngine.sample]
 * for states like [BotState.Orbit] and [BotState.Swirl].
 */
public data class ArcSpec(
    public val x: Float,
    public val y: Float,
    public val r: Float,
    public val stroke: Float = 0.05f,
    public val alpha: Float = 1f
)

/** Orbit's release flourish: three concentric rings centered on the origin. */
public val ORBIT_RINGS: List<ArcSpec> = listOf(
    ArcSpec(x = 0f, y = 0f, r = 1.35f, stroke = 0.06f),
    ArcSpec(x = 0f, y = 0f, r = 1.55f, stroke = 0.06f),
    ArcSpec(x = 0f, y = 0f, r = 1.75f, stroke = 0.06f)
)