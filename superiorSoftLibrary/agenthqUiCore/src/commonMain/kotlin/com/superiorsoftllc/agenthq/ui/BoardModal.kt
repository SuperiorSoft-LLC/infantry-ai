package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns

const val SIDEBAR_BOARD_BUTTON_TAG = "sidebar-board-button"
const val BOARD_MODAL_TAG = "board-modal"
const val BOARD_MODAL_CLOSE_TAG = "board-modal-close"

/**
 * M2-8: the compact sidebar entry to the Board (it replaced the centre "Board" tab). Shows how many tickets are
 * in progress, like the old tab label did. Ctrl+8 does the same.
 */
@Composable
fun SidebarBoardButton(state: AppState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val inProgress = state.column(TicketColumns.IN_PROGRESS).size
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
            .border(1.dp, Palette.Border, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Open the board", role = Role.Button, onClick = onOpen)
            .testTag(SIDEBAR_BOARD_BUTTON_TAG)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▦", color = Palette.Accent, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text("Board", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (inProgress > 0) Chip("$inProgress", Palette.Accent)
        Spacer(Modifier.width(6.dp))
        Text("^8", color = Palette.Muted, fontSize = 10.sp)
    }
}

/**
 * M2-8: the existing [BoardPane], unchanged, inside a near-full-screen modal: a header
 * with the title, the active project and a close button, then the board. The close button, Esc or a click on the
 * scrim dismiss it. Drawn as an in-window overlay (a full-size scrim over the window, laid last in [App]) rather
 * than a platform Dialog, so commonMain stays free of `androidx.compose.ui.window` (PlatformBoundaryTest).
 */
@Composable
fun BoardModal(state: AppState, controller: Controller, onOpenAgent: (String) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    // MODAL-BLUR: the backdrop blurs the app behind (or dims it when blur is off).
    ModalBackdrop(
        // The scrim takes every tap outside the panel, so nothing under the modal reacts; a tap on it closes.
        // pointerInput, not clickable: a clickable parent would merge the whole board into one semantics node.
        Modifier.pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
    ) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)).background(Palette.Bg)
                    .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                    .testTag(BOARD_MODAL_TAG)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
                    }
                    .focusable()
                    // The panel swallows its own taps so they never reach the scrim's dismiss (no semantics merge, unlike clickable).
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▦", color = Palette.Accent, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Board", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(state.activeProject?.name ?: "All projects", color = Palette.Muted, fontSize = 11.sp)
                    }
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .semantics { contentDescription = "Close the board" }
                            .testTag(BOARD_MODAL_CLOSE_TAG),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Palette.Muted, fontSize = 14.sp) }
                }
                HorizontalDivider(color = Palette.Border)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
                    BoardPane(state, controller, onOpenAgent)
                }
            }
        }
        // Focus lands in the modal so Esc reaches it straight away.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
