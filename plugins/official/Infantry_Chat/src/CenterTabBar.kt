package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers

const val CENTER_TAB_BAR_TAG = "center-tab-bar"

/**
 * The center pane's tab strip: the [LocalTabFactory]'s centre tabs in order; [current] is a tab id; [modifier] goes on the
 * strip's row (BLOCKS-LAYOUT L9b: the placement of `infantry.shell.center.chat.tabs`). Public so UI tests can read the labels.
 */
@Composable
fun CenterTabBar(state: AppState, current: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val factory = LocalTabFactory.current
    val strip = factory.tabs(TabContainers.CENTER)
    TabBar(
        strip.map { factory.title(it, state) to (current == it.id) },
        tagOf = { strip[it].testTag },
        barTag = CENTER_TAB_BAR_TAG,
        modifier = modifier,
    ) { onPick(strip[it].id) }
}

