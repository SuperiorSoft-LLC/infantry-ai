package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.badge
import com.superiorsoftllc.agenthq.ui.model.display
import com.superiorsoftllc.agenthq.ui.model.label
import com.superiorsoftllc.agenthq.ui.model.splitExtension
import com.superiorsoftllc.agenthq.ui.protocol.ChangeKind
import com.superiorsoftllc.agenthq.ui.protocol.ChangedFile

// RUNTIME-6: the changed-file rows, out of Infantry_Changes into the kit, as both Infantry_Changes and the official
// Infantry_Git (its own jar) draw them (audit B6: shared pieces go to the kit, not across modules).

/**
 * A changed path on one line: the stem is cut with an ellipsis, the extension always stays visible
 * (`2026-10-10-koo….md`). The cell carries the full path as its text, so tests and screen readers get the whole
 * path rather than the truncated drawing.
 */
@Composable
fun RowScope.ChangedPathText(path: String, color: Color) {
    val (stem, ext) = splitExtension(path)
    Row(Modifier.weight(1f, fill = false).semantics { text = AnnotatedString(path) }) {
        Text(stem, color = color, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(ext, color = color, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

fun changeKindColor(kind: ChangeKind): Color = when (kind) {
    ChangeKind.ADDED, ChangeKind.UNTRACKED -> Palette.Ok
    ChangeKind.DELETED, ChangeKind.CONFLICTED -> Palette.Danger
    ChangeKind.MODIFIED, ChangeKind.RENAMED -> Palette.Accent
}

/** S4b: the toggle of a [ChangeGroup] that collapses past its limit; the group title follows. */
const val GIT_LIST_TOGGLE_TAG = "git-list-toggle-"

/** A group of changed files under its title; [row] (BLOCKS-LAYOUT L18: the Changes tab's file template) draws each file in place of [ChangedFileRow]. */
@Composable
fun ChangeGroup(
    title: String, files: List<ChangedFile>, staged: Boolean, picked: Pair<String, Boolean>?, collapseOver: Int = Int.MAX_VALUE,
    row: (@Composable (ChangedFile) -> Unit)? = null, onPick: ((ChangedFile) -> Unit)?,
) {
    if (files.isEmpty()) return
    val collapsible = files.size > collapseOver
    var expanded by remember(title, collapsible) { mutableStateOf(!collapsible) }
    Text(
        "$title (${files.size})${if (collapsible) if (expanded) " ▾" else " ▸" else ""}", color = Palette.Muted, fontSize = 10.sp,
        modifier = Modifier.padding(top = 4.dp).then(
            if (collapsible) Modifier.testTag(GIT_LIST_TOGGLE_TAG + title).clickable { expanded = !expanded } else Modifier,
        ),
    )
    if (!expanded) return
    files.forEach { f ->
        if (row != null) row(f) else ChangedFileRow(f, open = picked == (f.path to staged), onPick = onPick?.let { pick -> { pick(f) } })
    }
}

/** One changed file: its status badge, its path ([open] highlights it) and its status; [modifier] goes on the row. */
@Composable
fun ChangedFileRow(f: ChangedFile, open: Boolean, onPick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().then(if (onPick != null) Modifier.clickable { onPick() } else Modifier).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(f.status.badge(), color = changeKindColor(f.status), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
        ChangedPathText(f.display(), if (open) Palette.Accent else Palette.Text)
        Spacer(Modifier.width(2.dp))
        Text(f.status.label(), color = Palette.Muted, fontSize = 9.sp, maxLines = 1, softWrap = false)
    }
}
