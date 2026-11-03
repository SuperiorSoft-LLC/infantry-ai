// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.DiffLineKind
import com.superiorsoftllc.infantry.ui.model.DiffRow

// S4c: the diff row stays in core (the chat's change box draws it); the diff modal around it is app UI (Infantry_AppUi).
const val DIFF_LINE_ADD_TAG = "diff-line-add"
const val DIFF_LINE_DEL_TAG = "diff-line-del"
const val DIFF_LINE_HUNK_TAG = "diff-line-hunk"

/**
 * One diff row as the Changes panel draws it: the add/remove/hunk band, the gutters, the line. Shared with the chat's
 * change box (UX-WRITE-1), which passes [oldGutter] false for a created file (there is no old side to number).
 */
@Composable
fun DiffLine(row: DiffRow, oldGutter: Boolean = true) {
    val (bg, fg, tag) = when (row.kind) {
        DiffLineKind.ADD -> Triple(Palette.Ok.copy(alpha = 0.14f), Palette.Ok, DIFF_LINE_ADD_TAG)
        DiffLineKind.DEL -> Triple(Palette.Danger.copy(alpha = 0.14f), Palette.Danger, DIFF_LINE_DEL_TAG)
        DiffLineKind.HUNK -> Triple(Palette.Raised, Palette.Muted, DIFF_LINE_HUNK_TAG)
        DiffLineKind.META -> Triple(Color.Transparent, Palette.Muted, "diff-line-meta")
        DiffLineKind.CONTEXT -> Triple(Color.Transparent, Palette.Text, "diff-line-context")
    }
    Row(Modifier.fillMaxWidth().background(bg).testTag(tag).semantics(mergeDescendants = true) {}) {
        if (oldGutter) Gutter(row.oldNo)
        Gutter(row.newNo)
        Text(row.text.ifEmpty { " " }, color = fg, fontFamily = Mono, fontSize = 12.sp, softWrap = false, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
    }
}

@Composable
private fun Gutter(n: Int?) = Text(
    n?.toString() ?: "", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1,
    modifier = Modifier.width(44.dp).padding(end = 6.dp),
)

