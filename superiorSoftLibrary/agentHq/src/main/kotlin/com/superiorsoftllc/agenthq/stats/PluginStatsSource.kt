package com.superiorsoftllc.agenthq.stats

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
