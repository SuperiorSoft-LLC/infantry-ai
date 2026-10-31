package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * USAGE-UI-1: a labelled segmented control. The [label] names what the control changes; the selected segment is filled
 * with the accent and exposes `selected` to accessibility. Segments are at least 32dp high.
 */
@Composable
fun <T> SegmentedControl(label: String, options: List<T>, selected: T, text: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        val shape = RoundedCornerShape(6.dp)
        Row(Modifier.clip(shape).border(1.dp, Palette.Border, shape)) {
            for (o in options) {
                val on = o == selected
                Box(
                    Modifier.heightIn(min = 32.dp).widthIn(min = 64.dp)
                        .background(if (on) Palette.Accent else Palette.Raised)
                        .clickable(onClick = { onSelect(o) })
                        .semantics { role = Role.RadioButton; this.selected = on }
                        .padding(horizontal = 12.dp),
                    Alignment.Center,
                ) {
                    Text(
                        text(o), color = if (on) Contrast.onColor(Palette.Accent) else Palette.Text, fontSize = 12.sp, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** USAGE-UI-1: a small stat: muted [label] over a [value] in the text colour. */
@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color = Palette.Text) {
    Column(modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, color = Palette.Muted, fontSize = 10.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** USAGE-UI-1: a right-aligned numeric cell of fixed [width]. */
@Composable
fun NumericText(text: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Palette.Text, fontSize: Int = 11) {
    Text(text, color = color, fontSize = fontSize.sp, fontFamily = Mono, textAlign = TextAlign.End, maxLines = 1, modifier = modifier.width(width))
}
