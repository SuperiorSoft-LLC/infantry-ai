// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How far one notch of a vertical mouse wheel moves an [ArrowScrollRow] sideways. */
private val WheelStep = 48.dp

/**
 * How far one arrow press moves an [ArrowScrollRow], given the visible width in px. [page] (the tab strips) moves most
 * of a viewport, less a margin so the item cut at the edge stays in view; [fixed] moves a set distance (about one chip).
 */
fun interface ArrowStep {
    fun px(viewportPx: Int, density: Float): Float

    companion object {
        val page = ArrowStep { viewport, _ -> (viewport * 0.8f).coerceAtLeast(1f) }
        fun fixed(step: Dp) = ArrowStep { viewport, density -> (step.value * density).coerceAtMost(viewport.toFloat()).coerceAtLeast(1f) }
    }
}

/**
 * UI-ARROWS-1: a row that scrolls sideways when its content outgrows the width, with PLUGINS-3's arrows at either end
 * while it overflows. Each arrow is a keyboard-focusable button, disabled once there is nothing left to scroll its way,
 * and moves the row by [step]; when everything fits there are no arrows. Touch drag scrolls the row, and a vertical
 * mouse wheel scrolls it sideways. [fade], when set, is the surface colour of a fade over the right edge that marks the
 * content past it (tagged [fadeTag]). [contentTag] goes on the scrolling row itself. Shared by the tab strips
 * ([TabBar]) and the agents sidebar's role filter chips, so any future strip gets the same arrows.
 */
@Composable
fun ArrowScrollRow(
    leftTag: String,
    rightTag: String,
    modifier: Modifier = Modifier,
    leftLabel: String = "Scroll left",
    rightLabel: String = "Scroll right",
    step: ArrowStep = ArrowStep.page,
    contentTag: String? = null,
    fade: Color? = null,
    fadeTag: String = "",
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val wheelStep = WheelStep.value * density
    val by = { step.px(scroll.viewportSize, density) }
    val overflows = scroll.maxValue > 0
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (overflows) StripArrow(AhqIcons.ChevronLeft, leftLabel, scroll.canScrollBackward, leftTag) { scope.launch { scroll.animateScrollBy(-by()) } }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().let { m -> contentTag?.let { m.testTag(it) } ?: m }
                    .pointerInput(scroll) {
                        // Initial pass: a purely vertical wheel turn becomes a sideways scroll before the row sees it.
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
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content,
            )
            if (fade != null && scroll.canScrollForward) EdgeFade(FadeEdge.END, fade, fadeTag)
        }
        if (overflows) StripArrow(AhqIcons.ChevronRight, rightLabel, scroll.canScrollForward, rightTag) { scope.launch { scroll.animateScrollBy(by()) } }
    }
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
