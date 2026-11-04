// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * CHAT-MINIMIZE-5: floating chips one plugin hands another (e.g. through slot args) to draw in its float row. A typed holder,
 * because a bare `@Composable` lambda does not survive a cast back from `Any?`.
 */
class FloatingChips(val content: @Composable RowScope.() -> Unit)

/** CHAT-MINIMIZE-5: the visible disc of a floating chip (dp); its hit area is [FLOATING_CHIP_TOUCH_DP]. */
const val FLOATING_CHIP_DP = 36

/** CHAT-MINIMIZE-5: a floating chip's touch target (dp); 48 keeps Android's minimum. */
const val FLOATING_CHIP_TOUCH_DP = 48

/** CHAT-MINIMIZE-5: the glyph inside a floating chip (dp). */
const val FLOATING_CHIP_ICON_DP = 18

/**
 * CHAT-MINIMIZE-5: a small round button that floats over content (the chat's Activity, Expand, Minimize, Restore):
 * a [FLOATING_CHIP_DP] disc on the panel tone with a thin theme border, a muted glyph, a hover tone and the hand cursor,
 * inside a [FLOATING_CHIP_TOUCH_DP] hit area. [label] is its tooltip and screen-reader name; [modifier] carries the tag.
 */
@Composable
fun FloatingChip(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, shortcut: String? = null) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    IconActionTooltipBox(label, shortcut, modifier.size(FLOATING_CHIP_TOUCH_DP.dp)) { m ->
        Box(m.hoverable(hover).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(FLOATING_CHIP_DP.dp).clip(CircleShape).background(if (hovered) Palette.Raised else Palette.Panel)
                    .border(1.dp, Palette.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (hovered) Palette.Text else Palette.Muted, modifier = Modifier.size(FLOATING_CHIP_ICON_DP.dp)) }
        }
    }
}
