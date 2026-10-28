package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.ToastKind
import com.superiorsoftllc.agenthq.ui.model.crossProjectNotices
import com.superiorsoftllc.agenthq.ui.model.toToast
import com.superiorsoftllc.agenthq.ui.model.visibleToasts
import kotlinx.coroutines.delay

/** Test tag on the bottom-right host, on each toast in it, and on a toast's close button. */
const val TOAST_HOST_TAG = "toast-host"
const val TOAST_TAG = "toast"
const val TOAST_CLOSE_TAG = "toast-close"

private const val SLIDE_MS = 220
private const val TICK_MS = 50L

/** RUNTIME-17: the notice colours (NOTICE-COLOUR-1: heads-up amber, error red), kept here since the host is shell code. */
fun toastColour(kind: ToastKind): Color = when (kind) {
    ToastKind.INFO -> Palette.Info
    ToastKind.SUCCESS -> Palette.Ok
    ToastKind.WARN -> Palette.Warn
    ToastKind.ERROR -> Palette.Danger
}

/**
 * Global bottom-right toasts: every [ToastEvent] in the state plus notices about OTHER projects, newest on top, at most
 * three. Each slides up in and down out (a plain fade when [reducedMotion]), stays [ToastEvent.durationMs] with the clock
 * paused while hovered, and goes on close or click. Overlay it in a [Box] over the whole window; it draws nothing when
 * there is nothing to say. [onOpen] is called when a toast is clicked (jump to its session / ticket / project).
 */
@Composable
fun ToastHost(state: AppState, onOpen: (ToastEvent) -> Unit, modifier: Modifier = Modifier, reducedMotion: Boolean = false) =
    ToastHost(toastEvents(state), onOpen, modifier, reducedMotion)


/** S2: [ToastHost] over [all], oldest first (the shell's [com.superiorsoftllc.agenthq.ui.shell.ToastContext.events]). */
@Composable
fun ToastHost(all: List<ToastEvent>, onOpen: (ToastEvent) -> Unit, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val baseline = remember { all.maxOfOrNull { it.at } ?: Long.MIN_VALUE }
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    val shown = visibleToasts(all, baseline, dismissed)
    if (shown.isEmpty()) return
    Column(
        modifier.padding(16.dp).width(340.dp).testTag(TOAST_HOST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (t in shown) {
            androidx.compose.runtime.key(t.id) {
                Toast(t, reducedMotion, onOpen = { onOpen(t) }, onGone = { dismissed = dismissed + t.id })
            }
        }
    }
}

@Composable
private fun Toast(t: ToastEvent, reducedMotion: Boolean, onOpen: () -> Unit, onGone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val hoveredNow by rememberUpdatedState(hovered)
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(Unit) {
        visible = true
        var left = t.durationMs
        while (left > 0 && !leaving) { delay(TICK_MS); if (!hoveredNow) left -= TICK_MS }
        visible = false
        delay(SLIDE_MS.toLong())
        gone()
    }
    val close = { leaving = true }
    val colour = toastColour(t.kind)
    AnimatedVisibility(
        visible = visible && !leaving,
        enter = if (reducedMotion) fadeIn(tween(SLIDE_MS)) else slideInVertically(tween(SLIDE_MS)) { it } + fadeIn(tween(SLIDE_MS)),
        exit = if (reducedMotion) fadeOut(tween(SLIDE_MS)) else slideOutVertically(tween(SLIDE_MS)) { it } + fadeOut(tween(SLIDE_MS)),
    ) {
        Row(
            Modifier.height(IntrinsicSize.Min).background(colour.copy(alpha = 0.12f).compositeOver(Palette.Panel))
                .hoverable(hover).clickable { close(); onOpen() }.testTag(TOAST_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colour))
            Column(Modifier.weight(1f).padding(10.dp, 6.dp)) {
                Text(t.title, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
                t.body?.let { Text(it, color = colour, fontSize = 12.sp, maxLines = 3) }
            }
            Text("×", color = Palette.Muted, fontSize = 16.sp,
                modifier = Modifier.clickable { close() }.semantics { contentDescription = "Dismiss notification" }
                    .padding(10.dp, 4.dp).testTag(TOAST_CLOSE_TAG))
        }
    }
}
