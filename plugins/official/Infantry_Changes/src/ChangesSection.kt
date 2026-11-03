// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.changes

import com.superiorsoftllc.infantry.ui.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.superiorsoftllc.infantry.ui.model.ChangesView
import com.superiorsoftllc.infantry.ui.model.badge
import com.superiorsoftllc.infantry.ui.model.display
import com.superiorsoftllc.infantry.ui.model.label
import com.superiorsoftllc.infantry.ui.model.splitExtension
import com.superiorsoftllc.infantry.ui.model.summary
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile

/**
 * GIT VIEW: the changed files of one agent's worktree, staged and unstaged apart. Presentation only --
 * every decision (unknown vs clean, the split, the ordering) is made by [ChangesView], which is tested.
 */
@Composable
fun ChangesSection(view: ChangesView) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (view) {
            // Unknown is stated as unknown. It is never drawn as "clean".
            is ChangesView.Unknown -> Text("Changes unknown: ${view.reason}", color = Palette.Muted, fontSize = 11.sp)
            ChangesView.Clean -> Text("Working tree clean. Nothing to commit.", color = Palette.Ok, fontSize = 11.sp)
            is ChangesView.Files -> {
                Text(view.summary(), color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                FileGroup("Staged", view.staged)
                FileGroup("Not staged", view.unstaged)
            }
        }
    }
}

@Composable
private fun FileGroup(title: String, files: List<ChangedFile>) {
    if (files.isEmpty()) return
    Text("$title (${files.size})", color = Palette.Muted, fontSize = 10.sp)
    files.forEach { f ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.status.badge(), color = changeKindColor(f.status), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
            ChangedPathText(f.display(), Palette.Text)
            Spacer(Modifier.width(2.dp))
            Text(f.status.label(), color = Palette.Muted, fontSize = 9.sp, maxLines = 1, softWrap = false)
        }
    }
}
