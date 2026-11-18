// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Test tags of [HoverRevealTab]. */
const val HOVER_REVEAL_TAB_TAG = "hover-reveal-tab"
const val HOVER_REVEAL_PANEL_TAG = "hover-reveal-panel"

/** How long the pointer may be off both the tab and the panel before the panel slides back in. */
const val HOVER_REVEAL_GRACE_MS = 250L

/**
 * SLIDE-TAB-1: a crowded row collapses into one small icon tab. [content] slides out beside the tab as an overlay
 * (a popup, drawn above everything, so nothing is pushed, squeezed or reflowed) and slides back when it is left.
 *
 * - Pointer: hovering the tab opens it; it closes [graceMs] after the pointer is off both the tab and the panel.
 * - Touch: a tap opens it and it stays open until a touch lands outside it; tapping the tab again closes it.
 *   (A mouse click on the tab pins it the same way.)
 * - Keyboard: the tab is focusable; Enter or Space toggles, Esc closes.
 *
 * The panel is anchored to the tab's right edge and grows leftwards, and is kept inside the window.
 */
@Composable
fun HoverRevealTab(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    graceMs: Long = HOVER_REVEAL_GRACE_MS,
    content: @Composable () -> Unit,
) {
    require(label.isNotBlank()) { "a hover-reveal tab needs a name for its tooltip and screen readers" }
    var tabHover by remember { mutableStateOf(false) }
    var panelHover by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var viaKeyboard by remember { mutableStateOf(false) } // a keyboard-opened panel keeps focus on the tab so Esc reaches it
    var pinnedAtPress by remember { mutableStateOf(false) } // a tap on the tab while pinned first lands as an outside touch that closes the panel
    val open = hovering || pinned
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open

    // The grace delay: leaving both areas only closes after graceMs; re-entering in time cancels it.
    val over = tabHover || panelHover
    LaunchedEffect(over) {
        if (over) hovering = true else { delay(graceMs); hovering = false }
    }

    Box(modifier) {
        IconActionTooltipBox(label) { tipModifier ->
            Box(
                tipModifier
                    .testTag(HOVER_REVEAL_TAB_TAG)
                    .semantics { role = Role.Button }
                    .border(BorderStroke(1.dp, Palette.Border), Palette.FieldShape)
                    .hoverTracker { tabHover = it }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Main)
                                if (e.type == PointerEventType.Press) pinnedAtPress = pinned
                                if (e.type == PointerEventType.Release) { viaKeyboard = false; pinned = !pinnedAtPress }
                            }
                        }
                    }
                    .onKeyEvent { k ->
                        if (k.type != KeyEventType.KeyDown) false
                        else when (k.key) {
                            Key.Enter, Key.NumPadEnter, Key.Spacebar -> { viaKeyboard = true; pinned = !pinned; true }
                            Key.Escape -> if (open) { pinned = false; hovering = false; true } else false
                            else -> false
                        }
                    }
                    .focusable()
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (open) Palette.Accent else Palette.Muted, modifier = Modifier.padding(0.dp))
            }
        }
        if (visible.currentState || visible.targetState) {
            Popup(
                popupPositionProvider = RightEdgePositionProvider,
                onDismissRequest = { pinned = false; hovering = false },
                // Focusable only while pinned: then Esc and a touch outside dismiss it; a hover-only panel never steals focus.
                properties = PopupProperties(focusable = pinned && !viaKeyboard, dismissOnClickOutside = pinned && !viaKeyboard, dismissOnBackPress = pinned),
            ) {
                AnimatedVisibility(
                    visibleState = visible,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                ) {
                    Surface(
                        color = Palette.Panel,
                        shape = Palette.PopupShape,
                        border = BorderStroke(1.dp, Palette.Border),
                        shadowElevation = 4.dp,
                        modifier = Modifier.testTag(HOVER_REVEAL_PANEL_TAG).hoverTracker { panelHover = it },
                    ) {
                        Box(Modifier.padding(8.dp)) { content() }
                    }
                }
            }
        }
    }
}

/** Reports mouse enter/exit only (touch has no hover); watched before the children, consuming nothing. */
private fun Modifier.hoverTracker(onHover: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.firstOrNull()?.type != PointerType.Mouse) continue
            when (e.type) {
                PointerEventType.Enter -> onHover(true)
                PointerEventType.Exit -> onHover(false)
            }
        }
    }
}

/** The popup's right edge sits on the tab's right edge, directly below it (never over the tab), and is shifted to stay inside the window. */
private object RightEdgePositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.bottom.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
