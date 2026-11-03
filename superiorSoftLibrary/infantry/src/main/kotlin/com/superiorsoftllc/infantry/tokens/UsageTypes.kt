package com.superiorsoftllc.infantry.tokens

import kotlinx.serialization.Serializable

/**
 * The three ways to run one measured story. [MULTI_AGENT_ROOM] is a team of agents sharing one room, the baseline
 * the other two arms are compared against.
 */
@Serializable
enum class Arm {
    MULTI_AGENT_ROOM,
    SINGLE_AGENT,
    LEAD_RELAY,
}

@Serializable
data class UsageSample(
    val taskId: String,
    val arm: Arm,
    val provider: String,
    val model: String,
    val tokensIn: Long,
    val tokensOut: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val defects: Int = 0,
) {
    val wallClockMs: Long get() = endedAtMs - startedAtMs

    init {
        require(tokensIn >= 0) { "tokensIn must be non-negative" }
        require(tokensOut >= 0) { "tokensOut must be non-negative" }
        require(defects >= 0) { "defects must be non-negative" }
        require(endedAtMs >= startedAtMs) { "endedAtMs must be at or after startedAtMs" }
    }
}
