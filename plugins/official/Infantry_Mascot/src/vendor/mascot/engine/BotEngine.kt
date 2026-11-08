/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2

/**
 * Clock-free engine: [sample] is a pure function of time.
 *
 * Practical consequence: pause, resume, slow motion and jumping to an
 * arbitrary date give exactly the same image. Ported from bloub's engine.ts,
 * which was measured frame by frame off the x.ai reference video.
 *
 * This is the core rendering engine. Most users will not need to use this
 * directly — prefer [MaterialBot] from the UI package. This class is public
 * for advanced use cases like custom renderers, testing, or headless frame
 * generation.
 *
 * @param initial The initial [BotState] to start in.
 * @param initialTimeMs Starting time in milliseconds since epoch. Defaults to 0.
 */
public class BotEngine(
    initial: BotState,
    initialTimeMs: Long = 0L
) {
    /** Current state (read-only). */
    public var cur: BotState = initial
        private set
    private var prev: BotState? = null

    /**
     * Frozen starting pose, set only when a state change arrives while a fade
     * is already in flight. Keeps chained transitions continuous.
     */
    private var frozenDeparture: Pose? = null

    private var tCur = initialTimeMs / 1000.0
    private var tPrev = tCur
    private var blinkAt = -10.0

    /** Ball radius as a fraction of the canvas half-side (bloub repere.ts: 100/158). */
    companion object {
        const val RADIUS_FRACTION = 100f / 158f
        private const val FORCED_BLINK_DUR = 0.2
    }

    /** Current state of the engine. */
    public val state: BotState get() = cur

    /**
     * State change. Only ONE slot of history is kept; a change arriving during
     * a fade freezes the current composite pose and blends from it.
     *
     * @param id The new [BotState] to transition to.
     * @param nowMs Current time in milliseconds since epoch.
     */
    public fun setState(id: BotState, nowMs: Long) {
        if (id == cur) return
        val now = nowMs / 1000.0
        val morph = cur.morphSeconds.toDouble()
        val midFade = prev != null && now - tCur < morph
        frozenDeparture = if (midFade) poseComposite(now) else null
        prev = cur
        tPrev = tCur
        cur = id
        tCur = now
        // In the video every shape change is masked by a blink.
        if (id.blinkIn) blinkAt = now
    }

    /** Origin of the current fade. */
    private fun origin(now: Double): Pose? {
        frozenDeparture?.let { return it }
        return prev?.let { it.poseFn((now - tPrev).coerceAtLeast(0.0).toFloat()) }
    }

    private fun poseComposite(now: Double): Pose {
        val since = now - tCur
        val pose = cur.poseFn(since.coerceAtLeast(0.0).toFloat())
        if (since >= cur.morphSeconds) return pose
        val originPose = origin(now) ?: return pose
        return blendPose(originPose, pose, Easings.easeSettle((since / cur.morphSeconds).toFloat()))
    }

    /**
     * Samples a single frame of the bot at the given time.
     *
     * This is a pure function: same inputs always produce the same [BotFrame].
     * No internal state is mutated — all animation state derives from [nowMs].
     *
     * @param nowMs Current time in milliseconds since epoch.
     * @param scalePx The canvas side in pixels. The ball radius is derived as
     *                `scalePx * RADIUS_FRACTION` — every caller passes the full
     *                canvas size, and the rendered bot is calibrated to that.
     * @param reducedMotion If true, disables decorative motion (gaze drift, breathing, positional float).
     * @param energy Liveliness multiplier (1.0 = reference video calm, higher = more active).
     * @param eyeStyle Eye shape personality transform.
     * @return A [BotFrame] containing all geometry needed to render the bot.
     */
    public fun sample(
        nowMs: Long,
        scalePx: Float,
        reducedMotion: Boolean = false,
        energy: Float = 1f,
        eyeStyle: EyeStyle = EyeStyle.CLASSIC
    ): BotFrame {
        val now = nowMs / 1000.0
        // All geometry is authored in ball-radius units; this maps unit -> px.
        val r = scalePx * RADIUS_FRACTION

        var pose = cur.poseFn((now - tCur).coerceAtLeast(0.0).toFloat())

        // --- transition ------------------------------------------------------
        val since = now - tCur
        if (since < cur.morphSeconds) {
            origin(now)?.let { o ->
                // Ease-out measured on the video: no body overshoot.
                // Settle curve: anticipation dip, then a soft overshoot-and-
                // settle instead of the flat measured slide.
                val ratio = Easings.easeSettle((since / cur.morphSeconds).toFloat())
                pose = blendPose(o, pose, ratio)
            }
        }

        // --- life at rest ----------------------------------------------------
        val alive = pose.eyeAlpha > 0.01f
        val life = liveliness(
            t = now.toFloat(),
            wander = if (alive && !reducedMotion) 1f else 0f,
            blink = alive,
            float = !reducedMotion,
            energy = energy
        )

        // Every state is authored as a departure from rest, so turning the
        // expression up is one operation: push that departure further. Applied
        // after the transition blend, so morphs stay smooth and only the
        // destination gets louder.
        val gaze = HeadGaze(
            yaw = louder(Face.REST_GAZE.yaw, pose.gaze.yaw) + life.dYaw,
            pitch = louder(Face.REST_GAZE.pitch, pose.gaze.pitch) + life.dPitch,
            roll = louder(Face.REST_GAZE.roll, pose.gaze.roll) + life.dRoll
        )

        // Blink triggered by the state change itself, on top of the calendar.
        val forced = clamp(((now - blinkAt) / FORCED_BLINK_DUR).toFloat())
        val forcedLid = if (forced < 1f) kotlin.math.abs(forced * 2f - 1f) else 1f
        val lid = minOf(life.lid, forcedLid)

        val offX = life.driftX
        val offY = life.driftY

        // --- body ------------------------------------------------------------
        val sil = Silhouette(
            radii = pose.sil.radii,
            rot = pose.sil.rot,
            cx = pose.sil.cx + offX,
            cy = pose.sil.cy + offY,
            // Squash and stretch carry as much of the mood as the face does.
            sx = louder(1f, pose.sil.sx),
            sy = louder(1f, pose.sil.sy) * life.breath
        )
        val bodyPoints = ArrayList<Offset>(PROFILE_SAMPLES)
        toPoints(sil, r, bodyPoints)

        // --- eyes ------------------------------------------------------------
        // The eyes live on a sphere of radius 1; whenever the silhouette is not
        // a circle they are re-anchored proportionally to the local radius.
        val eyes = ArrayList<RenderedEye>(2)
        if (pose.eyeAlpha > 0.01f) {
            val poses = eyePoses(gaze, r, louder(Face.EYE_SPLIT, pose.split))
            for (i in 0..1) {
                val e = if (i == 0) poses.first else poses.second
                if (e.depth <= 0.02f) continue
                val cfg = louder(if (i == 0) pose.eye0 else pose.eye1)
                val fit = radiusAtAngle(pose.sil.radii, atan2(e.y, e.x) - pose.sil.rot)
                // Own eye tilt: tangent frame composed with an in-plane rotation
                // (Basis x Rot), enabling mirrored tilts between both eyes.
                val phi = cfg.tilt * kotlin.math.PI.toFloat() / 180f
                val cp = kotlin.math.cos(phi)
                val sp = kotlin.math.sin(phi)
                val ax = e.a * cp + e.c * sp
                val ay = e.b * cp + e.d * sp
                val cx2 = -e.a * sp + e.c * cp
                val cy2 = -e.b * sp + e.d * cp
                // The blink applies AFTER all that: a vertical screen-space
                // squash, not along the capsule axis.
                val k = blinkScale(minOf(lid, cfg.open))
                // The eye style reshapes the pose's own dimensions, keeping
                // each expression's relative character (squints stay squints).
                val (styledW, styledH) = eyeStyle.apply(cfg.w, cfg.h)
                eyes.add(
                    RenderedEye(
                        tx = e.x * fit + offX * r,
                        ty = e.y * fit + offY * r,
                        a = ax, b = ay, c = cx2, d = cy2,
                        blinkK = k,
                        wPx = styledW * r,
                        hPx = styledH * r,
                        alpha = pose.eyeAlpha * clamp(e.depth / 0.12f)
                    )
                )
            }
        }

        // --- decor -----------------------------------------------------------
        val dots = pose.dots
            .filter { it.alpha > 0.01f && it.r > 0.0005f }
            .map { it.copy(x = (it.x + offX) * r, y = (it.y + offY) * r, r = it.r * r) }

        val arcs = pose.arcs
            .filter { it.alpha > 0.01f }
            .map { it.copy(x = (it.x + offX) * r, y = (it.y + offY) * r) }

        val notif = pose.notif?.let {
            val nFit = radiusAtAngle(pose.sil.radii, atan2(it.y, it.x) - pose.sil.rot)
            NotifBadge(x = (it.x * nFit + offX) * r, y = (it.y * nFit + offY) * r, rPx = it.r * r)
        }

        return BotFrame(
            bodyPoints = bodyPoints,
            eyes = eyes,
            dots = dots,
            arcs = arcs,
            notif = notif,
            state = cur,
            ballness = ballness(pose.sil.radii)
        )
    }
}

/**
 * Pushes a posed value further from its resting value: the whole expression
 * knob, in one line.
 *
 * The states were measured off a reference video that plays everything very
 * cool. That reads as flat on a phone, where the bot is often 42dp in a corner
 * — so every departure from rest is amplified, and rest itself never moves.
 */
private fun louder(rest: Float, posed: Float, gain: Float = EXPRESSION_GAIN): Float =
    rest + (posed - rest) * gain

/** [louder] for a whole eye. Dimensions keep a floor so a squint stays a
 *  squint instead of inverting into a line. */
private fun louder(cfg: EyeCfg): EyeCfg = EyeCfg(
    w = louder(Face.EYE_W, cfg.w).coerceAtLeast(Face.EYE_W * 0.15f),
    h = louder(Face.EYE_H, cfg.h).coerceAtLeast(Face.EYE_H * 0.06f),
    open = louder(1f, cfg.open).coerceIn(0f, 1f),
    // Tilt is the loudest signal a capsule eye has, and the cheapest to read
    // at small sizes, so it gets pushed hardest.
    tilt = louder(0f, cfg.tilt, EXPRESSION_GAIN * 1.25f)
)

private const val EXPRESSION_GAIN = 1.4f

/**
 * 1 while the profile is a circle of about the resting size, falling to 0 as it
 * deforms into anything else. Squash, stretch and breathing are the ball being
 * alive, so they are deliberately not counted — only the radial profile is.
 */
private fun ballness(radii: FloatArray): Float {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    var sum = 0f
    radii.forEach {
        if (it < lo) lo = it
        if (it > hi) hi = it
        sum += it
    }
    val mean = sum / radii.size
    if (mean < 1e-4f) return 0f
    // A ball reads as a ball while its profile stays within a few percent of
    // round and near full size; past that the ears are gone well before the
    // shape is recognisably something else.
    val round = 1f - ((hi - lo) / mean) / BALL_ROUND_TOLERANCE
    val full = 1f - (1f - mean.coerceAtMost(1f)) / BALL_SIZE_TOLERANCE
    return minOf(round, full).coerceIn(0f, 1f)
}

private const val BALL_ROUND_TOLERANCE = 0.16f
private const val BALL_SIZE_TOLERANCE = 0.35f

/**
 * Pure data: what a bot looks like at one instant, in canvas pixels around (0,0).
 *
 * This is the output of [BotEngine.sample] and contains all geometry needed to
 * render the bot. The coordinate system is centered at (0,0) with Y down.
 */
public data class BotFrame(
    /** Body silhouette points (64 points forming a closed loop). */
    public val bodyPoints: List<Offset>,
    /** Rendered eyes, ready to draw. */
    public val eyes: List<RenderedEye>,
    /** Decorative dots (pulsing dots, notification badge dot, etc.). */
    public val dots: List<DotSpec>,
    /** Decorative arcs/rings. */
    public val arcs: List<ArcSpec> = emptyList(),
    /** Notification badge, if any. */
    public val notif: NotifBadge?,
    /** The [BotState] this frame represents. */
    public val state: BotState,
    /**
     * How much this frame is still the resting ball, 1 to 0.
     *
     * Cat ears belong on a head, not on a speech bubble or a loading dot, and
     * a morph is continuous — so this is a fade, not a flag: the ears retract
     * into the silhouette as it stops being a ball and grow back as it returns.
     */
    public val ballness: Float = 1f
)

/**
 * One eye ready to draw: affine matrix columns (a,b)/(c,d) plus vertical blink
 * squash [blinkK], applied exactly like bloub's SVG matrix(a, b*k, c, d*k, tx, ty).
 */
public data class RenderedEye(
    /** X translation in pixels. */
    public val tx: Float,
    /** Y translation in pixels. */
    public val ty: Float,
    /** Matrix column 0, row 0 (X scale / shear). */
    public val a: Float,
    /** Matrix column 0, row 1 (Y shear). */
    public val b: Float,
    /** Matrix column 1, row 0 (X shear). */
    public val c: Float,
    /** Matrix column 1, row 1 (Y scale / shear). */
    public val d: Float,
    /** Vertical blink squash factor (1 = open, ~0.06 = closed). */
    public val blinkK: Float,
    /** Eye width in pixels. */
    public val wPx: Float,
    /** Eye height in pixels. */
    public val hPx: Float,
    /** Alpha (0..1). */
    public val alpha: Float
)

/** Notification badge in px. */
public data class NotifBadge(
    /** X position in pixels. */
    public val x: Float,
    /** Y position in pixels. */
    public val y: Float,
    /** Radius in pixels. */
    public val rPx: Float
)

/** Interpolation of two poses; decor cross-fades in opacity, not geometry. */
private fun blendPose(a: Pose, b: Pose, t: Float): Pose {
    val out = 1f - t
    val dots = buildList {
        addAll(a.dots.map { it.copy(alpha = it.alpha * out) })
        addAll(b.dots.map { it.copy(alpha = it.alpha * t) })
    }
    val arcs = buildList {
        addAll(a.arcs.map { it.copy(alpha = it.alpha * out) })
        addAll(b.arcs.map { it.copy(alpha = it.alpha * t) })
    }
    return Pose(
        sil = blend(a.sil, b.sil, t),
        gaze = HeadGaze(
            yaw = lerp(a.gaze.yaw, b.gaze.yaw, t),
            pitch = lerp(a.gaze.pitch, b.gaze.pitch, t),
            roll = lerp(a.gaze.roll, b.gaze.roll, t)
        ),
        split = lerp(a.split, b.split, t),
        eye0 = lerpEye(a.eye0, b.eye0, t),
        eye1 = lerpEye(a.eye1, b.eye1, t),
        eyeAlpha = lerp(a.eyeAlpha, b.eyeAlpha, t).coerceIn(0f, 1f),
        dots = dots,
        arcs = arcs,
        notif = if (t < 0.5f) a.notif else b.notif
    )
}

private fun lerpEye(a: EyeCfg, b: EyeCfg, t: Float): EyeCfg = EyeCfg(
    w = lerp(a.w, b.w, t),
    h = lerp(a.h, b.h, t),
    open = lerp(a.open, b.open, t),
    tilt = lerp(a.tilt, b.tilt, t)
)