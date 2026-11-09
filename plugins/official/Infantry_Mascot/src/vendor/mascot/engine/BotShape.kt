/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A silhouette = one radial profile r(theta) plus a pose.
 *
 * Everything goes through profiles sampled at the SAME number of angles: any
 * two shapes have matching points one-to-one, so morphing reduces to a linear
 * interpolation of radii. This is what makes transitions clean without a path
 * morphing library.
 *
 * Units: radius of the resting ball = 1. theta = 0 points right and grows
 * clockwise on screen (y down), matching canvas coordinates.
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal class Silhouette(
    public val radii: FloatArray,
    public val rot: Float = 0f,
    /** center offset, in ball-radius units */
    public val cx: Float = 0f,
    public val cy: Float = 0f,
    /** squash & stretch, applied in screen space (after rotation) */
    public val sx: Float = 1f,
    public val sy: Float = 1f
)

internal const val PROFILE_SAMPLES = 64

private val ANGLES = FloatArray(PROFILE_SAMPLES) { i -> (i.toFloat() / PROFILE_SAMPLES) * TAU }
private val COS = FloatArray(PROFILE_SAMPLES) { cos(ANGLES[it]) }
private val SIN = FloatArray(PROFILE_SAMPLES) { sin(ANGLES[it]) }

/** Perfect circle: neutral base (ball, dot, bubble). */
internal fun circle(
    radius: Float,
    cx: Float = 0f,
    cy: Float = 0f,
    rot: Float = 0f,
    sx: Float = 1f,
    sy: Float = 1f
): Silhouette = Silhouette(FloatArray(PROFILE_SAMPLES) { radius }, rot, cx, cy, sx, sy)

/**
 * Egg silhouette, measured off the reference video (footprint 1.647 x 2.000
 * ball-radius): same height as the resting ball, narrower across.
 */
internal fun eggProfile(): FloatArray = floatArrayOf(
    0.8369f, 0.8424f, 0.8497f, 0.8585f, 0.8674f, 0.8775f, 0.8878f, 0.8983f,
    0.9089f, 0.9185f, 0.9288f, 0.9374f, 0.9445f, 0.9504f, 0.9543f, 0.9559f,
    0.9555f, 0.9519f, 0.9466f, 0.9389f, 0.9302f, 0.9193f, 0.9085f, 0.8969f,
    0.8852f, 0.8734f, 0.8625f, 0.8513f, 0.8411f, 0.8325f, 0.8243f, 0.8179f,
    0.8137f, 0.8112f, 0.8102f, 0.8128f, 0.8178f, 0.8262f, 0.8374f, 0.8518f,
    0.8702f, 0.8922f, 0.9169f, 0.9446f, 0.9741f, 1.0023f, 1.0267f, 1.0433f,
    1.0481f, 1.0393f, 1.0216f, 0.9970f, 0.9697f, 0.9418f, 0.9169f, 0.8949f,
    0.8760f, 0.8604f, 0.8490f, 0.8394f, 0.8337f, 0.8314f, 0.8305f, 0.8326f
)

/**
 * Triangle pointing up with heavily rounded corners (measured off bloub's
 * "play" state, frame 190, footprint 1.995 x 1.884 ball-radius).
 */
internal fun triangleProfile(): FloatArray = floatArrayOf(
    0.7819f, 0.8211f, 0.8747f, 0.9440f, 1.0223f, 1.0960f, 1.1401f, 1.1340f,
    1.0808f, 1.0047f, 0.9265f, 0.8603f, 0.8104f, 0.7730f, 0.7450f, 0.7273f,
    0.7151f, 0.7118f, 0.7148f, 0.7245f, 0.7427f, 0.7680f, 0.8037f, 0.8518f,
    0.9148f, 0.9876f, 1.0583f, 1.1073f, 1.1109f, 1.0667f, 0.9940f, 0.9164f,
    0.8482f, 0.7948f, 0.7555f, 0.7261f, 0.7056f, 0.6925f, 0.6859f, 0.6869f,
    0.6938f, 0.7084f, 0.7305f, 0.7615f, 0.8040f, 0.8595f, 0.9311f, 1.0092f,
    1.0791f, 1.1171f, 1.1054f, 1.0501f, 0.9779f, 0.9050f, 0.8450f, 0.7990f,
    0.7656f, 0.7413f, 0.7258f, 0.7160f, 0.7146f, 0.7204f, 0.7330f, 0.7528f
)

/** Hexagon pointing up, very rounded corners (measured, footprint 1.826 x 2.011). */
internal fun hexagonProfile(): FloatArray = floatArrayOf(
    0.9210f, 0.9282f, 0.9441f, 0.9706f, 0.9984f, 1.0059f, 0.9896f, 0.9562f,
    0.9290f, 0.9124f, 0.9047f, 0.9058f, 0.9157f, 0.9349f, 0.9642f, 0.9873f,
    0.9882f, 0.9665f, 0.9336f, 0.9105f, 0.8968f, 0.8918f, 0.8955f, 0.9080f,
    0.9293f, 0.9611f, 0.9820f, 0.9812f, 0.9590f, 0.9282f, 0.9089f, 0.8978f,
    0.8964f, 0.9026f, 0.9189f, 0.9439f, 0.9778f, 0.9990f, 0.9964f, 0.9713f,
    0.9439f, 0.9274f, 0.9196f, 0.9206f, 0.9308f, 0.9502f, 0.9799f, 1.0121f,
    1.0226f, 1.0071f, 0.9752f, 0.9510f, 0.9366f, 0.9316f, 0.9351f, 0.9485f,
    0.9711f, 1.0026f, 1.0213f, 1.0155f, 0.9863f, 0.9547f, 0.9347f, 0.9232f
)

/**
 * Convex hull of two circles as a point polygon: the tapered bar of the "!".
 */
internal fun hullOfCirclesPoints(
    x1: Float, y1: Float, r1: Float,
    x2: Float, y2: Float, r2v: Float,
    steps: Int = 96
): Array<Offset> {
    val dx = x2 - x1
    val dy = y2 - y1
    val dist = hypot(dx, dy).let { if (it == 0f) 1e-6f else it }
    val base = atan2(dy, dx)
    val ratio = ((r1 - r2v) / dist).coerceIn(-1f, 1f)
    val spread = acos(ratio)
    val poly = ArrayList<Offset>(steps + 2)
    // arc of the larger circle
    for (i in 0..steps / 2) {
        val a = base + spread + ((TAU - 2f * spread) * i) / (steps / 2)
        poly.add(Offset(x1 + cos(a) * r1, y1 + sin(a) * r1))
    }
    // arc of the smaller circle
    for (i in 0..steps / 2) {
        val a = base - spread + ((2f * spread) * i) / (steps / 2)
        poly.add(Offset(x2 + cos(a) * r2v, y2 + sin(a) * r2v))
    }
    return poly.toTypedArray()
}

/**
 * Radial profile of a convex hull of two circles, ray-cast from (cx, cy).
 * Used for the tapered bar of the "!" glyphs. Computed once, never per frame.
 */
internal fun profileFromHullOfCircles(
    x1: Float, y1: Float, r1: Float,
    x2: Float, y2: Float, r2v: Float,
    cx: Float = 0f,
    cy: Float = 0f,
    steps: Int = 96
): FloatArray =
    profileFromPolygon(hullOfCirclesPoints(x1, y1, r1, x2, y2, r2v, steps), cx, cy)

/**
 * Arbitrary polygon -> radial profile, by ray casting from [center].
 * Used for shapes that do not express naturally as r(theta).
 */
internal fun profileFromPolygon(poly: Array<Offset>, cx: Float, cy: Float): FloatArray {
    val radii = FloatArray(PROFILE_SAMPLES)
    val n = poly.size
    for (k in 0 until PROFILE_SAMPLES) {
        val dx = COS[k]
        val dy = SIN[k]
        var best = 0f
        for (i in 0 until n) {
            val a = poly[i]
            val b = poly[(i + 1) % n]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-9f) continue
            val px = a.x - cx
            val py = a.y - cy
            val t = (px * ey - py * ex) / den // distance along the ray
            val u = (px * dy - py * dx) / den // position along the segment
            if (t > best && u >= 0f && u <= 1f) best = t
        }
        radii[k] = best
    }
    return radii
}

private fun abs(v: Float): Float = if (v < 0f) -v else v

/** Radius of a profile in an arbitrary direction, interpolated between neighbors. */
internal fun radiusAtAngle(radii: FloatArray, angle: Float): Float {
    val n = radii.size
    val t = ((((angle / TAU) % 1f) + 1f) % 1f) * n
    val i = t.toInt()
    return lerp(radii[i % n], radii[(i + 1) % n], t - i)
}

/** Interpolation of two silhouettes; allocates a fresh result. */
internal fun blend(a: Silhouette, b: Silhouette, t: Float): Silhouette {
    val radii = FloatArray(PROFILE_SAMPLES) { i -> lerp(a.radii[i], b.radii[i], t) }
    // Shortest-path rotation: avoid a full turn when going e.g. +170deg -> -170deg.
    var dRot = b.rot - a.rot
    while (dRot > PI.toFloat()) dRot -= TAU
    while (dRot < -PI.toFloat()) dRot += TAU
    return Silhouette(
        radii = radii,
        rot = a.rot + dRot * t,
        cx = lerp(a.cx, b.cx, t),
        cy = lerp(a.cy, b.cy, t),
        sx = lerp(a.sx, b.sx, t),
        sy = lerp(a.sy, b.sy, t)
    )
}

/** Projects the silhouette to screen points; [scale] = ball radius in px. */
internal fun toPoints(s: Silhouette, scale: Float, out: MutableList<Offset>) {
    out.clear()
    val cr = cos(s.rot)
    val sr = sin(s.rot)
    for (i in 0 until PROFILE_SAMPLES) {
        val r = s.radii[i]
        val x = r * COS[i]
        val y = r * SIN[i]
        // rotation then screen-space squash, then translation
        val rx = x * cr - y * sr
        val ry = x * sr + y * cr
        out.add(Offset((rx * s.sx + s.cx) * scale, (ry * s.sy + s.cy) * scale))
    }
}