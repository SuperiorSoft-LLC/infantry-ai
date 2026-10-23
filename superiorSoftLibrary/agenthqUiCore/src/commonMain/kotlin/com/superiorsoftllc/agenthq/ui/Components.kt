package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.protocol.SessionState

@Composable
fun Chip(text: String, color: Color = Palette.Muted, modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { Text(text, color = color, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = textModifier) }
}

/** ROLE-ICON-1: a [Chip] naming a role, its icon ([RoleIcon]) ahead of the text. [icon] is the role's stored key. */
@Composable
fun RoleChip(text: String, roleId: String, icon: String?, site: String, color: Color = Palette.Muted, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        RoleIcon(roleId, icon, site, size = 11, tint = color)
        Text(text, color = color, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** ROLE-PILL-ICONS-1: a [Chip] naming a harness, its [HarnessIcon] ahead of the id. */
@Composable
fun HarnessChip(harnessId: String, color: Color = Palette.Muted, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        HarnessIcon(harnessId, size = 11, tint = color)
        Text(harnessId, color = color, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

fun stateColor(state: SessionState, needsInput: Boolean): Color = when {
    needsInput -> Palette.Accent
    state == SessionState.BUSY || state == SessionState.STARTING -> Palette.Info
    state == SessionState.IDLE -> Palette.Ok
    state == SessionState.FAILED -> Palette.Danger
    else -> Palette.Muted
}

fun ago(then: Long, now: Long): String {
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
