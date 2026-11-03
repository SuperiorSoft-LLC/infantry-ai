// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.AgentWatchView

/**
 * PIPELINE-WATCHDOG: the watchdog state a sidebar row and the Attention tab show. Only states that need a human eye have a
 * flag: WORKING and IDLE (nothing pending) show none, so "idle but should be doing something" reads differently from "idle".
 */
enum class WatchFlag(val label: String, val urgent: Boolean) {
    STALLED("stalled", true),
    UNREPORTED("unreported", true),
    QUIET("quiet", true),
    WAITING("waiting", false),
}

fun watchFlagOf(watch: AgentWatchView?): WatchFlag? = watch?.let { w -> WatchFlag.entries.firstOrNull { it.name == w.state } }
