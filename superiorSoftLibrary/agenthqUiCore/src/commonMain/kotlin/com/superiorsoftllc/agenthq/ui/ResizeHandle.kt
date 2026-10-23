package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** M2-9: the platform's horizontal (east/west) resize cursor: ↔ on desktop, the double arrow on Android. */
expect val HorizontalResizeIcon: PointerIcon

/** The hover cursor a resize handle applies, exposed in semantics so tests can read it (the cursor itself is not). */
val ResizeCursor = SemanticsPropertyKey<PointerIcon>("ResizeCursor")
var SemanticsPropertyReceiver.resizeCursor by ResizeCursor

/**
 * M2-9: a vertical drag edge between two panes: the 1dp divider line plus a small grip, the ↔ cursor on hover,
 * and horizontal drags reported to [onDragDp] in dp (positive = pointer moved right).
 */
@Composable
internal fun ResizeHandle(tag: String, onDragDp: (Float) -> Unit) {
    val density = LocalDensity.current
    val onDrag by rememberUpdatedState(onDragDp)
    val icon = HorizontalResizeIcon
    Box(
        Modifier.width(7.dp).fillMaxHeight().testTag(tag)
            .semantics { resizeCursor = icon }
            .pointerHoverIcon(icon)
            .pointerInput(density) {
                detectHorizontalDragGestures { change, dragPx ->
                    change.consume()
                    onDrag(dragPx / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
        Box(Modifier.testTag("resize-grip").size(width = 3.dp, height = 32.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Muted))
    }
}
