// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TabBar

/** SETTINGS-TAB-1: the sub-tab strip of a host tab; the suffix is the host's id. */
const val SUB_TAB_BAR_TAG_PREFIX = "sub-tab-bar-"
/** SETTINGS-TAB-1: the line a host tab shows when none of its sub-tabs is loaded or switched on; suffix: the host id. */
const val SUB_TAB_EMPTY_TAG_PREFIX = "sub-tab-empty-"

/**
 * SETTINGS-TAB-1: what a host tab draws: its sub-tabs ([TabFactory.children], in the host's stored order, without the
 * disabled ones) as a strip, then the [picked] one's content. Stateless: the caller owns which sub-tab is picked
 * ([onPick]); a [picked] that is not a sub-tab now falls back to the first. With no sub-tab it says so.
 */
@Composable
fun SubTabHost(factory: TabFactory, hostId: String, context: TabContext, picked: String?, onPick: (String) -> Unit) {
    val kids = factory.children(hostId)
    if (kids.isEmpty()) {
        Text(
            "Nothing is grouped here: the tabs that belong under this one are off or their modules did not load.",
            color = Palette.Muted, fontSize = 12.sp,
            modifier = Modifier.padding(16.dp).testTag(SUB_TAB_EMPTY_TAG_PREFIX + hostId),
        )
        return
    }
    val current = kids.firstOrNull { it.id == picked } ?: kids.first()
    Column(Modifier.fillMaxSize()) {
        TabBar(
            kids.map { factory.title(it, context.state) to (it.id == current.id) },
            hPad = 12.dp,
            fontSize = 12.sp,
            tagOf = { kids[it].testTag },
            barTag = SUB_TAB_BAR_TAG_PREFIX + hostId,
        ) { onPick(kids[it].id) }
        Box(Modifier.fillMaxWidth().weight(1f)) { factory.Content(current.id, context) }
    }
}
