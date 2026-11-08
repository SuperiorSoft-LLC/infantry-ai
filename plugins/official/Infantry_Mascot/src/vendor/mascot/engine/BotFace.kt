/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

/**
 * The eyes are painted on a sphere, not laid flat.
 *
 * Measured off the reference video: the eye nearest the edge is 0.69x the
 * width of the other and its area 0.663x — exactly the depth factor
 * (z = 0.669) of a point on a sphere at that distance from center. We model a
 * real head orientation: each eye gets the tangent frame of the sphere,
 * projected orthographically. Compression and tilt fall out of it.
 *
 * Constants below are fitted on frame-by-frame measurements (residual ~1 px
 * for a radius of 190 px), not hand-picked.
 *
 * This is an internal object used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal object Face {
    /** Half-separation of the eyes on the sphere, degrees (~31 deg total). */
    internal const val EYE_SPLIT = 15.46f

    /** Resting eye size, in ball-radius units. */
    internal const val EYE_W = 0.186f
    internal const val EYE_H = 0.412f

    /** Resting head orientation, fitted on reference frames. */
    internal val REST_GAZE = HeadGaze(yaw = 28.49f, pitch = 28.62f, roll = -13f)
}

/**
 * Head orientation in degrees.
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal data class HeadGaze(
    /** yaw, degrees, positive = looks right */
    public val yaw: Float,
    /** pitch, degrees, positive = looks up */
    public val pitch: Float,
    /** roll, degrees, head tilt */
    public val roll: Float
)

/**
 * Local eye config in ball-radius units; [open] 1 = open, 0 = closed.
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal data class EyeCfg(
    public val w: Float,
    public val h: Float,
    public val open: Float = 1f,
    /**
     * Own tilt of the capsule, degrees, positive = top leans right (screen
     * coords). Applied AFTER the tangent frame of the sphere.
     */
    public val tilt: Float = 0f
)

/**
 * One eye's placement: center + tangent 2x2 matrix columns + depth.
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal data class EyePose(
    public val x: Float,
    public val y: Float,
    public val a: Float,
    public val b: Float,
    public val c: Float,
    public val d: Float,
    /** z component of the normal: > 0 = facing visible */
    public val depth: Float
)

private fun deg(d: Float): Float = d * (kotlin.math.PI.toFloat() / 180f)

/** Rotates two vectors of an orthonormal frame within their common plane. */
private fun spin(u: FloatArray, v: FloatArray, angle: Float): Pair<FloatArray, FloatArray> {
    val c = kotlin.math.cos(angle)
    val s = kotlin.math.sin(angle)
    return floatArrayOf(
        u[0] * c + v[0] * s,
        u[1] * c + v[1] * s,
        u[2] * c + v[2] * s
    ) to floatArrayOf(
        v[0] * c - u[0] * s,
        v[1] * c - u[1] * s,
        v[2] * c - u[2] * s
    )
}

/**
 * Head then eye frames.
 * Screen frame: x right, y down, z toward the viewer.
 * Index 0 is the inner eye, index 1 the outer one.
 *
 * This is an internal function used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal fun eyePoses(gaze: HeadGaze, scale: Float, split: Float = Face.EYE_SPLIT): Pair<EyePose, EyePose> {
    var f = floatArrayOf(0f, 0f, 1f)
    var right = floatArrayOf(1f, 0f, 0f)
    var down = floatArrayOf(0f, 1f, 0f)

    // yaw: forward tips toward right
    val fr = spin(f, right, deg(gaze.yaw))
    f = fr.first; right = fr.second
    // pitch: forward tips up (against down)
    val df = spin(down, f, deg(gaze.pitch))
    down = df.first; f = df.second
    // roll: the head tilts within its own plane
    val rd = spin(right, down, deg(gaze.roll))
    right = rd.first; down = rd.second

    fun build(side: Int): EyePose {
        val ef = spin(f, right, deg(split * side))
        val efr = ef.first
        val er = ef.second
        return EyePose(
            x = efr[0] * scale,
            y = efr[1] * scale,
            a = er[0],
            b = er[1],
            c = down[0],
            d = down[1],
            depth = efr[2]
        )
    }

    return build(-1) to build(1)
}

/**
 * Life at rest: slow gaze drift, saccades, blinks.
 *
 * Pure function of time (no internal state), so pause, resume and jumping to
 * an arbitrary date always give the same image. Values are DELTAs added to the
 * current state's pose.
 *
 * This is an internal data class used by [BotEngine]. Most users will not
 * interact with this directly.
 */
internal data class Liveliness(
    public val dYaw: Float,
    public val dPitch: Float,
    public val dRoll: Float,
    /** 1 = eye open, 0 = closed (vertical screen-space squash) */
    public val lid: Float,
    public val driftX: Float,
    public val driftY: Float,
    public val breath: Float
)

private val BLINK_SCHEDULE: Pair<FloatArray, FloatArray> = run {
    val rng = Mulberry32(0x5eed)
    val starts = ArrayList<Float>()
    val durs = ArrayList<Float>()
    var t = 1.4f
    while (t < 900f) {
        starts.add(t)
        durs.add(0.15f + rng() * 0.07f)
        // 1.9 to 4.6 s between blinks, sometimes a double blink
        t += 1.9f + rng() * 2.7f
        if (rng() < 0.24f) {
            starts.add(t)
            durs.add(0.12f + rng() * 0.05f)
            t += 0.24f
        }
    }
    starts.toFloatArray() to durs.toFloatArray()
}

/** Pre-drawn blink calendar: deterministic and stateless. */
internal val BLINKS: FloatArray = BLINK_SCHEDULE.first

/** Per-blink close duration (s): no two blinks share an exact length. */
internal val BLINK_DURS: FloatArray = BLINK_SCHEDULE.second

/** Measured: 1 to 2 frames at 10 fps; kept as the reference duration. */
internal const val BLINK_DUR = 0.18f

internal fun blinkLid(t: Float): Float {
    for (i in BLINKS.indices) {
        val start = BLINKS[i]
        if (t < start) break
        val k = (t - start) / BLINK_DURS[i]
        if (k >= 0f && k <= 1f) {
            // fast close, slightly slower reopen
            return if (k < 0.45f) 1f - k / 0.45f else (k - 0.45f) / 0.55f
        }
    }
    return 1f
}

/**
 * Saccades: real eyes never drift smoothly, they HOLD a fixation then JUMP to
 * the next one in a few hundredths of a second (Disney's "moving hold", and
 * the reason game idles feel alive where pure noise feels drugged). Layered on
 * top of the measured drift: the drift keeps the organic wobble, the saccade
 * layer adds the fixation jumps.
 *
 * Pre-drawn calendar, deterministic like [BLINKS]: each entry is
 * `[startTimeSec, yawTargetDeg, pitchTargetDeg]`. The transition to a new
 * target takes [SACCADE_ATTACK]; between attacks the value is HELD flat.
 */
private const val SACCADE_ATTACK = 0.07f

private val SACCADES: FloatArray = run {
    val rng = Mulberry32(0x5accad)
    val out = ArrayList<Float>()
    var t = 0.9f
    while (t < 3600f) {
        // one jump every 0.7 to 3.1 s, occasionally clustered (reading rhythm)
        out.addAll(listOf(t, rng() * 7f - 3.5f, rng() * 4f - 2f))
        t += 0.7f + rng() * 2.4f
    }
    out.toFloatArray()
}

/** Smooth 0->1 ramp with zero-slope ends; the cheapest non-jarring attack. */
private fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Current saccade offset (yaw, pitch) in degrees, held flat between jumps. */
internal fun saccadeOffset(t: Float): Pair<Float, Float> {
    var prevY = 0f
    var prevP = 0f
    var i = 0
    while (i + 2 < SACCADES.size && SACCADES[i] <= t) {
        // next event's start; MAX_VALUE after the last one (calendar exhausted)
        val nextStart = if (i + 3 < SACCADES.size) SACCADES[i + 3] else Float.MAX_VALUE
        if (t < nextStart) {
            // inside this fixation: attack from the previous target, then hold
            val k = smooth((t - SACCADES[i]) / SACCADE_ATTACK)
            return (prevY + (SACCADES[i + 1] - prevY) * k) to
                (prevP + (SACCADES[i + 2] - prevP) * k)
        }
        prevY = SACCADES[i + 1]
        prevP = SACCADES[i + 2]
        i += 3
    }
    return prevY to prevP
}

/**
 * Curious glances: rare, larger excursions — something caught the bot's
 * attention off-screen, it looks, holds, comes back. This is what makes a
 * rest pose read as ATTENDING rather than IDLING. Envelope is a symmetric
 * trapezoid (rise 0.45 s, hold, fall 0.45 s) so it lands and leaves softly.
 */
private val GLANCES: FloatArray = run {
    val rng = Mulberry32(0x61a7ce)
    val out = ArrayList<Float>()
    var t = 11f
    while (t < 3600f) {
        val yaw = (if (rng() < 0.5f) -1f else 1f) * (4.5f + rng() * 3f)
        val pitch = rng() * 10f - 5f
        val hold = 0.9f + rng() * 0.7f
        out.addAll(listOf(t, yaw, pitch, 0.45f + hold + 0.45f))
        t += 14f + rng() * 20f
    }
    out.toFloatArray()
}

/** Current glance offset (yaw, pitch) in degrees, trapezoid-enveloped. */
internal fun glanceOffset(t: Float): Pair<Float, Float> {
    var i = 0
    while (i + 3 < GLANCES.size && GLANCES[i] <= t) {
        val u = t - GLANCES[i]
        val total = GLANCES[i + 3]
        if (u <= total) {
            val env = minOf(smooth(u / 0.45f), smooth((total - u) / 0.45f))
            return (GLANCES[i + 1] * env) to (GLANCES[i + 2] * env)
        }
        i += 4
    }
    return 0f to 0f
}

/**
 * Squint moods: rare half-lidded moments (skepticism, drowsy comfort). Held
 * long enough to read as a mood, not a glitch; always combined WITH blinks,
 * never replacing them. Returns a lid MULTIPLIER in [0.35, 1].
 */
private val SQUINTS: FloatArray = run {
    val rng = Mulberry32(0x5a117)
    val out = ArrayList<Float>()
    var t = 7f
    while (t < 3600f) {
        val hold = 0.5f + rng() * 0.5f
        val depth = 0.45f + rng() * 0.12f
        out.addAll(listOf(t, 0.15f + hold + 0.15f, depth))
        t += 9f + rng() * 17f
    }
    out.toFloatArray()
}

internal fun squintLid(t: Float): Float {
    var i = 0
    while (i + 2 < SQUINTS.size && SQUINTS[i] <= t) {
        val u = t - SQUINTS[i]
        val total = SQUINTS[i + 1]
        if (u <= total) {
            val env = minOf(smooth(u / 0.15f), smooth((total - u) / 0.15f))
            return 1f + (SQUINTS[i + 2] - 1f) * env
        }
        i += 3
    }
    return 1f
}

/**
 * Micro-fidget deck: rare full-body asides (a yawn, a wander, a sneeze)
 * layered on top of gaze drift so long idle stretches never metronome.
 * Pre-drawn schedule, deterministic like [BLINKS]; kinds cycle in order.
 */
private enum class FidgetKind { YAWN, WANDER, SNEEZE }

private const val YAWN_DUR = 0.9f
private const val WANDER_DUR = 1.4f
private const val SNEEZE_DUR = 0.5f

private data class Fidget(
    val start: Float,
    val kind: FidgetKind,
    /** Wander direction, alternating so excursions go both ways. */
    val dir: Float
)

private val FIDGETS: List<Fidget> = run {
    val rng = Mulberry32(0xF1D9E7)
    val out = ArrayList<Fidget>()
    var t = 20f + rng() * 70f
    while (t < 3600f) {
        out.add(Fidget(t, FidgetKind.entries[out.size % FidgetKind.entries.size],
            if (out.size % 2 == 0) 1f else -1f))
        // one fidget every 20 to 90 s
        t += 20f + rng() * 70f
    }
    out
}

/**
 * The active micro-fidget at [ts]: (yawAdd, pitchAdd) degrees plus a lid
 * MULTIPLIER in [0.35, 1]. Zero/neutral between events.
 */
internal fun fidget(ts: Float): Triple<Float, Float, Float> {
    var current: Fidget? = null
    for (f in FIDGETS) {
        if (f.start > ts) break
        current = f
    }
    val f = current ?: return Triple(0f, 0f, 1f)
    val u = ts - f.start
    return when (f.kind) {
        FidgetKind.YAWN ->
            if (u >= YAWN_DUR) Triple(0f, 0f, 1f)
            // Slow full-lid dip and recovery: sleepy comfort, not a blink.
            else Triple(
                0f,
                0f,
                1f - 0.65f * kotlin.math.sin(u / YAWN_DUR * kotlin.math.PI.toFloat())
            )
        FidgetKind.WANDER ->
            if (u >= WANDER_DUR) Triple(0f, 0f, 1f)
            // Something off-screen caught its eye: one wide yaw excursion.
            else Triple(
                25f * kotlin.math.sin(u / WANDER_DUR * kotlin.math.PI.toFloat()) * f.dir,
                0f,
                1f
            )
        FidgetKind.SNEEZE ->
            if (u >= SNEEZE_DUR) Triple(0f, 0f, 1f)
            // Pitch nose-dive with a rebound.
            else Triple(0f, -18f * kotlin.math.sin(u / SNEEZE_DUR * kotlin.math.PI.toFloat()), 1f)
    }
}

/**
 * How much further the idle gaze wanders than the reference video's own drift.
 *
 * The eyes are already anchored on a sphere — [eyePoses] places them in 3D and
 * hands back the projected tangent frame, so they foreshorten and slide behind
 * the limb on their own. At the measured amplitude they barely leave centre,
 * which wastes that: the bot reads as a flat face with jittering dots. Sweeping
 * wider is what makes the head read as a ball being looked around.
 */
private const val GAZE_SWEEP = 1.8f

internal fun liveliness(
    t: Float,
    wander: Float = 1f,
    blink: Boolean = true,
    float: Boolean = true,
    energy: Float = 1f
): Liveliness {
    // Energy > 1 shortens every period and widens every amplitude, turning the
    // measured calm of the reference video into a cheerful, active character.
    val e = if (energy.isFinite()) energy.coerceIn(0.5f, 2.5f) else 1f
    // The ADDED behaviour layers get a gentler amplitude ramp than the drift:
    // at WILD they must spice up the idle, not park the outer eye behind the
    // limb of the sphere.
    val amp = 0.6f + 0.4f * e
    // Schedules run on the same inner clock as blinks (t * e).
    val ts = t * e
    val (sacYaw, sacPitch) = saccadeOffset(ts)
    val (glanceYaw, glancePitch) = glanceOffset(ts)
    val (fidgetYaw, fidgetPitch, fidgetLid) = fidget(ts)
    return Liveliness(
        dYaw = (loopNoise(t, 11.3f / e, 0.4f) * 5.5f + loopNoise(t, 3.7f / e, 2.1f) * 1.6f) *
            GAZE_SWEEP * wander * e + (sacYaw + glanceYaw + fidgetYaw) * wander * amp,
        dPitch = (loopNoise(t, 9.1f / e, 1.3f) * 4.2f + loopNoise(t, 4.3f / e, 0.7f) * 1.3f) *
            GAZE_SWEEP * wander * e + (sacPitch + glancePitch + fidgetPitch) * wander * amp,
        dRoll = loopNoise(t, 13.7f / e, 3.2f) * 2.2f * wander * e,
        lid = if (blink) minOf(blinkLid(ts), squintLid(ts), fidgetLid) else 1f,
        // At rest the video is nearly motionless (center stable +-0.003):
        // all the life goes through gaze and blinking.
        driftX = if (float) loopNoise(t, 7.9f / e, 1.9f) * (0.006f * e) else 0f,
        driftY = if (float) loopNoise(t, 5.3f / e, 0.3f) * (0.007f * e) else 0f,
        // Width stays constant, only height breathes. The depth is itself
        // modulated by a slow noise so inhales never metronome.
        breath =
        if (float) {
            1f + kotlin.math.sin((t / (3.4f / e)) * TAU) *
                (0.005f * e * (1f + 0.45f * loopNoise(t, 17f / e, 5.5f)))
        } else {
            1f
        }
    )
}

/**
 * The blink is a VERTICAL squash in screen space around the eye center
 * (measured: bbox width preserved, height falls to ~0.35), not a shrink along
 * the capsule axis. Applied after the tangent matrix, only affecting y outs.
 */
internal fun blinkScale(lid: Float): Float = 0.06f + 0.94f * clamp(lid)