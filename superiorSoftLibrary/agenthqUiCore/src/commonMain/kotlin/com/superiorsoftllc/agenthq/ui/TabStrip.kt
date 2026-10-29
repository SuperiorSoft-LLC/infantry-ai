package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val TAB_BAR_MORE_CUE_TAG = "tab-bar-more-cue"
const val TAB_BAR_ARROW_LEFT_TAG = "tab-bar-arrow-left"
const val TAB_BAR_ARROW_RIGHT_TAG = "tab-bar-arrow-right"

/**
 * A tab strip (the centre strip and the right panel's). Scrolls sideways when the labels outgrow the width, so no tab
 * is ever squeezed or hidden. While it overflows, PLUGINS-3's arrows sit at either end (each disabled once there is
 * nothing left to scroll that way) and a fade over the right edge marks the tabs off it. Touch drag scrolls it, and a
 * vertical mouse wheel scrolls it sideways (all of it [ArrowScrollRow], UI-ARROWS-1). [modifier] goes first on the
 * strip's row (a block's placement, BLOCKS-LAYOUT L9a); the strip's divider under it does not wear it.
 */
@Composable
fun TabBar(
    tabs: List<Pair<String, Boolean>>,
    hPad: Dp = 14.dp,
    fontSize: TextUnit = 12.sp,
    tagOf: ((Int) -> String)? = null,
    barTag: String? = null,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    ArrowScrollRow(
        TAB_BAR_ARROW_LEFT_TAG, TAB_BAR_ARROW_RIGHT_TAG, modifier.background(Palette.Panel),
        leftLabel = "Scroll tabs left", rightLabel = "Scroll tabs right", contentTag = barTag,
        fade = Palette.Panel, fadeTag = TAB_BAR_MORE_CUE_TAG,
    ) {
        tabs.forEachIndexed { i, (label, on) ->
            Column(Modifier.let { m -> tagOf?.let { m.testTag(it(i)) } ?: m }.clickable { onPick(i) }.padding(horizontal = hPad, vertical = 7.dp)) {
                Text(label, maxLines = 1, softWrap = false, color = if (on) Palette.Accent else Palette.Muted, fontSize = fontSize, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    HorizontalDivider(color = Palette.Border)
}

