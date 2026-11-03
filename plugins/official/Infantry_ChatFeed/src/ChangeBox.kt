package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ChangeBox
import com.superiorsoftllc.infantry.ui.model.ChangeBoxView
import com.superiorsoftllc.infantry.ui.model.ChangeKind
import com.superiorsoftllc.infantry.ui.model.changeBoxView
import com.superiorsoftllc.infantry.ui.model.fileWriteOf
import com.superiorsoftllc.infantry.ui.protocol.FileWrite

const val CHANGE_BOX_TAG = "change-box"
const val CHANGE_BOX_COUNTS_TAG = "change-box-counts"
const val CHANGE_BOX_MORE_TAG = "change-box-more"
const val CHANGE_BOX_VIEW_ALL_TAG = "change-box-view-all"
const val CHANGE_BOX_COLLAPSE_TAG = "change-box-collapse"
const val CHANGE_BOX_FADE_TAG = "change-box-fade"

/** A [LineKind.WRITE][com.superiorsoftllc.infantry.ui.model.LineKind.WRITE] transcript line's text, drawn as a [ChangeBox]. */
@Composable
fun ChangeBoxLine(text: String) {
    val write = remember(text) { fileWriteOf(text) }
    if (write == null) Text("✎ (a file write this window could not read)", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
    else ChangeBox(write)
}

/**
 * UX-WRITE-1: one file-writing tool call, inline in the chat, styled like the Changes panel's diff (it draws the same
 * [DiffLine] rows). Header: an icon, the path, created / edited, and +N / −M. Body: the lines, numbered, monospace; an
 * edit is diff-styled. Collapsed to [ChangeBox.PREVIEW_LINES] rows with a fade, "+N more lines" and View all; expanded,
 * Collapse folds it back. A box that fits its preview shows in full with no control.
 */
@Composable
fun ChangeBox(write: FileWrite) {
    val view = remember(write) { changeBoxView(write) }
    var expanded by remember(write) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(shape).border(1.dp, Palette.Border, shape).background(Palette.Bg)
            .testTag(CHANGE_BOX_TAG),
    ) {
        ChangeBoxHeader(view)
        HorizontalDivider(color = Palette.Border)
        if (view.truncated) {
            Text("Cut by the daemon: the write is longer than shown.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        val rows = ChangeBox.visibleRows(view, expanded)
        val folded = ChangeBox.collapsible(view) && !expanded
        Box(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // As in the diff modal: rows are as wide as the longest line, never narrower than the box, and only
                // this body scrolls sideways.
                val viewport = maxWidth
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.widthIn(min = viewport).width(IntrinsicSize.Max).padding(vertical = 4.dp)) {
                        rows.forEach { DiffLine(it, oldGutter = view.kind != ChangeKind.CREATED) }
                    }
                }
            }
            if (folded) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Bg)))
                        .testTag(CHANGE_BOX_FADE_TAG),
                )
            }
        }
        if (ChangeBox.collapsible(view)) {
            HorizontalDivider(color = Palette.Border)
            Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (folded) Text(ChangeBox.moreLabel(view), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.testTag(CHANGE_BOX_MORE_TAG))
                Spacer(Modifier.weight(1f))
                if (folded) ChangeBoxButton(ChangeBox.viewAllLabel(view), CHANGE_BOX_VIEW_ALL_TAG) { expanded = true }
                else ChangeBoxButton(ChangeBox.COLLAPSE_LABEL, CHANGE_BOX_COLLAPSE_TAG) { expanded = false }
            }
        }
    }
}

@Composable
private fun ChangeBoxHeader(view: ChangeBoxView) {
    val created = view.kind == ChangeKind.CREATED
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (created) "＋" else "✎",
            color = if (created) Palette.Ok else Palette.Info,
            fontSize = 12.sp,
            modifier = Modifier.semantics { contentDescription = view.kind.label },
        )
        Spacer(Modifier.width(8.dp))
        Text(view.path, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(view.kind.label, color = Palette.Muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.testTag(CHANGE_BOX_COUNTS_TAG).semantics(mergeDescendants = true) {}) {
            Text("+${view.added}", color = Palette.Ok, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (!created) {
                Spacer(Modifier.width(6.dp))
                Text("−${view.removed}", color = Palette.Danger, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChangeBoxButton(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp).testTag(tag),
    )
}
