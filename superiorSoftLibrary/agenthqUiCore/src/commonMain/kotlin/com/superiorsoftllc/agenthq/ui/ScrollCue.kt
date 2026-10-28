package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** The fade over the start (top) of a scrolling column, while there is content above. */
const val SCROLL_CUE_START_TAG = "scroll-cue-start"
/** The fade over the end (bottom) of a scrolling column, while there is content below. */
const val SCROLL_CUE_END_TAG = "scroll-cue-end"
const val SCROLL_CUE_THUMB_TAG = "scroll-cue-thumb"

/** Which edge an [EdgeFade] covers. */
internal enum class FadeEdge { TOP, BOTTOM, END }

/**
 * A soft fade over one [edge] of a scrolling area, from clear to [color] (the surface behind it), marking content past
 * that edge. No border: the fade alone says "more this way". Shared by the tab strips (PLUGINS-3) and [ScrollCueColumn].
 */
@Composable
internal fun BoxScope.EdgeFade(edge: FadeEdge, color: Color, tag: String, depth: Dp = 32.dp) {
    // matchParentSize keeps the overlay from stretching its parent (a fillMax child would take the whole column).
    Box(Modifier.matchParentSize()) {
        val clear = color.copy(alpha = 0f)
        val m = when (edge) {
            FadeEdge.TOP -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(depth).background(Brush.verticalGradient(listOf(color, clear)))
            FadeEdge.BOTTOM -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(depth).background(Brush.verticalGradient(listOf(clear, color)))
            FadeEdge.END -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(depth).background(Brush.horizontalGradient(listOf(clear, color)))
        }
        Box(m.testTag(tag))
    }
}

/**
 * A vertically scrolling column that shows when it scrolls (ticket cb1ceb93): a fade over the bottom edge while there
 * is more below, over the top while there is more above, and a slim thumb at the right while the pointer is over it or
 * it is scrolling. Nothing shows when the content fits. [modifier] sizes the column (e.g. a height cap); [fade] is the
 * surface it sits on. No borders.
 */
@Composable
fun ScrollCueColumn(
    modifier: Modifier,
    fade: Color,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(hover)) {
        // The end padding keeps the thumb clear of the content's right-hand controls.
        Column(modifier.verticalScroll(scroll).padding(end = ThumbGutter), verticalArrangement = verticalArrangement, content = content)
        if (scroll.canScrollBackward) EdgeFade(FadeEdge.TOP, fade, SCROLL_CUE_START_TAG, depth = 24.dp)
        if (scroll.canScrollForward) EdgeFade(FadeEdge.BOTTOM, fade, SCROLL_CUE_END_TAG, depth = 40.dp)
        if (scroll.maxValue > 0) ScrollThumb(scroll, visible = hovered || scroll.isScrollInProgress)
    }
}

private val ThumbGutter = 10.dp
private val ThumbWidth = 4.dp
private val ThumbMinLength = 24.dp

/** The slim thumb: its length is the visible share of the content, its position the scroll offset. Fades in and out. */
@Composable
private fun BoxScope.ScrollThumb(scroll: ScrollState, visible: Boolean) {
    val alpha by animateFloatAsState(if (visible) 0.55f else 0f)
    val minPx = with(LocalDensity.current) { ThumbMinLength.toPx() }
    Box(Modifier.matchParentSize()) {
        val viewport = scroll.viewportSize.toFloat()
        val total = viewport + scroll.maxValue
        val length = (viewport * viewport / total).coerceIn(minPx.coerceAtMost(viewport), viewport)
        val top = if (scroll.maxValue == 0) 0f else (viewport - length) * scroll.value / scroll.maxValue
        val lengthDp = with(LocalDensity.current) { length.toDp() }
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top.toInt()) }.width(ThumbWidth).height(lengthDp)
                .graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(ThumbWidth / 2)).background(Palette.Muted)
                .testTag(SCROLL_CUE_THUMB_TAG),
        )
    }
}
