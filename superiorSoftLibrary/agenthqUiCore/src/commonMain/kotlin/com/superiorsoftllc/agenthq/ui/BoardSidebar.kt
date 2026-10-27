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

// S4b (docs/SHELL-LEAKS.md): what the sidebar and the chat (core until S4c) draw of the board, which moved to the app UI.

const val SIDEBAR_BOARD_BUTTON_TAG = "sidebar-board-button"

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

/** PIPELINE-WATCHDOG: the watchdog flag; urgent states in the attention colour. */
// S4 bridge (docs/SHELL-LEAKS.md): public only until the sidebar (App.kt) moves to the app UI (S4c); the board and Infantry_Attention use it.
@Composable
fun WatchFlagChip(flag: WatchFlag?, modifier: Modifier = Modifier) {
    if (flag != null) Chip(flag.label, if (flag.urgent) Palette.Accent else Palette.Info, modifier)
}

/** TRAITS-T9: the REPORT BACK flag; `unreported` in the attention colour, `reported` muted, nothing otherwise. */
// S4 bridge (docs/SHELL-LEAKS.md): public only until the sidebar and the chat move (S4c); the board uses it.
@Composable
fun ReportFlagChip(flag: ReportFlag?, modifier: Modifier = Modifier) {
    when (flag) {
        ReportFlag.UNREPORTED -> Chip(flag.label, Palette.Accent, modifier)
        ReportFlag.REPORTED -> Chip(flag.label, Palette.Muted, modifier)
        null -> Unit
    }
}
