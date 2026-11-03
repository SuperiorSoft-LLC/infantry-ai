package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ColumnAlign
import com.superiorsoftllc.infantry.ui.model.parseChatInline

/** Narrowest a column gets before the whole table scrolls sideways instead of squeezing further. */
private const val MIN_COLUMN_DP = 72

/** Column share of the width: the longest cell of the column, bounded so one long column cannot starve the rest. */
internal fun tableColumnWeights(table: ChatBlock.Table): List<Float> =
    table.header.indices.map { c ->
        val longest = (listOf(table.header) + table.rows).maxOf { it.getOrElse(c) { "" }.length }
        longest.coerceIn(6, 40).toFloat()
    }

internal fun ColumnAlign.textAlign(): TextAlign = when (this) {
    ColumnAlign.NONE, ColumnAlign.START -> TextAlign.Start
    ColumnAlign.CENTER -> TextAlign.Center
    ColumnAlign.END -> TextAlign.End
}

/**
 * A bordered grid for a [ChatBlock.Table]: thin borders, bold tinted header row, per-column alignment, wrapping cells,
 * selectable text. It fills the width and only scrolls sideways when the columns cannot fit at [MIN_COLUMN_DP] each.
 */
@Composable
fun ChatTable(table: ChatBlock.Table, modifier: Modifier = Modifier) {
    val cols = table.header.size
    if (cols == 0) return
    val weights = tableColumnWeights(table)
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(4.dp)
    SelectionContainer {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val minWidth = (cols * MIN_COLUMN_DP).dp
            val gridModifier = if (minWidth > maxWidth) Modifier.horizontalScroll(scroll).width(minWidth) else Modifier.fillMaxWidth()
            Column(gridModifier.clip(shape).border(1.dp, Palette.Border, shape)) {
                for ((r, row) in (listOf(table.header) + table.rows).withIndex()) {
                    if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Border))
                    val tint = if (r == 0) Modifier.background(Palette.Accent.copy(alpha = 0.15f).compositeOver(Palette.Panel)) else Modifier
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(tint)) {
                        for (c in 0 until cols) {
                            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))
                            Text(
                                spansText(parseChatInline(row.getOrElse(c) { "" })),
                                color = Palette.Text,
                                fontFamily = Mono,
                                fontSize = 12.sp,
                                fontWeight = if (r == 0) FontWeight.Bold else null,
                                textAlign = table.alignments.getOrElse(c) { ColumnAlign.NONE }.textAlign(),
                                modifier = Modifier.weight(weights[c]).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
