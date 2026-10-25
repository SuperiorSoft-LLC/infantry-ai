package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import com.superiorsoftllc.agenthq.ui.model.ReasoningPlacement
import com.superiorsoftllc.agenthq.ui.model.reasoningCopyText

const val REASONING_BUTTON_TAG = "reasoning-button"
const val REASONING_PANEL_TAG = "reasoning-panel"
const val REASONING_PANEL_TEXT_TAG = "reasoning-panel-text"
const val REASONING_PANEL_COPY_TAG = "reasoning-panel-copy"
const val REASONING_PANEL_CLOSE_TAG = "reasoning-panel-close"
const val REASONING_PLACEMENT_TAG_PREFIX = "reasoning-placement-"

/** The height the top-right slot's button takes (button + its padding); a card stacked below it starts under this. */
val REASONING_BUTTON_SLOT = 40.dp

/** Where the chat draws reasoning on this device; provided by the app from the Appearance setting. */
val LocalReasoningPlacement = staticCompositionLocalOf { ReasoningPlacement.INLINE }

/**
 * The top-right slot's first row: a small button that opens the reasoning panel, and the panel itself while [open].
 * Lay it out inside the transcript's Box; the to-do card is laid below it (see [REASONING_BUTTON_SLOT]).
 * Draws nothing when the session has no reasoning and the panel is closed.
 */
@Composable
fun BoxScope.ReasoningSlot(lines: List<OutputLine>, open: Boolean, onOpen: (Boolean) -> Unit) {
    if (lines.isEmpty() && !open) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val cap = maxHeight * 0.6f
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReasoningButton(lines.size, open) { onOpen(!open) }
            if (open) ReasoningPanel(lines, maxHeight = cap, onClose = { onOpen(false) })
        }
    }
}

@Composable
private fun ReasoningButton(count: Int, open: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag(REASONING_BUTTON_TAG)
            .semantics { contentDescription = if (open) "Close reasoning panel" else "Open reasoning panel, $count lines" }
            .clickable(onClick = onToggle),
    ) {
        androidx.compose.material3.Icon(
            AhqIcons.Brain, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(7.dp).size(18.dp),
        )
    }
}

@Composable
private fun ReasoningPanel(lines: List<OutputLine>, maxHeight: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    val clip = LocalClipboardManager.current
    val scroll = rememberScrollState()
    // Newest at the bottom: follow the stream as lines arrive or the last one grows.
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.testTag(REASONING_PANEL_TAG).widthIn(max = 420.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Reasoning", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                OutlinedButton(onClick = { clip.setText(AnnotatedString(reasoningCopyText(lines))) }, modifier = Modifier.testTag(REASONING_PANEL_COPY_TAG)) { Text("Copy", fontSize = 11.sp) }
                OutlinedButton(onClick = onClose, modifier = Modifier.testTag(REASONING_PANEL_CLOSE_TAG)) { Text("Close", fontSize = 11.sp) }
            }
            Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll)) {
                SelectionContainer {
                    Text(
                        if (lines.isEmpty()) "No reasoning yet." else reasoningCopyText(lines),
                        Modifier.testTag(REASONING_PANEL_TEXT_TAG),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Appearance setting: the two placements as a pair of buttons, the picked one filled. */
@Composable
fun ReasoningPlacementChoice(picked: ReasoningPlacement, onPick: (ReasoningPlacement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReasoningPlacement.entries.forEach { p ->
                OutlinedButton(onClick = { onPick(p) }, modifier = Modifier.testTag(REASONING_PLACEMENT_TAG_PREFIX + p.name)) {
                    Text(if (p == picked) "✓ ${p.label}" else p.label, fontSize = 12.sp)
                }
            }
        }
        Text(picked.effect, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
