package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ContextLimitChip
import com.superiorsoftllc.infantry.ui.model.ContextLimitPresets
import com.superiorsoftllc.infantry.ui.model.formatContextLimit

const val CONTEXT_LIMIT_CHIP_TAG = "context-limit-chip"
const val CONTEXT_LIMIT_PRESET_TAG_PREFIX = "context-limit-preset-"

/** The tags of one [ContextLimitField], so each form (New agent, ticket editor) keeps its own. */
data class ContextLimitTags(val switch: String, val field: String, val note: String)

/**
 * CONTEXT-LIMIT-2: the context limit control shared by the New agent dialog and the ticket forms. Off (the default) = the
 * harness's own window; on, a size field with preset buttons (200k / 500k / 1M). [note] is the chosen harness's support line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextLimitField(
    on: Boolean, text: String, note: String, error: Boolean, tags: ContextLimitTags,
    onOn: (Boolean) -> Unit, onText: (String) -> Unit,
    textColor: Color = AlertDialogDefaults.textContentColor, noteColor: Color = Palette.Muted,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(on, onOn, modifier = Modifier.testTag(tags.switch))
            Text("Limit the context size (unchecked = the harness default)", fontSize = 12.sp, color = textColor)
        }
        if (on) {
            OutlinedTextField(
                text, onText, placeholder = { Text("200k, 1m or 150000") }, singleLine = true, isError = error,
                modifier = Modifier.fillMaxWidth().testTag(tags.field), colors = visibleBorderFieldColors(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ContextLimitPresets.ALL.forEach { n ->
                    val label = formatContextLimit(n)
                    Chip(label, Palette.Accent, Modifier.testTag(CONTEXT_LIMIT_PRESET_TAG_PREFIX + label.lowercase()).clickable { onText(label) })
                }
            }
        }
        Text(note, color = noteColor, fontSize = 11.sp, modifier = Modifier.testTag(tags.note))
    }
}

/** CONTEXT-LIMIT-2: the `limit 1M` chip with its tooltip (where the value came from, and the how). A live meter later fills the same chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLimitChipView(chip: ContextLimitChip, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(chip.tooltip, fontSize = 11.sp) } },
        state = rememberTooltipState(),
    ) {
        Chip(
            chip.text, if ("not applied" in chip.text) Palette.Danger else Palette.Info,
            modifier = modifier.testTag(CONTEXT_LIMIT_CHIP_TAG).semantics { contentDescription = chip.tooltip },
        )
    }
}
