// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.stats

import com.superiorsoftllc.infantry.harness.api.HarnessStatEvent

/** Folds a plugin's ordered record through the accumulator every harness uses; [limitOf] states a model's window from a local catalog. */
internal fun SessionStats.withPluginEvents(events: List<HarnessStatEvent>, limitOf: (String?) -> Long?): SessionStats {
    val acc = StatsAccumulator()
    for (e in events) when (e) {
        is HarnessStatEvent.Seen -> acc.seen(e.at)
        is HarnessStatEvent.Prompt -> acc.prompt()
        is HarnessStatEvent.Turn -> acc.turn(e.model)
        is HarnessStatEvent.Request -> acc.request(RequestUsage(e.at, e.model, e.input, e.output, e.cacheRead, e.cacheWrite, e.cost))
        is HarnessStatEvent.AssistantAt -> acc.assistantAt(e.at)
        is HarnessStatEvent.Tool -> acc.tool(e.name, e.at)
        is HarnessStatEvent.ToolResult -> acc.toolResult(e.chars, e.at)
    }
    return acc.build(this, limitOf(acc.lastModel))
}

/** HARNESS H5d: one session's stats as a plugin harness read them from its own store; null fields = not stated. */
data class PluginStatsRow(
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val userPrompts: Int? = null,
    val assistantTurns: Int? = null,
    val toolUses: Int? = null,
    val models: List<String>? = null,
    val lastContextTokens: Long? = null,
    val billedTokens: Long? = null,
    val costTotal: Double? = null,
    val events: List<HarnessStatEvent>? = null,
)

/** HARNESS H5d: reads a plugin harness's row; must not throw (the adapter attributes a plugin's throw and returns null). */
fun interface PluginStatsSource {
    fun read(providerSessionId: String): PluginStatsRow?
}

internal fun SessionStats.withPlugin(r: PluginStatsRow): SessionStats = copy(
    startedAt = r.startedAt, endedAt = r.endedAt,
    durationMs = if (r.startedAt != null && r.endedAt != null) r.endedAt - r.startedAt else null,
    userPrompts = r.userPrompts, assistantTurns = r.assistantTurns, toolUses = r.toolUses, models = r.models,
    lastContextTokens = r.lastContextTokens, billedTokens = r.billedTokens, costTotal = r.costTotal,
)
