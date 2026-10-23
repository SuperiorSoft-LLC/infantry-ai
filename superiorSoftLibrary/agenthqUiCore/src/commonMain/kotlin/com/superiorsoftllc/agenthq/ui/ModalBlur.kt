package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.BlurredEdgeTreatment

/**
 * MODAL-BLUR: what is behind an open modal. One owner for every dialog: the app root reads [blurActive] to blur its
 * content, and each modal (via [ModalBackdrop], [RegisterModal] or [AhqAlertDialog]) only reports that it is open.
 * A counter, not a flag, so nested modals keep the blur on until the last one closes.
 */
class ModalBlurState(enabled: Boolean = true, supported: Boolean = true) {
    private var open by mutableIntStateOf(0)

    /** The "Blur behind dialogs" setting. */
    var enabled by mutableStateOf(enabled)

    /** Whether the platform can blur (Android below 12 cannot). */
    var supported by mutableStateOf(supported)

    val openCount: Int get() = open
    val anyModalOpen: Boolean get() = open > 0

    /** The app behind is blurred: a modal is open, the setting is on and the platform can render it. */
    val blurActive: Boolean get() = anyModalOpen && enabled && supported

    /** Blur is the backdrop style, whether or not a modal is open right now. */
    val blurStyle: Boolean get() = enabled && supported

    fun opened() { open++ }

    fun closed() { if (open > 0) open-- }
}

val LocalModalBlur = staticCompositionLocalOf { ModalBlurState() }

/** How strongly the app behind a modal is blurred. */
val ModalBlurRadius: Dp = 12.dp

private const val BLUR_FADE_MS = 180

/** The dark dim used when blur is off or unsupported (today's look). */
val ModalScrimDark = Color(0xB8000000)

/** The light tint over the blurred app, enough to lift the dialog off it. */
val ModalScrimTint = Color(0x33000000)

/** Reports a modal open for as long as this is in the composition. */
@Composable
fun RegisterModal() {
    val state = LocalModalBlur.current
    DisposableEffect(state) {
        state.opened()
        onDispose { state.closed() }
    }
}

/** Blurs this node while [state] says a modal is open, fading the radius in and out. */
@Composable
fun Modifier.modalBlur(state: ModalBlurState = LocalModalBlur.current): Modifier {
    val radius by animateDpAsState(if (state.blurActive) ModalBlurRadius else 0.dp, tween(BLUR_FADE_MS))
    return if (radius > 0.dp) this.blur(radius, BlurredEdgeTreatment.Unbounded) else this
}

/**
 * The backdrop of an in-window modal: registers it as open, then fills the window with the light tint when the app
 * behind is blurred, or the dark scrim when blur is off or unsupported. [content] is the modal's own layout.
 */
@Composable
fun ModalBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    RegisterModal()
    val state = LocalModalBlur.current
    Box(modifier.fillMaxSize().background(if (state.blurStyle) ModalScrimTint else ModalScrimDark)) { content() }
}

/**
 * [AlertDialog] that counts as an open modal. The dialog itself (focus, Esc, click outside) is the platform's,
 * unchanged; only the blur registration is added. Keeps `androidx.compose.ui.window` out of commonMain.
 */
@Composable
fun AhqAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    RegisterModal()
    AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
        dismissButton = dismissButton, title = title, text = text, containerColor = containerColor,
    )
}
