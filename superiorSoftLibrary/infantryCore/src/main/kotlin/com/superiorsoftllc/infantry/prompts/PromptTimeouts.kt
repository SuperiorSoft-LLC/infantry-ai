package com.superiorsoftllc.infantry.prompts

/**
 * ASK-TIMER (ticket c62e72a9, contract 1.139.0): what an unanswered prompt does when its timer runs out. A tool
 * approval resolves as [DENY] (the safe default) or [APPROVE] (`allow_once`, for unattended runs); a question always
 * resolves as no answer. Either way the asker is unblocked and told why, so a worker never stalls on the owner.
 */
enum class TimeoutAction(val wire: String) {
    DENY("deny"),
    APPROVE("approve");

    /** The decision a timed-out tool approval gets. */
    val decision: Decision get() = if (this == APPROVE) Decision.ALLOW_ONCE else Decision.DENY

    companion object {
        val WIRE: List<String> = entries.map { it.wire }
        fun parse(text: String?): TimeoutAction? = entries.firstOrNull { it.wire == text?.trim() }
    }
}

/** ASK-TIMER: the timer a prompt of one session gets. [timeoutMillis] 0 = no timer (the prompt waits for an answer). */
data class PromptTimeoutPolicy(val timeoutMillis: Long, val onTimeout: TimeoutAction = TimeoutAction.DENY)

/**
 * ASK-TIMER: a session's own timer settings, over the global `prompts.timeoutSec` / `prompts.onTimeout`; a null field
 * keeps the global value. [timeoutSec] 0 = no timer for that session.
 */
data class PromptTimeoutOverride(val timeoutSec: Int? = null, val onTimeout: TimeoutAction? = null) {
    /** [global] with this override's fields over it. */
    fun over(global: PromptTimeoutPolicy): PromptTimeoutPolicy = PromptTimeoutPolicy(
        timeoutMillis = timeoutSec?.let { it * 1_000L } ?: global.timeoutMillis,
        onTimeout = onTimeout ?: global.onTimeout,
    )
}

/** ASK-TIMER: what a tool approval came to, and whether the timer (not the owner) decided it. */
data class ApprovalOutcome(val decision: Decision, val timedOut: Boolean = false, val timeoutSec: Long = 0)
