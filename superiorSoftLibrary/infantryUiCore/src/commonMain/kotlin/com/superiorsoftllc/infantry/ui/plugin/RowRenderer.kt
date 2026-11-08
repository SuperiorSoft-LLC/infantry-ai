// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.model.ChatItem

/**
 * KINDS-1: one row of a list a host draws by kind. [kind] is the item's kind string (see [ChatItem.kind]); [agentId],
 * [agentName], [agentRole] and [roleColor] describe the session the list belongs to.
 */
class RowItem(
    val item: ChatItem,
    val agentId: String,
    val agentName: String,
    val agentRole: String?,
    val roleColor: String?,
    /** AVATAR-SEAM-2: the session's state name when the row's session is in the app state, else null. */
    val sessionState: String? = null,
) {
    val kind: String get() = item.kind
}

/**
 * KINDS-1: the drawer of one kind of row. A plugin registers it with `UiRegistrar.implementation(registry, kind, renderer)`
 * (and declares it under `implementations` in module.json); the host that draws the list owns the registry name. [agentSide]
 * rows sit on the agent's side of the list (the host adds the agent's portrait to the first of a run); others draw as they are.
 */
class RowRenderer(val agentSide: Boolean = false, val content: @Composable (RowItem) -> Unit)
