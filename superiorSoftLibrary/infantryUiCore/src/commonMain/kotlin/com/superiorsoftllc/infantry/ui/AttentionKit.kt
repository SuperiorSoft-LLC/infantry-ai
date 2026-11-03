// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.model.WatchFlag
import com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView

// RUNTIME-13: kit, out of Infantry_AppUi, since the official Infantry_Attention jar draws them too (the sidebar and the
// question window still do).

/** ASK-1: who asked, as the window shows it: the role and the agent (or the session when there is no agent). */
fun questionAsker(q: OwnerQuestionView): String {
    val who = q.agentId?.takeIf { it.isNotBlank() } ?: "session ${q.sessionId.take(8)}"
    return listOfNotNull(q.role?.takeIf { it.isNotBlank() }, who).joinToString(" · ")
}

/** PIPELINE-WATCHDOG: the watchdog flag; urgent states in the attention colour. */
@Composable
fun WatchFlagChip(flag: WatchFlag?, modifier: Modifier = Modifier) {
    if (flag != null) Chip(flag.label, if (flag.urgent) Palette.Accent else Palette.Info, modifier)
}
