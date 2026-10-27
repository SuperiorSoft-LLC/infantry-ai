package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ReportFlag
import com.superiorsoftllc.agenthq.ui.model.WatchFlag
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns

// S4b (docs/SHELL-LEAKS.md): what the chat (core) draws of the board, which moved to the app UI. Since S4c (the
// sidebar moved) only the REPORT BACK flag is left.

/** TRAITS-T9: the REPORT BACK flag; `unreported` in the attention colour, `reported` muted, nothing otherwise. */
// S4 bridge (docs/SHELL-LEAKS.md): public only until the chat (ChatSurface.kt) moves; the board and the sidebar use it.
@Composable
fun ReportFlagChip(flag: ReportFlag?, modifier: Modifier = Modifier) {
    when (flag) {
        ReportFlag.UNREPORTED -> Chip(flag.label, Palette.Accent, modifier)
        ReportFlag.REPORTED -> Chip(flag.label, Palette.Muted, modifier)
        null -> Unit
    }
}
