package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

const val TAB_BAR_MORE_CUE_TAG = "tab-bar-more-cue"
const val TAB_BAR_ARROW_LEFT_TAG = "tab-bar-arrow-left"
const val TAB_BAR_ARROW_RIGHT_TAG = "tab-bar-arrow-right"

/** How far one notch of a vertical mouse wheel moves a tab strip sideways. */
private val WheelStep = 48.dp

/**
 * A tab strip (the centre strip and the right panel's). Scrolls sideways when the labels outgrow the width, so no tab
 * is ever squeezed or hidden. While it overflows, PLUGINS-3's arrows sit at either end (each disabled once there is
 * nothing left to scroll that way) and a fade over the right edge marks the tabs off it. Touch drag scrolls it, and a
 * vertical mouse wheel scrolls it sideways. [modifier] goes first on the strip's row (a block's placement, BLOCKS-LAYOUT
 * L9a); the strip's divider under it does not wear it.
 */
@Composable
internal fun TabBar(
    tabs: List<Pair<String, Boolean>>,
    hPad: Dp = 14.dp,
    fontSize: TextUnit = 12.sp,
    tagOf: ((Int) -> String)? = null,
    barTag: String? = null,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val wheelStep = with(LocalDensity.current) { WheelStep.toPx() }
    // One page per press, less a margin so the tab cut at the edge stays in view.
    val page = { (scroll.viewportSize * 0.8f).coerceAtLeast(1f) }
    val overflows = scroll.maxValue > 0
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Palette.Panel)) {
        if (overflows) StripArrow(AhqIcons.ChevronLeft, "Scroll tabs left", scroll.canScrollBackward, TAB_BAR_ARROW_LEFT_TAG) { scope.launch { scroll.animateScrollBy(-page()) } }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().let { m -> barTag?.let { m.testTag(it) } ?: m }
                    .pointerInput(scroll) {
                        // Initial pass: a purely vertical wheel turn becomes a sideways scroll before the strip sees it.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.type != PointerEventType.Scroll) continue
                                val d = e.changes.firstOrNull()?.scrollDelta ?: continue
                                if (d.x == 0f && d.y != 0f && scroll.maxValue > 0) {
                                    scope.launch { scroll.scrollBy(d.y * wheelStep) }
                                    e.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .horizontalScroll(scroll),
            ) {
                tabs.forEachIndexed { i, (label, on) ->
                    Column(Modifier.let { m -> tagOf?.let { m.testTag(it(i)) } ?: m }.clickable { onPick(i) }.padding(horizontal = hPad, vertical = 7.dp)) {
                        Text(label, maxLines = 1, softWrap = false, color = if (on) Palette.Accent else Palette.Muted, fontSize = fontSize, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            if (scroll.canScrollForward) EdgeFade(FadeEdge.END, Palette.Panel, TAB_BAR_MORE_CUE_TAG)
        }
        if (overflows) StripArrow(AhqIcons.ChevronRight, "Scroll tabs right", scroll.canScrollForward, TAB_BAR_ARROW_RIGHT_TAG) { scope.launch { scroll.animateScrollBy(page()) } }
    }
    HorizontalDivider(color = Palette.Border)
}

/** One end's scroll arrow: the strip's muted colour, dimmed while there is nothing left to scroll that way. */
@Composable
private fun StripArrow(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().width(22.dp).clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Palette.Muted else Palette.Muted.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
    }
}
