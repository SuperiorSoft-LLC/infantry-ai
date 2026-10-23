package com.superiorsoftllc.agenthq.stats

import com.superiorsoftllc.agenthq.protocol.StrictJson
import kotlinx.serialization.Serializable

/**
 * STATS-1 (contract 1.92.0): per-session statistics read from the harness's OWN local session record
 * (Claude Code's jsonl, Pi's jsonl, OpenCode's sqlite), never from the network and never written back.
 *
 * Every field a harness does not expose is null. Nothing here is estimated: a null means "this harness's
 * record does not say", not zero. Token fields are the harness's own reported counts.
 *
 * - [contextTokens] of a request = input + cache read + cache write as that harness reports them.
 * - billed tokens of a request = input + output + cache read + cache write (reasoning is not added: harnesses
 *   disagree on whether output already includes it, so it is never double-counted).
 */
@Serializable
data class SessionStats(
    /** The AgentHQ session id. */
    val sessionId: String,
    /** The harness id (`claude`, `pi`, `opencode`). */
    val harness: String,
    /** The harness's own session id, null until the CLI revealed it. */
    val providerSessionId: String? = null,
    /** The daemon's view of the session (`SessionState`, lowercase); harness records carry no live status. */
    val status: String? = null,
    /** Epoch millis of the first and last record in the harness's session record. */
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val userPrompts: Int? = null,
    val assistantTurns: Int? = null,
    val toolUses: Int? = null,
    /** Distinct model ids, in first-seen order. */
    val models: List<String>? = null,
    /** Context tokens of the most recent request. */
    val lastContextTokens: Long? = null,
    /** The model's context window, ONLY when a local catalog states it. Null for Claude Code (nothing on disk says). */
    val modelContextLimit: Long? = null,
    /** Running total of billed tokens over the whole session. */
    val billedTokens: Long? = null,
    /** One point per model request, in order. */
    val series: List<StatsPoint>? = null,
    /** Tools by tokens added, largest first. */
    val topSources: List<ContextSource>? = null,
    /**
     * Context growth that followed a step running MORE than one tool: it cannot be split between them
     * without guessing, so it is reported here instead of being divided.
     */
    val unattributedToolTokens: Long? = null,
)

@Serializable
data class StatsPoint(
    /** Epoch millis of the request (null when the record gives no time). */
    val at: Long? = null,
    val contextTokens: Long,
    val cumulativeBilledTokens: Long,
)

@Serializable
data class ContextSource(
    val tool: String,
    val calls: Int,
    /**
     * Sum of context growth after steps where this was the ONLY tool run. Null when no such step exists
     * (every call ran beside another tool, or had no following request).
     */
    val tokensAdded: Long? = null,
)

/** The `session_stats_get` request payload. */
@Serializable
data class SessionStatsRequest(val sessionId: String? = null) {
    companion object {
        fun decode(text: String): SessionStatsRequest =
            StrictJson.decode(serializer(), text, "session_stats_get payload")
    }
}
