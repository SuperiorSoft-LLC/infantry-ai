// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRequest

const val PACE_SESSIONS_TAG = "pace-sessions"
const val PACE_COMPARE_TAG = "pace-compare"

fun lookbackLabel(h: Double): String = if (h < 1.0) "${(h * 60).toInt()}m" else "${h.toInt()}h"

/** PACE-TREND-2: who is spending now (ranked per provider, with share and an ESTIMATED meter contribution) and the harness/model comparison. */
@Composable
fun PaceSessionsPanel(requests: List<UsageMeterRequest>?, series: List<PaceSeries>, now: Long) {
    var lookback by remember { mutableStateOf(PaceSessions.DEFAULT_LOOKBACK_HOURS) }
    val groups = remember(requests, series, now, lookback) { PaceSessions.groups(requests.orEmpty(), series, now, lookback) }
    val compare = remember(requests) { PaceSessions.compare(requests.orEmpty()) }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape).padding(12.dp).testTag(PACE_SESSIONS_TAG)) {
        Row {
            Text("SESSIONS", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(end = 10.dp, top = 4.dp))
            for (h in PaceSessions.LOOKBACKS) Chip(lookbackLabel(h), lookback == h, "pace-lookback-${lookbackLabel(h)}") { lookback = h }
        }
        Text(
            "Tokens each session spent in the last ${lookbackLabel(lookback)}, from the daemon's own per-request records (input includes cache read and write). " +
                "The provider meter is account-wide and cannot say which session caused a rise. Share = the session's part of its provider's tokens in this window.",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        if (groups.isEmpty()) Text("No session spent tokens in the last ${lookbackLabel(lookback)}.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag("pace-sessions-empty"))
        for (g in groups) {
            Text("${g.meter}: ${PaceTokens.compact(g.total)} tokens", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text(
                g.estimate.pointsPerMillion?.let {
                    "Estimate: about ${PaceChartModel.one(it)} meter points per 1M tokens (${PaceChartModel.one(g.estimate.meterDelta ?: 0.0)} points on the ${g.estimate.window} meter / ${PaceTokens.compact(g.estimate.tokens)} tokens in the window). " +
                        "Account-wide, so other use of the plan is included: an estimate, not a measurement."
                } ?: "No estimate: ${g.estimate.reason}",
                color = Palette.Muted, fontSize = 10.sp,
            )
            for (s in g.sessions) {
                val est = s.estimatePoints?.let { " · est. ~${PaceChartModel.one(it)} pts" }.orEmpty()
                Text(
                    "${if (s.active) "● active" else "○ ended"}  ${s.ticketId ?: "no ticket"} · ${s.role} · ${s.model ?: "default"} · ${s.harness}  " +
                        "${PaceTokens.compact(s.tokens)} tokens (in ${PaceTokens.compact(s.input)}, out ${PaceTokens.compact(s.output)}) · ${s.turns} turns · " +
                        "${PaceTokens.compact(s.perHour.toLong())}/h · ${PaceSessions.percent(s.share)} of ${g.meter}$est",
                    color = Palette.Text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp).horizontalScroll(rememberScrollState()),
                )
            }
        }
        Text("COMPARE", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Average per run (one session in the kept records) by harness and model. Effort is not in the per-request records yet, so it is not a grouping.",
            color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp),
        )
        if (compare.isEmpty()) Text("No runs recorded yet.", color = Palette.Muted, fontSize = 11.sp)
        Column(Modifier.testTag(PACE_COMPARE_TAG)) {
            for (c in compare) Text(
                "${c.harness} · ${c.model}: ${PaceTokens.compact(c.avgTokens)} tokens, ${PaceChartModel.one(c.avgTurns)} turns per run (${c.sessions} ${if (c.sessions == 1) "run" else "runs"})",
                color = Palette.Text, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
