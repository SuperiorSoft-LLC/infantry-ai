// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord

/** Test tag prefix on a worker card's runner badge (the session id follows). */
const val RUNNER_BADGE_TAG_PREFIX = "runner-badge-"

/** RUNNER-POOL-4: what a runner badge shows: the runner name and its health word (`up` / `degraded` / `offline`). */
internal data class RunnerBadgeModel(val runner: String, val health: String)

/**
 * RUNNER-POOL-4 (docs/RUNNER-POOL.md 6): the badge for [session], or null. Null with one runner (every session on the same
 * runner), so the single-machine card composes no new element. Health is read from the session frames of that runner:
 * `up` = a live session there, `degraded` = a live one but another failed or was orphaned, `offline` = none live.
 */
internal fun runnerBadgeFor(session: SessionRecord, all: List<SessionRecord>): RunnerBadgeModel? {
    if (all.map { it.runner }.plus(session.runner).distinct().size < 2) return null
    val onRunner = all.filter { it.runner == session.runner }
    val live = onRunner.any { it.state != SessionState.EXITED && it.state != SessionState.FAILED && it.state != SessionState.ORPHANED }
    val bad = onRunner.any { it.state == SessionState.FAILED || it.state == SessionState.ORPHANED }
    return RunnerBadgeModel(session.runner, if (!live) "offline" else if (bad) "degraded" else "up")
}

/** Drawn by the board card (this plugin), not by the host. */
@Composable
internal fun RunnerBadge(badge: RunnerBadgeModel, sessionId: String) {
    val color = when (badge.health) {
        "up" -> Palette.Ok
        "degraded" -> Palette.Warn
        else -> Palette.Danger
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(RUNNER_BADGE_TAG_PREFIX + sessionId)) {
        Text("● ${badge.runner}", color = color, fontSize = 10.sp)
    }
}
