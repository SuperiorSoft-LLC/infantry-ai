package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AssistantEntryStyle

/** ASSISTANT-1: the top-bar entry's tag and the Assistant's name (RUNTIME-20: core, as the official Infantry_Header draws the entry). */
const val TITLE_BAR_ASSISTANT_TAG = "title-bar-assistant"
const val ASSISTANT_NAME = "Assistant"

/**
 * ASSISTANT-1: the Assistant's entry in the team bar. A pill (not a team portrait): the avatar, a 1dp accent border and an
 * "Assistant" label (dropped in a compact bar, the border kept). It fits the 22dp portrait row, so the bar never grows.
 */
@Composable
fun AssistantEntry(open: Boolean, needsInput: Boolean, onToggle: () -> Unit) {
    val border = if (open) Palette.Text else Palette.Accent
    val showLabel = AssistantEntryStyle.showLabel(LocalTitleBarCompact.current)
    IconActionTooltipBox(ASSISTANT_NAME, modifier = Modifier.padding(end = 4.dp).testTag(TITLE_BAR_ASSISTANT_TAG)) { m ->
        Row(
            m.clip(CircleShape).border(1.dp, border, CircleShape).padding(start = 1.dp, end = if (showLabel) 8.dp else 1.dp)
                .background(if (needsInput) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = if (open) "Close the Assistant" else "Open the Assistant", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar("assistant", ASSISTANT_NAME, 22)
            if (showLabel) {
                Spacer(Modifier.width(5.dp))
                Text(ASSISTANT_NAME, color = Palette.Text, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
