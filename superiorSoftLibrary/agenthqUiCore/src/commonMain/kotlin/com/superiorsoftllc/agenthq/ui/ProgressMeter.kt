package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_DEFAULT_MIN
import com.superiorsoftllc.agenthq.ui.model.ProgressTone
import com.superiorsoftllc.agenthq.ui.model.progressDisplay
import com.superiorsoftllc.agenthq.ui.protocol.ProgressView
import kotlinx.coroutines.delay

/** Contract 1.117.0: the "no update for N min" threshold the Configuration dialog sets, read by every [ProgressMeter]. */
val LocalProgressStaleMin = compositionLocalOf { PROGRESS_STALE_DEFAULT_MIN }

const val PROGRESS_METER_TAG_PREFIX = "progress-meter-"

/** The bar's colour for a [ProgressTone]: amber when stale, red when failed, green when done. */
fun progressColor(tone: ProgressTone): Color = when (tone) {
    ProgressTone.Running -> Palette.Info
    ProgressTone.Stale -> Palette.Accent
    ProgressTone.Done -> Palette.Ok
    ProgressTone.Failed -> Palette.Danger
}

/**
 * A slim live progress bar with its headline and detail line: a fill for a measured job, a shimmer for an
 * indeterminate one. Ticks once a second while the job runs (elapsed time, staleness). [compact] drops the detail
 * line, for a dense list row.
 */
@Composable
fun ProgressMeter(progress: ProgressView, tagKey: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val running = progress.state == "running"
    val now by produceState(kotlin.time.Clock.System.now().toEpochMilliseconds(), progress, running) {
        value = kotlin.time.Clock.System.now().toEpochMilliseconds()
        while (running) {
            delay(1_000)
            value = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
    val view = progressDisplay(progress, now, LocalProgressStaleMin.current)
    val color = progressColor(view.tone)
    Column(
        modifier.fillMaxWidth().testTag(PROGRESS_METER_TAG_PREFIX + tagKey)
            .semantics {
                contentDescription = "progress: ${view.headline}" + if (view.detail.isNotEmpty()) ", ${view.detail}" else ""
                view.fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it, 0f..1f) }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                view.headline, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (compact && view.detail.isNotEmpty()) {
                Text(view.detail, color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Bar(view.fraction, color, hatched = view.estimated)
        if (!compact && view.detail.isNotEmpty()) {
            Text(view.detail, color = if (view.tone == ProgressTone.Stale) Palette.Accent else Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Bar(fraction: Float?, color: Color, hatched: Boolean = false) {
    val shape = RoundedCornerShape(2.dp)
    val track = Palette.Border
    if (fraction != null) {
        val shown by animateFloatAsState(fraction, tween(400), label = "progress")
        Canvas(Modifier.fillMaxWidth().height(4.dp).clip(shape)) {
            drawRect(track)
            if (!hatched) {
                drawRect(color, size = Size(size.width * shown, size.height))
            } else {
                // PROGRESS-S5: an estimate is drawn hatched (diagonal stripes over a faint fill), never as a solid measurement.
                val w = size.width * shown
                drawRect(color.copy(alpha = 0.25f), size = Size(w, size.height))
                clipRect(right = w) {
                    val step = 6.dp.toPx()
                    var x = -size.height
                    while (x < w) {
                        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
                        x += step
                    }
                }
            }
        }
    } else {
        // Indeterminate: a soft highlight sweeping across the track; no number is drawn or implied.
        val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
            -0.4f, 1.4f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer",
        )
        Canvas(Modifier.fillMaxWidth().height(4.dp).clip(shape)) {
            drawRect(track)
            val w = size.width * 0.35f
            val x = size.width * sweep - w / 2
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), startX = x, endX = x + w),
                topLeft = Offset(x, 0f), size = Size(w, size.height),
            )
        }
    }
}
