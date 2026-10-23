package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MEMORY-UI-1: the shared create / edit modal of the side panes (Memory today; Prompts, Skills, Epics and the
 * profile editor in follow-ups). A [title], the caller's form [content], an optional [error], then Cancel / Save.
 *
 * Drawn as an overlay that fills its parent (a scrim, the panel centred on top) rather than a platform Dialog, like
 * [BoardModal], so commonMain stays free of `androidx.compose.ui.window` (PlatformBoundaryTest). The caller puts it
 * last inside a Box that covers the area it should dim. Esc, Cancel or a tap on the scrim call [onCancel]; Save is
 * enabled by [saveEnabled]. Closing on success is the caller's call (it knows when the request was sent), so the
 * modal never closes itself on Save.
 */
@Composable
fun EditorModal(
    title: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = true,
    error: String? = null,
    saveLabel: String = "Save",
    saveTag: String? = null,
    cancelTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.72f))
            .pointerInput(onCancel) { detectTapGestures { onCancel() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(Palette.Bg)
                .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                .then(modifier)
                .semantics { paneTitle = title }
                .focusRequester(focus)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false }
                .focusable()
                // The panel swallows its own taps so they never reach the scrim's dismiss.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            content()
            error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel, modifier = cancelTag?.let { Modifier.testTag(it) } ?: Modifier) { Text("Cancel", fontSize = 12.sp) }
                Button(
                    onClick = onSave, enabled = saveEnabled,
                    modifier = saveTag?.let { Modifier.testTag(it) } ?: Modifier,
                ) { Text(saveLabel, fontSize = 12.sp) }
            }
        }
        // Focus lands in the modal so Esc reaches it straight away.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
