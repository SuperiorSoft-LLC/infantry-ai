/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmaster.materialmascot.config.MascotConfig
import com.maxmaster.materialmascot.config.MascotFinish
import com.maxmaster.materialmascot.engine.BotEngine
import com.maxmaster.materialmascot.engine.BotState
import com.maxmaster.materialmascot.engine.DotSpec
import com.maxmaster.materialmascot.engine.EAR_FROM_DEG
import com.maxmaster.materialmascot.engine.EAR_PROFILE
import com.maxmaster.materialmascot.engine.EAR_STEP_DEG
import com.maxmaster.materialmascot.engine.EAR_TIP_REACH
import com.maxmaster.materialmascot.engine.NOTIF_BLUE
import com.maxmaster.materialmascot.engine.RenderedEye
import com.maxmaster.materialmascot.engine.TEAR_POINTS
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the bot mascot using Compose Canvas.
 *
 * This is the main entry point for displaying the mascot in your UI.
 * Create a [MascotConfig] with your desired settings and pass it here.
 *
 * The bot animates autonomously based on time — no manual frame advancement
 * needed. State changes are smooth morph transitions.
 *
 * @param config Host-owned immutable configuration. See [MascotConfig].
 * @param modifier Layout modifier for positioning, sizing, etc.
 * @param contentDescription Accessibility label; null omits the semantics node.
 *                           Provide a description like "Bot mascot" for accessibility.
 * @param onClick Optional host action run when the bot is tapped, in addition
 *                to its own poke reaction. Null leaves the bot decorative.
 *
 * @sample com.maxmaster.materialmascot.sample.SampleUsage.ChatWithBot
 * @sample com.maxmaster.materialmascot.sample.SampleUsage.AccessibleBot
 */
@Composable
public fun MaterialBot(
    config: MascotConfig,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    frozen: Boolean = false
) {
    if (!config.enabled) return

    val sizePx = with(LocalDensity.current) { config.size.toPx() }
    // Ears reach above the crown, so cat mode draws the whole bot smaller: the
    // eared silhouette then occupies exactly the footprint the earless one
    // does, and every host's margin and padding around `size` still holds.
    val drawPx = if (config.cat) sizePx * CAT_FIT else sizePx

    // Kept as the state OBJECT and never read with `by` at composable scope.
    // A `by` read here — or any read during composition, which is what
    // rememberUpdatedState(frameMs) used to be — subscribes this composable to
    // a value that changes every frame, so every bot on screen recomposed at
    // display refresh rate. The draw lambda and the effects below read
    // `.longValue` instead: draw-phase and effect reads invalidate only the
    // canvas' draw, which is what the sampling comment further down promises.
    //
    // Seeded from nanoTime, not currentTimeMillis: withFrameNanos reports the
    // Choreographer's nanoTime-based frame clock, so an epoch seed made the
    // very first tick jump the bot's clock ~1.7e9 seconds BACKWARDS.
    val frameMs = remember { mutableLongStateOf(if (frozen) FROZEN_FRAME_MS else 0L) }
    LaunchedEffect(frozen) {
        while (!frozen) {
            withFrameNanos { frameMs.longValue = it / 1_000_000L }
        }
    }

    val engine = remember { BotEngine(config.initialState) }

    // Poke reaction: a tap makes the bot wink at you for a moment, whatever
    // state it is in. Pure delight, no configuration needed.
    var pokes by remember { mutableIntStateOf(0) }
    var celebrating by remember { mutableStateOf(false) }
    LaunchedEffect(pokes) {
        if (pokes == 0) return@LaunchedEffect
        celebrating = true
        delay(1100)
        celebrating = false
    }
    val shownState = if (celebrating && config.state != BotState.Happy) BotState.Happy else config.state

    LaunchedEffect(shownState) {
        if (engine.state != shownState) {
            // Read inside the effect, not in composition: same current value,
            // without subscribing the composable to the per-frame ticker.
            engine.setState(shownState, frameMs.longValue)
        }
    }

    // Ears read the mood: perked and forward when alert or pleased, splayed
    // and low when sleepy or sad. Animated rather than snapped so they arrive
    // with the body morph instead of popping ahead of it.
    val earPose = earPoseFor(shownState)
    // Held as State and read in the draw lambda for the same reason as the
    // frame ticker: reading `.value` here with `by` would recompose the bot on
    // every frame of the 420ms ear tween.
    val earPerk = animateFloatAsState(
        targetValue = if (config.cat) earPose.perk else 1f,
        animationSpec = tween(durationMillis = 420),
        label = "earPerk"
    )
    val earSplay = animateFloatAsState(
        targetValue = if (config.cat) earPose.splay else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "earSplay"
    )

    val canvasModifier = if (contentDescription != null) {
        modifier
            .size(config.size)
            .semantics { this.contentDescription = contentDescription }
    } else {
        modifier.size(config.size)
    }

    // Reused across frames: allocating paths and matrices at 60 fps makes the
    // garbage collector stutter on device.
    // Every gradient is derived from the configured colors and the canvas size,
    // so it is built once per palette change — never per frame, which is what
    // drawing at display refresh rate demands.
    val chrome = remember(config.color, config.eyeColor, drawPx, config.finish) {
        if (config.finish == MascotFinish.CHROME) {
            ChromeBrushes(config.color, config.eyeColor, drawPx)
        } else null
    }

    val bodyPath = remember { Path() }
    val tearPath = remember { Path() }
    // The body outline with the ears merged into it, refilled every frame.
    val catPoints = remember { ArrayList<Offset>(CAT_SAMPLES) }
    val eyeDstPath = remember { Path() }
    val eyeMatrix = remember { Matrix() }

    Canvas(
        // The poke reaction always fires; a host action rides along with it,
        // so the bot can be a real button on screens that give it a job
        // without losing the delight of prodding it.
        modifier = canvasModifier.pointerInput(onClick) {
            detectTapGestures {
                pokes++
                onClick?.invoke()
            }
        }
    ) {
        // Sample in the draw phase: the ticker state is read here, so a new
        // frame invalidates only this canvas' draw — not recomposition.
        // Sampling during composition made every visible bot rebuild its
        // whole composable at display refresh rate.
        val now = frameMs.longValue
        val frame = engine.sample(
            nowMs = now,
            scalePx = drawPx,
            reducedMotion = !config.motion,
            energy = config.energy,
            eyeStyle = config.eyeStyle
        )
        val outline = config.finish == MascotFinish.OUTLINE
        // With no fill behind them, eyes in the configured eye color would sit
        // on the page rather than in the bot — so line art draws them in the
        // same ink as the stroke.
        val inkColor = if (outline) config.color else config.eyeColor
        translate(this.center.x, this.center.y) {
            val silhouette = if (config.cat) {
                val twitch = if (config.motion) earTwitch(now, config.energy) else 0f
                catSilhouette(
                    points = frame.bodyPoints,
                    ballness = frame.ballness,
                    perk = earPerk.value,
                    splayDeg = earSplay.value,
                    twitchDeg = twitch,
                    twitchSide = twitchSide(now),
                    out = catPoints
                )
            } else {
                frame.bodyPoints
            }
            buildBodyPath(silhouette, bodyPath)
            when {
                chrome != null -> drawChromeBody(bodyPath, chrome)
                outline -> drawOutlineBody(bodyPath, config.color, drawPx)
                else -> drawBody(bodyPath, config.color)
            }
            if (frame.arcs.isNotEmpty()) {
                val ballR = drawPx * BotEngine.RADIUS_FRACTION
                frame.arcs.forEach { arc ->
                    drawCircle(
                        color = inkColor,
                        radius = arc.r * ballR,
                        center = Offset(arc.x, arc.y),
                        alpha = arc.alpha,
                        style = Stroke(width = arc.stroke * ballR)
                    )
                }
            }
            frame.dots.forEach { drawDot(it, config.color, inkColor, drawPx, tearPath) }
            // The eyes ride the surface of a ball, so the ball's edge is where
            // they end: clipped to the silhouette, an eye that slides toward the
            // limb is cut off by the form instead of hanging over it.
            clipPath(bodyPath) {
                frame.eyes.forEach {
                    drawEye(
                        it, inkColor, eyeDstPath, eyeMatrix,
                        glow = chrome?.eyesGlow == true
                    )
                }
            }
            frame.notif?.let {
                drawCircle(
                    color = Color(NOTIF_BLUE),
                    radius = it.rPx,
                    center = Offset(it.x, it.y)
                )
            }
        }
    }
}

/**
 * The gradients behind [MascotFinish.CHROME], all expressed in the canvas'
 * centre-origin space so they can be built once and reused every frame.
 *
 * Each tone is derived from the host's body color rather than hardcoded, so a
 * mint or lavender bot gets the same lit-metal treatment as a near-black one.
 */
private class ChromeBrushes(color: Color, eyeColor: Color, sizePx: Float) {

    /**
     * Bloom is light spilling out of the eye, so it only makes sense when the
     * eye is brighter than the body. Blooming a dark eye on a pale body just
     * paints grey rings around it.
     */
    val eyesGlow: Boolean = eyeColor.luminance() > color.luminance() + 0.15f

    val ballR: Float = sizePx * BotEngine.RADIUS_FRACTION

    private val top = lerp(color, Color.White, 0.18f)
    private val bottom = lerp(color, Color.Black, 0.35f)
    private val sheen = lerp(color, Color.White, 0.55f)

    /** Lit from above, falling into shadow at the base. */
    val body: Brush = Brush.verticalGradient(
        colors = listOf(top, color, bottom),
        startY = -ballR,
        endY = ballR
    )

    /** The edge catch-light that does most of the work of reading as metal. */
    val rim: Brush = Brush.verticalGradient(
        colors = listOf(sheen.copy(alpha = 0.9f), sheen.copy(alpha = 0f)),
        startY = -ballR,
        endY = ballR * 0.25f
    )
    val rimWidth: Float = ballR * 0.022f

    /** One soft highlight, upper-left, clipped to the body. */
    val specular: Brush = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0f)),
        center = Offset(-ballR * 0.34f, -ballR * 0.46f),
        radius = ballR * 0.62f
    )
    val specularTopLeft = Offset(-ballR * 0.72f, -ballR * 0.84f)
    val specularSize = Size(ballR * 0.78f, ballR * 0.58f)

    /** Contact shadow, so the bot sits in space instead of floating on it. */
    val shadow: Brush = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = 0.26f), Color.Black.copy(alpha = 0f)),
        center = Offset(0f, ballR * 0.99f),
        radius = ballR * 0.95f
    )
    val shadowTopLeft = Offset(-ballR * 0.95f, ballR * 0.72f)
    val shadowSize = Size(ballR * 1.9f, ballR * 0.54f)
}

/** Same silhouette as [drawBody], painted with depth instead of one flat fill. */
private fun DrawScope.drawChromeBody(path: Path, chrome: ChromeBrushes) {
    drawOval(brush = chrome.shadow, topLeft = chrome.shadowTopLeft, size = chrome.shadowSize)
    drawPath(path = path, brush = chrome.body)
    drawPath(path = path, brush = chrome.rim, style = Stroke(width = chrome.rimWidth))
    clipPath(path) {
        drawOval(brush = chrome.specular, topLeft = chrome.specularTopLeft, size = chrome.specularSize)
    }
}

/** [MascotFinish.OUTLINE]: the same silhouette as a stroke, with no fill. */
private fun DrawScope.drawOutlineBody(
    path: Path,
    color: Color,
    sizePx: Float
) {
    drawPath(
        path = path,
        color = color,
        // Scaled off the bot's radius so the line keeps its weight at any size.
        style = Stroke(width = sizePx * BotEngine.RADIUS_FRACTION * 0.06f)
    )
}

private fun DrawScope.drawBody(path: Path, color: Color) {
    drawPath(path = path, color = color)
}

/** Catmull-Rom style closed spline through the sampled silhouette points. */
private fun buildBodyPath(points: List<Offset>, path: Path) {
    val n = points.size
    if (n < 3) return
    path.reset()
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]
        val p1 = points[i]
        val p2 = points[(i + 1) % n]
        val p3 = points[(i + 2) % n]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f,
            p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f,
            p2.y - (p3.y - p1.y) / 6f,
            p2.x,
            p2.y
        )
    }
    path.close()
}

private fun DrawScope.drawDot(
    dot: DotSpec,
    color: Color,
    onBodyColor: Color,
    sizePx: Float,
    path: Path
) {
    if (!dot.tear) {
        // Dots painted "on" the body belong to face-less states and take the
        // eye color so they read as features, not holes.
        drawCircle(
            color = if (dot.onBody) onBodyColor else color,
            radius = dot.r,
            center = Offset(dot.x, dot.y),
            alpha = dot.alpha
        )
        return
    }
    val ballR = sizePx * BotEngine.RADIUS_FRACTION
    val rad = dot.rotDeg * PI.toFloat() / 180f
    val cr = cos(rad)
    val sr = sin(rad)
    path.reset()
    TEAR_POINTS.forEachIndexed { i, p ->
        val rx = p.x * cr - p.y * sr
        val ry = p.x * sr + p.y * cr
        val point = Offset(dot.x + rx * ballR, dot.y + ry * ballR)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path = path, color = color, alpha = dot.alpha)
}

/**
 * How far an ear may rotate. With the body painted over the bases the join
 * can no longer open, so this is a pose-sanity bound now: beyond it the ears
 * would read as antennae rather than ears.
 */
private const val EAR_SPLAY_LIMIT = 12f

/** How an ear sits: tip length multiplier, and outward rotation in degrees. */
private class EarPose(val perk: Float, val splay: Float)

/**
 * Cat body language, mapped onto the bot's states: forward and tall when
 * something has its attention, splayed and low when it is winding down.
 */
private fun earPoseFor(state: BotState): EarPose = when (state) {
    BotState.Alert, BotState.Exclaim, BotState.Notify -> EarPose(EAR_PERK_MAX, -8f)
    BotState.Listening -> EarPose(1.10f, -5f)
    BotState.Greet -> EarPose(1.14f, -4f)
    BotState.Happy, BotState.Smile, BotState.Proud, BotState.Love -> EarPose(1.12f, -3f)
    BotState.Responding, BotState.Narrating -> EarPose(1.03f, -1f)
    BotState.Working, BotState.Reading -> EarPose(1.0f, 2f)
    BotState.Thinking -> EarPose(0.97f, 5f)
    BotState.Confused -> EarPose(0.93f, 8f)
    BotState.Silly, BotState.Play, BotState.Swirl -> EarPose(1.08f, 9f)
    BotState.Sleepy -> EarPose(0.80f, 10f)
    BotState.Sad -> EarPose(0.76f, EAR_SPLAY_LIMIT)
    else -> EarPose(1f, 0f)
}

/** Which ear flicks this cycle; they take turns. */
private fun twitchSide(nowMs: Long): Int =
    if ((nowMs / TWITCH_PERIOD_MS) % 2L == 0L) -1 else 1

/**
 * The idle ear flick — a real cat never holds an ear perfectly still, and one
 * quick twitch every few seconds is what sells the bot as awake.
 */
private fun earTwitch(nowMs: Long, energy: Float): Float {
    val phase = (nowMs % TWITCH_PERIOD_MS).toFloat()
    if (phase > TWITCH_MS) return 0f
    return sin(phase / TWITCH_MS * PI.toFloat()) * 4f * energy.coerceIn(0.5f, 2f)
}

private const val TWITCH_PERIOD_MS = 5200L
private const val TWITCH_MS = 260f

private fun mix(a: Offset, b: Offset, t: Float) =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** How many points the eared outline is built from. The body's own profile is
 *  64, so this loses nothing there, and it takes about this many to keep the
 *  traced ear tip a point rather than a facet. */
private const val CAT_SAMPLES = 192

/** Tip-height damping on top of the traced reach — the one knob for "cat, but
 *  understated". It scales only what stands proud of the head, so the ears can
 *  go as low as you like without ever detaching. */
private const val EAR_TIP_DAMP = 0.9f

/** The tallest pose perk in [earPoseFor] — [CAT_FIT] budgets the canvas for it. */
private const val EAR_PERK_MAX = 1.20f

/** How far each ear sits from the crown beyond where it was traced, in degrees.
 *  The reference draws them close together on a wide head; on this rounder one
 *  they want more daylight between them. */
private const val EAR_SPREAD = 7f

/** Width of the blend where an ear meets the head, in body radii. This is the
 *  difference between an ear that grows out of the skull and one that looks
 *  glued on: the union is tangent to the ball, not incident to it. */
private const val EAR_MELT = 0.13f

/**
 * How far the eared silhouette stands from the body centre, in body radii, at
 * the tallest pose: the traced tips reach [EAR_TIP_REACH], damped, and a
 * smooth union can only ever pull the outline in.
 */
private const val EAR_REACH = 1f + (EAR_TIP_REACH - 1f) * EAR_PERK_MAX * EAR_TIP_DAMP

/**
 * How much smaller cat mode draws the bot, so the ear tips land where the
 * plain bot's crown does and the footprint the host padded for is unchanged.
 */
private const val CAT_FIT = 1f / EAR_REACH

/**
 * The body outline with the ears grown out of it, as one list of points.
 *
 * The ears are not a shape placed on the head — they are part of the same
 * profile. [EAR_PROFILE] says, for each angle off the crown, how far the traced
 * silhouette stands out in head radii; this samples the live body the same way
 * and takes a SMOOTH union of the two. Two consequences, both of them the point:
 *
 *  - They cannot come off. There is one outline, not two overlapping shapes, so
 *    no pose, squash or morph has a seam to open.
 *  - The join flows. A plain max() leaves the crease that makes an ear look
 *    glued on; blending over [EAR_MELT] brings the ear out of the head tangent
 *    to it, the way a real ear meets a skull, and the tips stay sharp because
 *    nothing blends out there.
 *
 * The ear rides the body's own radius at each angle, so it squashes, stretches
 * and drifts with whatever the state is doing, and [ballness] retracts it as the
 * silhouette stops being a ball — ears belong on a head, not on a speech bubble.
 */
private fun catSilhouette(
    points: List<Offset>,
    ballness: Float,
    perk: Float,
    splayDeg: Float,
    twitchDeg: Float,
    twitchSide: Int,
    out: ArrayList<Offset>
): List<Offset> {
    val n = points.size
    if (n < 8 || ballness <= 0.01f) return points
    val crown = points[n * 3 / 4]
    val up = kotlin.math.atan2(crown.y, crown.x)
    val stand = perk * EAR_TIP_DAMP * ballness

    out.clear()
    for (i in 0 until CAT_SAMPLES) {
        val a = i.toFloat() / CAT_SAMPLES * 2f * PI.toFloat()
        val bodyR = bodyRadius(points, a)
        // Angle off the crown, signed: negative to the bot's left.
        var off = a - up
        while (off > PI.toFloat()) off -= 2f * PI.toFloat()
        while (off < -PI.toFloat()) off += 2f * PI.toFloat()
        val side = if (off < 0f) -1 else 1
        // Splay swings the ear about the head centre, which is why it can never
        // lever the base off the body: it is only ever a phase shift.
        val pose = (splayDeg + (if (side == twitchSide) twitchDeg else 0f))
            .coerceIn(-EAR_SPLAY_LIMIT, EAR_SPLAY_LIMIT)
        val deg = kotlin.math.abs(off) * 180f / PI.toFloat() - pose - EAR_SPREAD
        // Perk stretches only what stands proud of the head, so the base stays
        // welded at every pose.
        val profile = earProfile(deg)
        val earR = if (profile <= 0f) 0f else bodyR * (1f + (profile - 1f) * stand)
        val r = smoothMax(bodyR, earR, bodyR * EAR_MELT)
        out.add(Offset(cos(a) * r, sin(a) * r))
    }
    return out
}

/** [EAR_PROFILE] at an arbitrary angle off the crown, interpolated. */
private fun earProfile(deg: Float): Float {
    val x = (deg - EAR_FROM_DEG) / EAR_STEP_DEG
    if (x <= 0f || x >= (EAR_PROFILE.size - 1).toFloat()) return 0f
    val i = x.toInt()
    return EAR_PROFILE[i] + (EAR_PROFILE[i + 1] - EAR_PROFILE[i]) * (x - i)
}

/**
 * Distance from the centre to the sampled outline at [angle], interpolated
 * between the two samples that bracket it.
 *
 * Every silhouette this engine draws is a radial profile, so reading the body
 * back this way is lossless — which is what lets the ears be part of the same
 * profile instead of a shape laid over it.
 */
private fun bodyRadius(points: List<Offset>, angle: Float): Float {
    // ponytail: linear scan of 64 samples per output point. Worth indexing by
    // angle only if a screen ever draws dozens of bots at once.
    val dx = cos(angle)
    val dy = sin(angle)
    var bestDot = -Float.MAX_VALUE
    var best = 0f
    var nextDot = -Float.MAX_VALUE
    var next = 0f
    points.forEach { p ->
        val len = kotlin.math.hypot(p.x, p.y)
        if (len > 1e-3f) {
            val dot = (p.x * dx + p.y * dy) / len
            if (dot > bestDot) {
                nextDot = bestDot
                next = best
                bestDot = dot
                best = len
            } else if (dot > nextDot) {
                nextDot = dot
                next = len
            }
        }
    }
    if (nextDot <= -1f) return best
    // Blend the two samples nearest this direction, so resampling a 64-point
    // body at [CAT_SAMPLES] does not polygonise it.
    val span = (bestDot - nextDot).coerceAtLeast(1e-6f)
    val t = (0.5f - span * 0.5f / (1f - nextDot).coerceAtLeast(1e-6f)).coerceIn(0f, 0.5f)
    return best + (next - best) * t
}

/**
 * Maximum of [a] and [b] rounded over a window of [k] instead of meeting at a
 * corner: the smooth union of signed-distance work, in the one dimension this
 * needs it.
 */
private fun smoothMax(a: Float, b: Float, k: Float): Float {
    if (k <= 1e-4f) return if (a > b) a else b
    val h = (0.5f + 0.5f * (a - b) / k).coerceIn(0f, 1f)
    return (b + (a - b) * h) + k * h * (1f - h)
}

private fun DrawScope.drawEye(
    eye: RenderedEye,
    color: Color,
    dst: Path,
    matrix: Matrix,
    glow: Boolean = false
) {
    val corner = minOf(eye.wPx, eye.hPx) / 2f
    // Built straight into dst and transformed in place: Compose's
    // Path.transform mutates the receiver, unlike the platform Path.transform
    // that read from a separate source, so the second scratch Path this used
    // to need is gone. Both objects are still caller-owned and reused across
    // frames — nothing here allocates per frame.
    dst.rewind()
    dst.addRoundRect(RoundRect(0f, 0f, eye.wPx, eye.hPx, CornerRadius(corner, corner)))
    // The engine emits a 2D affine transform (a, b, c, d, tx, ty); Compose's
    // Matrix is a 4x4 in column-major order, so the six values land at these
    // indices and everything else stays identity.
    matrix.reset()
    matrix.values[Matrix.ScaleX] = eye.a
    matrix.values[Matrix.SkewY] = eye.b * eye.blinkK
    matrix.values[Matrix.SkewX] = eye.c
    matrix.values[Matrix.ScaleY] = eye.d * eye.blinkK
    matrix.values[Matrix.TranslateX] = eye.tx
    matrix.values[Matrix.TranslateY] = eye.ty
    dst.transform(matrix)
    if (glow && eye.alpha > 0f) {
        // Two expanding copies at falling alpha read as bloom without a blur
        // mask filter, which fights hardware acceleration on some devices.
        val pivot = dst.getBounds().center
        scale(scaleX = 1.55f, scaleY = 1.55f, pivot = pivot) {
            drawPath(path = dst, color = color, alpha = eye.alpha * 0.14f)
        }
        scale(scaleX = 1.25f, scaleY = 1.25f, pivot = pivot) {
            drawPath(path = dst, color = color, alpha = eye.alpha * 0.22f)
        }
    }
    drawPath(path = dst, color = color, alpha = eye.alpha)
}

/** Infantry_Mascot: the clock a [frozen] bot draws at: past the entry blink, so the frame is the settled pose. */
const val FROZEN_FRAME_MS: Long = 1_500L
