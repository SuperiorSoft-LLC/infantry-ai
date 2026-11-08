/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** A decorative dot: circle, or the teardrop of the "!" glyph when [tear].
 *  [onBody] dots are painted on top of the body in the eye color. */
public data class DotSpec(
    public val x: Float,
    public val y: Float,
    public val r: Float,
    public val alpha: Float = 1f,
    public val tear: Boolean = false,
    public val rotDeg: Float = 0f,
    public val onBody: Boolean = false
)

/** Notification badge, positioned in ball-radius units. */
public data class NotifSpec(public val x: Float, public val y: Float, public val r: Float)

/**
 * One instant of the bot: silhouette, head orientation, eye configs and decor.
 * All geometry lives in ball-radius units (resting ball radius = 1).
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal class Pose(
    public val sil: Silhouette,
    public val gaze: HeadGaze = Face.REST_GAZE,
    public val split: Float = Face.EYE_SPLIT,
    public val eye0: EyeCfg = EyeCfg(Face.EYE_W, Face.EYE_H),
    public val eye1: EyeCfg = EyeCfg(Face.EYE_W, Face.EYE_H),
    public val eyeAlpha: Float = 1f,
    public val dots: List<DotSpec> = emptyList(),
    public val arcs: List<ArcSpec> = emptyList(),
    public val notif: NotifSpec? = null
)

private fun basePose(
    sil: Silhouette = circle(1f),
    gaze: HeadGaze = Face.REST_GAZE,
    split: Float = Face.EYE_SPLIT,
    w0: Float = Face.EYE_W, h0: Float = Face.EYE_H, t0: Float = 0f,
    w1: Float = Face.EYE_W, h1: Float = Face.EYE_H, t1: Float = 0f,
    eyeAlpha: Float = 1f,
    dots: List<DotSpec> = emptyList(),
    arcs: List<ArcSpec> = emptyList(),
    notif: NotifSpec? = null
) = Pose(
    sil = sil, gaze = gaze, split = split,
    eye0 = EyeCfg(w0, h0, tilt = t0),
    eye1 = EyeCfg(w1, h1, tilt = t1),
    eyeAlpha = eyeAlpha, dots = dots, arcs = arcs, notif = notif
)

/* ------------------------------------------------- non-radial body shapes */

/**
 * Bar of the upright "!": convex hull of two circles.
 * Measured: top circle (0, -0.505) r 0.132, bottom circle (0, +0.130) r 0.075,
 * straight flanks — hence tapered (top/bottom ratio 1.76).
 */
private val EGG_RADII by lazy { eggProfile() }
private val HEX_RADII by lazy { hexagonProfile() }
private val TRIANGLE_RADII by lazy { triangleProfile() }

/**
 * The rounded "play" triangle doesn't spin on its axis: its centre circles
 * the origin (measured r 0.213), which is what makes it read as tumbling
 * rather than rotating. We let the rotation sway gently around that orbit.
 */
private const val TRI_ORBIT = 0.213f

/** bloub's comet: the ball collapses to this pebble before regrowing. */
private const val COMET_DOT = 0.129f

/**
 * Burst sparkles: five spiraling-in particles, one born every 0.2 s.
 * Deterministic seeds (bloub uses a fixed-seed RNG; evenly spread angles
 * read the same).
 */
private val BURST_SEEDS = Array(5) { i ->
    Triple(i * 0.2f, 0.58f + (i % 3) * 0.09f, i * (TAU / 5f) + 0.7f)
}

private const val BAR_UPRIGHT_CY = -0.1875f
private val BAR_UPRIGHT_RADII =
    profileFromHullOfCircles(0f, -0.505f, 0.132f, 0f, 0.13f, 0.075f, 0f, BAR_UPRIGHT_CY)

/** Bar of the tilted "!": pure capsule (constant width 0.269, length 0.776). */
private val BAR_ITALIC_RADII =
    profileFromHullOfCircles(0f, -0.2535f, 0.1345f, 0f, 0.2535f, 0.1345f)

/**
 * The dot of the tilted "!" is a teardrop: round end (r 0.118) toward the bar,
 * pointed tail opposite, length 0.300 along the glyph axis.
 */
public val TEAR_POINTS: Array<androidx.compose.ui.geometry.Offset> by lazy {
    hullOfCirclesPoints(0f, 0f, 0.118f, 0f, 0.172f, 0.012f)
}

/* ------------------------------------------------------------ state utils */

/** Smooth 0->1 ramp with zero-slope ends; softens the start of a sweep. */
private fun smoothStep(x: Float): Float {
    val c = clamp(x)
    return c * c * (3f - 2f * c)
}

/** Three-dot layout of the processing animation, measured off the video. */
public val DOT_X = floatArrayOf(-0.557f, -0.013f, 0.532f)

/** Radius of the processing dots, in ball-radius units. */
public const val DOT_R = 0.165f

/** Peak scale factor of a processing dot at the top of its pulse. */
public const val DOT_PEAK = 1.25f

/** Left-to-right pulse wave traveling across the three dots. */
public fun dotPulse(t: Float, index: Int): Float {
    val raw = (t - index * 0.32f) / 1.0f
    val p = raw - kotlin.math.floor(raw)
    val k = if (p < 0.5f) 0.5f - 0.5f * cos(p * TAU) else 0f
    return clamp(k * 2f)
}

/** Processing pose shared by Thinking and Working.
 *  The body KEEPS its full size: three dots CHASE each other around an
 *  invisible ring on the belly — front dots bigger and brighter than back
 *  dots, so the orbit reads with real depth — while the ball does a happy
 *  little bounce. Far more alive than pulsing in place. */
private fun dotsPose(t: Float): Pose {
    val spin = t * (TAU / 2.4f)
    val dots = (0..2).map { i ->
        val a = spin + i * (TAU / 3f)
        val depth = 0.5f + 0.5f * sin(a)
        DotSpec(
            x = cos(a) * 0.34f,
            y = 0.04f + sin(a) * 0.10f,
            r = DOT_R * (0.55f + 0.38f * depth),
            alpha = 0.40f + 0.60f * depth,
            onBody = true
        )
    }
    return basePose(
        sil = circle(1f, cy = sin(t * (TAU / 1.4f)) * 0.014f),
        eyeAlpha = 0f,
        dots = dots
    )
}

/**
 * Emotional/operational states for the bot mascot.
 *
 * Poses are ported from bloub (SVG recreation of the x.ai Grok avatar):
 * single filled silhouette morphing through radial profiles, two capsule eyes
 * painted on a sphere, exponential ease-out transitions, life at rest through
 * gaze drift and blinking.
 *
 * - Idle: calm breathing ball
 * - Listening: wide surprised eyes ("wide" state)
 * - Thinking / Working: three pulsing dots, no face
 * - Responding: lively ball, slightly raised gaze
 * - Alert: tilted "!" glyph with travel and buzz
 * - Happy: wink
 * - Confused: mismatched eyes with mirrored tilts
 * - Sleepy: small bouncing ball, standby
 * - Notify: round wide eyes + blue notification badge pop
 *
 * Additional expressive states:
 * - Narrating: reading aloud, eyes lowered toward content
 * - Sad: droopy mirrored tilts, soft breathing
 * - Love: round shining eyes, giddy sway
 * - Silly: crossed mismatched tilts, goofball
 * - Egg: egg-shaped body
 * - Hexagon: hexagonal body
 * - Smile: genuine two-eyed warm smile, body lifted
 * - Greet: friendly hello hop with a wave flick
 * - Reading: eyes tracking text lines in slow left-to-right sweeps
 * - Proud: chest-up settle, warm half-lidded eyes
 *
 * Playful shape morphs (from bloub):
 * - Play: tumbling rounded triangle
 * - Burst: collapse to pebble with spiral sparkles
 * - Comet: shrink to comet dot with orbiting trail
 * - Orbit: triangle tumble with concentric rings
 * - Swirl: compressed ring flourish
 * - Exclaim: upright "!" glyph
 */
public sealed class BotState(
    /** Unique identifier for this state (lowercase, e.g. "idle", "thinking"). */
    public val name: String,
    /** Duration of the entry morph, in seconds. */
    public val morphSeconds: Float,
    /**
     * Period of the periodic part of [poseFn], in pose-clock ms — the TAU
     * denominator of the silhouette bob / dot orbit / collapse cycle. Hosts
     * that tile the animation (widget loop frames) sweep exactly this period
     * so their wrap stays seamless when poses change. 0 = static geometry.
     */
    public val posePeriodMs: Long = 0L,
    /** true = the entry is masked by a blink, as in the reference video */
    public val blinkIn: Boolean,
    /** Local state time (seconds) -> pose. Internal use only. */
    internal val poseFn: (Float) -> Pose
) {
    // --- Core States ---

    /**
     * Calm breathing ball with friendly eyes facing the viewer.
     * Gentle happy bob plus a slow breathing swell (a couple of percent of
     * the radius); the rare micro-saccades of the rest-life layer land on
     * top, so long idle stretches never read as static.
     */
    public object Idle : BotState(
        name = "idle",
        morphSeconds = 0.32f,
        posePeriodMs = 3400L,
        blinkIn = false,
        poseFn = { t ->
            val breathe = sin(t * (TAU / 3.4f))
            basePose(
                sil = circle(
                    1f,
                    cy = sin(t * (TAU / 1.7f)) * 0.006f,
                    sy = 1f + breathe * 0.013f
                ),
                gaze = HeadGaze(yaw = 0f, pitch = 8f, roll = -8f),
                w0 = 0.21f, h0 = 0.45f,
                w1 = 0.21f, h1 = 0.45f
            )
        }
    )

    /**
     * Wide surprised eyes ("wide" state) — attentive listening pose.
     * Entry masked by a blink.
     */
    public object Listening : BotState(
        name = "listening",
        morphSeconds = 0.38f,
        blinkIn = true,
        poseFn = { _ ->
            basePose(
                gaze = HeadGaze(yaw = 6.92f, pitch = -21.96f, roll = 11.6f),
                split = 18.43f,
                w0 = 0.356f, h0 = 0.875f,
                w1 = 0.356f, h1 = 0.875f
            )
        }
    )

    /**
     * Three pulsing dots orbiting on the belly, no face.
     * Body keeps full size while dots chase each other with depth.
     * Entry masked by a blink.
     */
    public object Thinking : BotState(
        name = "thinking",
        morphSeconds = 0.28f,
        posePeriodMs = 2400L,
        blinkIn = true,
        poseFn = ::dotsPose
    )

    /**
     * Lively ball with slightly raised gaze and cheerful micro-bounce.
     * Streaming a reply.
     */
    public object Responding : BotState(
        name = "responding",
        morphSeconds = 0.32f,
        posePeriodMs = 800L,
        blinkIn = false,
        poseFn = { t ->
            val bob = sin(t * (TAU / 0.8f)) * 0.018f
            basePose(
                sil = circle(1f, cy = bob),
                gaze = HeadGaze(yaw = 10f, pitch = 14f, roll = -13f),
                split = 16f,
                w0 = 0.196f, h0 = 0.42f,
                w1 = 0.196f, h1 = 0.42f
            )
        }
    )

    /**
     * Alarmed pose: ball stays full size, stretches a touch, vibrates at 2.5 Hz,
     * opens huge round eyes. No blink on entry.
     */
    public object Alert : BotState(
        name = "alert",
        morphSeconds = 0.32f,
        posePeriodMs = 400L,
        blinkIn = false,
        poseFn = { t ->
            val buzz = sin(t * 2.5f * TAU) * 0.006f
            basePose(
                sil = circle(1f, cx = buzz, sx = 1.08f),
                gaze = HeadGaze(yaw = 0f, pitch = -24f, roll = 0f),
                split = 19f,
                w0 = 0.30f, h0 = 0.30f,
                w1 = 0.30f, h1 = 0.30f
            )
        }
    )

    /**
     * Upright "!" glyph: tapered bar with dot below, no face.
     * Static geometry.
     */
    public object Exclaim : BotState(
        name = "exclaim",
        morphSeconds = 0.45f,
        blinkIn = false,
        poseFn = { _ ->
            basePose(
                sil = Silhouette(BAR_UPRIGHT_RADII),
                eyeAlpha = 0f,
                dots = listOf(DotSpec(x = -0.012f, y = 0.526f, r = 0.113f))
            )
        }
    )

    /**
     * Wink: one eye closed as a horizontal dash wider than the open eye.
     * Entry masked by a blink.
     */
    public object Happy : BotState(
        name = "happy",
        morphSeconds = 0.22f,
        blinkIn = true,
        poseFn = { _ ->
            basePose(
                gaze = HeadGaze(yaw = -5.37f, pitch = 4.55f, roll = 6.7f),
                split = 16.25f,
                w0 = 0.236f, h0 = 0.464f,
                w1 = 0.447f, h1 = 0.089f
            )
        }
    )

    /**
     * Mismatched mirrored-tilt eyes with size mismatch.
     * Per-eye tilt enables this exact expression.
     */
    public object Confused : BotState(
        name = "confused",
        morphSeconds = 0.28f,
        blinkIn = false,
        poseFn = { _ ->
            basePose(
                gaze = HeadGaze(yaw = -14f, pitch = 2f, roll = -26f),
                split = 15f,
                w0 = 0.236f, h0 = 0.40f, t0 = 10f,
                w1 = 0.30f, h1 = 0.30f, t1 = -14f
            )
        }
    )

    /**
     * Softly breathing ball with droopy closed-eye dashes, plus one dream
     * bubble that drifts up beside it and pops every 2.8 s.
     */
    public object Sleepy : BotState(
        name = "sleepy",
        morphSeconds = 0.5f,
        posePeriodMs = 2800L,
        blinkIn = false,
        poseFn = { t ->
            val breathe = sin(t * (TAU / 2.8f))
            val p = (t % 2.8f) / 2.8f
            basePose(
                sil = circle(0.97f, sy = 0.95f + 0.02f * breathe),
                gaze = HeadGaze(yaw = 0f, pitch = -6f, roll = -13f),
                w0 = 0.447f, h0 = 0.089f, t0 = -8f,
                w1 = 0.447f, h1 = 0.089f, t1 = 8f,
                dots = listOf(
                    DotSpec(
                        x = 0.55f + 0.06f * p,
                        y = -0.95f - 0.45f * p,
                        r = 0.055f + 0.03f * p,
                        alpha = clamp(p * 6f) * (1f - p)
                    )
                )
            )
        }
    )

    /**
     * Round wide eyes + blue notification badge pop (peak +14% around 0.3 s).
     * Gaze moves away from the badge. Entry masked by a blink.
     */
    public object Notify : BotState(
        name = "notify",
        morphSeconds = 0.35f,
        blinkIn = true,
        poseFn = { t ->
            val p = clamp(t / 0.45f)
            val pop = 1f + (NOTIF_POP - 1f) * sin(p * PI.toFloat()) * (1f - p * 0.35f)
            val r = NOTIF_R * if (p < 1f) pop else 1f
            val a = NOTIF_ANGLE * PI.toFloat() / 180f
            basePose(
                gaze = HeadGaze(yaw = -21.94f, pitch = -5.82f, roll = -12.2f),
                split = 18.89f,
                w0 = 0.505f, h0 = 0.498f,
                w1 = 0.505f, h1 = 0.498f,
                notif = NotifSpec(x = cos(a) * NOTIF_DIST, y = sin(a) * NOTIF_DIST, r = r)
            )
        }
    )

    /**
     * Alias of [Thinking] with same pulsing dots animation.
     * Use for background work that isn't user-facing "thinking".
     */
    public object Working : BotState(
        name = "working",
        morphSeconds = 0.28f,
        posePeriodMs = 2400L,
        blinkIn = true,
        poseFn = ::dotsPose
    )

    /**
     * Companion reads aloud: eyes lowered toward the book, soft bob at speaking
     * cadence, relaxed half-lowered capsules. Entry masked by a blink.
     */
    public object Narrating : BotState(
        name = "narrating",
        morphSeconds = 0.32f,
        posePeriodMs = 1100L,
        blinkIn = true,
        poseFn = { t ->
            basePose(
                sil = circle(1f, cy = sin(t * (TAU / 1.1f)) * 0.010f),
                gaze = HeadGaze(yaw = 0f, pitch = -16f, roll = -8f),
                w0 = 0.21f, h0 = 0.34f,
                w1 = 0.21f, h1 = 0.34f
            )
        }
    )

    /**
     * Mirrored droopy tilts, soft breathing with a slow sigh rhythm.
     */
    public object Sad : BotState(
        name = "sad",
        morphSeconds = 0.35f,
        posePeriodMs = 3200L,
        blinkIn = false,
        poseFn = { t ->
            val sigh = sin(t * (TAU / 3.2f)) * 0.006f
            basePose(
                sil = circle(1f, sy = 0.96f, cy = sigh),
                gaze = HeadGaze(yaw = -4f, pitch = -18f, roll = -18f),
                split = 14f,
                w0 = 0.236f, h0 = 0.36f, t0 = 12f,
                w1 = 0.236f, h1 = 0.36f, t1 = -12f
            )
        }
    )

    /**
     * Round shining eyes and a giddy sway.
     */
    public object Love : BotState(
        name = "love",
        morphSeconds = 0.28f,
        posePeriodMs = 1400L,
        blinkIn = false,
        poseFn = { t ->
            basePose(
                sil = circle(1f, cx = sin(t * (TAU / 1.4f)) * 0.008f),
                gaze = HeadGaze(yaw = 0f, pitch = 10f, roll = -10f + sin(t * (TAU / 1.4f)) * 5f),
                split = 17f,
                w0 = 0.26f, h0 = 0.26f,
                w1 = 0.26f, h1 = 0.26f
            )
        }
    )

    /**
     * Crossed mismatched tilts: pure goofball.
     * Entry masked by a blink.
     */
    public object Silly : BotState(
        name = "silly",
        morphSeconds = 0.3f,
        blinkIn = true,
        poseFn = { _ ->
            basePose(
                gaze = HeadGaze(yaw = 6f, pitch = -4f, roll = 14f),
                split = 13f,
                w0 = 0.30f, h0 = 0.24f, t0 = 20f,
                w1 = 0.20f, h1 = 0.40f, t1 = -22f
            )
        }
    )

    /**
     * Egg-shaped body with measured face, straightened to face the viewer.
     * Entry masked by a blink.
     */
    public object Egg : BotState(
        name = "egg",
        morphSeconds = 0.4f,
        blinkIn = true,
        poseFn = { _ ->
            basePose(
                sil = Silhouette(EGG_RADII),
                gaze = HeadGaze(yaw = 8f, pitch = 20f, roll = -14f),
                split = 12f,
                w0 = 0.164f, h0 = 0.385f,
                w1 = 0.164f, h1 = 0.385f
            )
        }
    )

    /**
     * Hexagonal body with rounded corners.
     * Entry masked by a blink.
     */
    public object Hexagon : BotState(
        name = "hexagon",
        morphSeconds = 0.4f,
        blinkIn = true,
        poseFn = { _ ->
            basePose(
                sil = Silhouette(HEX_RADII),
                gaze = HeadGaze(yaw = 9f, pitch = 18f, roll = -12f),
                split = 13f,
                w0 = 0.177f, h0 = 0.411f,
                w1 = 0.177f, h1 = 0.411f
            )
        }
    )

    // --- bloub's playful shape morphs ---

    /**
     * Tumbling rounded "play" triangle with orbiting center.
     * Entry masked by a blink.
     */
    public object Play : BotState(
        name = "play",
        morphSeconds = 0.5f,
        posePeriodMs = 3000L,
        blinkIn = true,
        poseFn = { t ->
            val rot = sin(t * (TAU / 3f)) * 0.35f
            basePose(
                sil = Silhouette(
                    TRIANGLE_RADII,
                    rot = rot,
                    cx = -TRI_ORBIT * sin(rot),
                    cy = TRI_ORBIT * cos(rot)
                ),
                gaze = HeadGaze(yaw = 12f, pitch = -8f, roll = -6f),
                split = 15f,
                w0 = 0.18f, h0 = 0.34f,
                w1 = 0.18f, h1 = 0.34f
            )
        }
    )

    /**
     * Collapse to a pebble while sparkles spiral in and get eaten, then pop
     * back to the ball. Internally periodic (2.6 s).
     */
    public object Burst : BotState(
        name = "burst",
        morphSeconds = 0.4f,
        posePeriodMs = 2600L,
        blinkIn = false,
        poseFn = { t ->
            val c = t % 2.6f
            val collapse = 1f - 0.834f * Easings.easeOutQuint(clamp(c / 0.7f))
            val regrow = Easings.easeOutQuint(clamp((c - 1.7f) / 0.7f))
            val dots = BURST_SEEDS.mapNotNull { seed ->
                val u = c - seed.first
                if (u <= 0f || u >= 0.62f) return@mapNotNull null
                val rho = seed.second * 0.75f.pow(u * 10f)
                val angle = seed.third + u * 100f * PI.toFloat() / 180f
                DotSpec(
                    x = cos(angle) * rho,
                    y = sin(angle) * rho,
                    r = 0.04f + 0.028f * clamp(u / 0.55f),
                    alpha = clamp(u / 0.06f) * clamp((0.62f - u) / 0.08f)
                )
            }
            basePose(
                sil = circle(collapse + (1f - collapse) * regrow),
                eyeAlpha = clamp((c - 1.85f) / 0.4f),
                dots = dots
            )
        }
    )

    /**
     * Shrink to a comet dot with a gentle wobble and a tiny orbiting trail.
     */
    public object Comet : BotState(
        name = "comet",
        morphSeconds = 0.45f,
        posePeriodMs = 2400L,
        blinkIn = false,
        poseFn = { t ->
            val c = t % 2.4f
            val collapse =
                1f - (1f - COMET_DOT) * Easings.easeOutQuint(clamp(c / 0.55f))
            val regrow = Easings.easeOutQuint(clamp((c - 1.85f) / 0.6f))
            val ta = c * (TAU / 1.2f)
            basePose(
                sil = circle(
                    collapse + (1f - collapse) * regrow,
                    cy = sin(clamp(c / 1.7f) * PI.toFloat()) * 0.035f
                ),
                eyeAlpha = clamp((c - 2f) / 0.35f),
                dots = listOf(
                    DotSpec(
                        x = cos(ta) * 0.5f,
                        y = sin(ta) * 0.13f,
                        r = 0.05f,
                        alpha = clamp((c - 0.2f) / 0.3f) * clamp((2.1f - c) / 0.3f)
                    )
                )
            )
        }
    )

    /**
     * Triangle lets go: one fast tumble out while concentric rings bloom,
     * then a long settle back into the resting ball. Internally periodic (3.4 s).
     */
    public object Orbit : BotState(
        name = "orbit",
        morphSeconds = 0.6f,
        posePeriodMs = 3400L,
        blinkIn = false,
        poseFn = { t ->
            val rot = -TAU * 1.25f * t * Easings.easeInOutCubic(clamp(t / 0.35f))
            val back = Easings.easeInOutCubic(clamp((t - 1.6f) / 0.9f))
            val tri = Silhouette(
                TRIANGLE_RADII,
                rot = rot,
                cx = -TRI_ORBIT * sin(rot),
                cy = TRI_ORBIT * cos(rot)
            )
            val rings = ORBIT_RINGS.mapIndexed { i, ring ->
                ring.copy(
                    alpha = clamp(t / 0.8f) * clamp((3.6f - t) / 0.9f) *
                        clamp((t - i * 0.13f) / 0.3f)
                )
            }
            basePose(
                sil = blend(tri, circle(1f, rot = rot), back),
                gaze = HeadGaze(
                    yaw = sin(t * 6.5f) * 65f * (1f - back),
                    pitch = -4f + 32f * back,
                    roll = -13f
                ),
                w0 = 0.18f, h0 = 0.34f + 0.07f * back,
                w1 = 0.18f, h1 = 0.34f + 0.07f * back,
                arcs = rings
            )
        }
    )

    /**
     * Same ring vocabulary as [Orbit], compressed to a one-second flourish.
     */
    public object Swirl : BotState(
        name = "swirl",
        morphSeconds = 0.35f,
        posePeriodMs = 1000L,
        blinkIn = false,
        poseFn = { t ->
            val rings = ORBIT_RINGS.take(2).mapIndexed { i, ring ->
                ring.copy(
                    alpha = clamp(t / 0.8f) * clamp((1f - t) / 0.9f) *
                        clamp((t - i * 0.13f) / 0.3f)
                )
            }
            basePose(arcs = rings)
        }
    )

    /**
     * Genuine two-eyed warm smile: both eyes closed as upward-arched dashes
     * (the mirrored chevron of which [Happy] shows only half — Happy winks),
     * body lifted slightly off its rest line, breathing up instead of sagging.
     * The plain "good news" face of the vocabulary.
     */
    public object Smile : BotState(
        name = "smile",
        morphSeconds = 0.26f,
        posePeriodMs = 3200L,
        blinkIn = true,
        poseFn = { t ->
            val lift = sin(t * (TAU / 3.2f))
            basePose(
                sil = circle(
                    1f,
                    cy = -0.008f - 0.005f * lift,
                    sy = 1.012f
                ),
                gaze = HeadGaze(yaw = 0f, pitch = 10f, roll = -6f),
                split = 16f,
                w0 = 0.40f, h0 = 0.09f, t0 = 16f,
                w1 = 0.40f, h1 = 0.09f, t1 = -16f
            )
        }
    )

    /**
     * Friendly hello: the ball leans in with a gentle side sway and does
     * small bouncy hops while a flick of dots travels along an arc off the
     * upper-right shoulder — reads as a waving hand. Bright open eyes pitched
     * up at the viewer.
     */
    public object Greet : BotState(
        name = "greet",
        morphSeconds = 0.34f,
        posePeriodMs = 2600L,
        blinkIn = true,
        poseFn = { t ->
            val hop = kotlin.math.abs(kotlin.math.sin(t * (TAU / 1.3f)))
            val lean = sin(t * (TAU / 2.6f))
            // Three staggered specks sweeping up the shoulder arc = the wave.
            val wave = (0..2).map { i ->
                val p = (t / 1.3f + i * 0.16f) % 1f
                val env = kotlin.math.sin(p * PI.toFloat())
                val ang = (-25f - 55f * p) * PI.toFloat() / 180f
                DotSpec(
                    x = cos(ang) * 1.06f,
                    y = sin(ang) * 1.06f,
                    r = 0.045f + 0.018f * env,
                    alpha = env * 0.85f
                )
            }
            basePose(
                sil = circle(
                    1f + 0.012f * hop,
                    cx = 0.012f * lean,
                    cy = -0.05f * hop,
                    rot = 0.07f * lean
                ),
                gaze = HeadGaze(yaw = -8f, pitch = 12f, roll = -10f + lean * 30f),
                split = 16f,
                w0 = 0.22f, h0 = 0.47f,
                w1 = 0.22f, h1 = 0.47f,
                dots = wave
            )
        }
    )

    /**
     * Reading: eyes lowered toward content, tracking left-to-right in slow
     * sweeps with a quick soft return (the line wrap), plus a tiny nod on the
     * return. The signature state for a reading app.
     */
    public object Reading : BotState(
        name = "reading",
        morphSeconds = 0.32f,
        posePeriodMs = 2600L,
        blinkIn = true,
        poseFn = { t ->
            val amp = 15f
            val p = (t % 2.6f) / 2.6f
            val sweep: Float
            val dip: Float
            if (p < 0.76f) {
                // slow eased traverse of the line
                sweep = -amp + 2f * amp * smoothStep(p / 0.76f)
                dip = 0f
            } else {
                // soft return to the left edge, head nods along
                val u = (p - 0.76f) / 0.24f
                sweep = amp - 2f * amp * Easings.easeInOutCubic(u)
                dip = kotlin.math.sin(u * PI.toFloat()) * 4f
            }
            basePose(
                gaze = HeadGaze(yaw = sweep, pitch = -20f - dip, roll = -4f),
                split = 15f,
                w0 = 0.20f, h0 = 0.30f, t0 = 6f,
                w1 = 0.20f, h1 = 0.30f, t1 = -6f
            )
        }
    )

    /**
     * Proud: chest-up settle after finishing something long. A slow one-shot
     * rise out of a small sunken start (the exhale), then it holds there,
     * breathing; eyes are wide-set, squat and warm — contented half-lids.
     */
    public object Proud : BotState(
        name = "proud",
        morphSeconds = 0.6f,
        posePeriodMs = 3600L,
        blinkIn = true,
        poseFn = { t ->
            val rise = Easings.easeOutCubic(clamp(t / 1.2f))
            val breathe = sin(t * (TAU / 3.6f))
            basePose(
                sil = circle(
                    0.97f + 0.03f * rise,
                    sy = 0.985f + 0.02f * rise + 0.008f * breathe,
                    cy = 0.012f - 0.03f * rise + 0.005f * breathe
                ),
                gaze = HeadGaze(yaw = 0f, pitch = 4f + 8f * rise, roll = -6f),
                split = 15f,
                w0 = 0.27f, h0 = 0.24f,
                w1 = 0.27f, h1 = 0.24f
            )
        }
    )

    companion object {
        // Lazy so the registry never builds during the sealed class's own
        // initialization window.
        /** All available [BotState] objects in a fixed order. */
        public val all: List<BotState> by lazy {
            listOf(
                Idle, Listening, Thinking, Responding, Alert, Happy, Confused,
                Sleepy, Notify, Working, Narrating, Sad, Love, Silly, Egg,
                Hexagon, Play, Burst, Comet, Exclaim, Orbit, Swirl,
                Smile, Greet, Reading, Proud
            )
        }

        /**
         * Finds a [BotState] by name (case-insensitive).
         * Returns [Idle] if not found.
         */
        public fun fromName(name: String): BotState =
            all.find { it.name.equals(name, ignoreCase = true) } ?: Idle
    }
}

/** Notification badge constants, measured (bloub decor.ts). */

/** Badge fill color (bloub blue). */
public const val NOTIF_BLUE: Long = 0xFF2496E8

/** Badge anchor angle around the ball, degrees (screen coords, Y down). */
public const val NOTIF_ANGLE = -42f

/** Badge anchor distance from center, in ball-radius units. */
public const val NOTIF_DIST = 1.003f

/** Badge radius at rest, in ball-radius units. */
public const val NOTIF_R = 0.15f

/** Entry overshoot multiplier of the badge pop (~+9% at peak). */
public const val NOTIF_POP = 1.09f