// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.EffortChip
import com.superiorsoftllc.infantry.ui.model.EffortLevels

const val EFFORT_CHIP_TAG = "effort-chip"
const val EFFORT_LEVEL_TAG_PREFIX = "effort-level-"

/** The tags of one [EffortField], so each form (New agent, ticket editor) keeps its own. */
data class EffortTags(val switch: String, val note: String)

/**
 * EFFORT-1: the reasoning effort control shared by the New agent dialog and the ticket forms. Off (the default) = the
 * harness's own default; on, one button per level from [EffortLevels.ALL] (the picked one is ticked, not only coloured).
 * [note] is the chosen harness's support line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffortField(
    on: Boolean, level: String?, note: String, tags: EffortTags,
    onOn: (Boolean) -> Unit, onLevel: (String) -> Unit,
    textColor: Color = AlertDialogDefaults.textContentColor, noteColor: Color = Palette.Muted,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(on, onOn, modifier = Modifier.testTag(tags.switch))
            Text("Set the reasoning effort (unchecked = the harness default)", fontSize = 12.sp, color = textColor)
        }
        if (on) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EffortLevels.ALL.forEach { l ->
                    val picked = l.id == level
                    Chip(
                        if (picked) "✓ ${l.label}" else l.label, if (picked) Palette.Accent else Palette.Muted,
                        Modifier.testTag(EFFORT_LEVEL_TAG_PREFIX + l.id).semantics { selected = picked }.clickable { onLevel(l.id) },
                    )
                }
            }
        }
        Text(note, color = noteColor, fontSize = 11.sp, modifier = Modifier.testTag(tags.note))
    }
}

/** EFFORT-1: the `effort high` chip with its tooltip (where the value came from, and the how). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffortChipView(chip: EffortChip, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(chip.tooltip, fontSize = 11.sp) } },
        state = rememberTooltipState(),
    ) {
        Chip(
            chip.text, if ("not applied" in chip.text) Palette.Danger else Palette.Info,
            modifier = modifier.testTag(EFFORT_CHIP_TAG).semantics { contentDescription = chip.tooltip },
        )
    }
}
