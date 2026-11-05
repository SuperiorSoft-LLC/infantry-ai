// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.runtime.mutableStateListOf

/** Test tag prefix on a collapsed lane's strip (the lane id follows). */
const val BOARD_LANE_STRIP_TAG_PREFIX = "board-lane-strip-"
/** Test tag prefix on an expanded lane's collapse button (the lane id follows). */
const val BOARD_LANE_COLLAPSE_TAG_PREFIX = "board-lane-collapse-"

/**
 * LANE-COLLAPSE-1: which board lanes are collapsed to a strip, per project. A UI preference, not ticket data: it is
 * kept through [load] / [save] (the host's ui prefs, restart-safe) under one key per project. Default: none collapsed.
 * Lane ids are open strings, so a user-defined lane collapses the same way.
 */
internal class LaneCollapse(
    projectId: String?,
    private val load: (String) -> String?,
    private val save: (String, String) -> Unit,
) {
    private val key = "board.lanes.collapsed.${projectId ?: "_all"}"
    private val collapsed = mutableStateListOf<String>().apply {
        load(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.let { addAll(it) }
    }

    fun isCollapsed(lane: String): Boolean = lane in collapsed

    fun toggle(lane: String) {
        if (!collapsed.remove(lane)) collapsed.add(lane)
        save(key, collapsed.joinToString(","))
    }

    /** The collapsed lanes now, for the drag (a drop on a strip goes to the lane's end). */
    fun snapshot(): Set<String> = collapsed.toSet()
}
