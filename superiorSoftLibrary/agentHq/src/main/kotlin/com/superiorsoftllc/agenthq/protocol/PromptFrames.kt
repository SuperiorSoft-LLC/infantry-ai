package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.77.0 (PROMPT-1): the prompt frames. A harness asks the owner something mid-run (may this tool run? which
 * of these options?); the daemon shows it to every client as a card and suspends the asker until one answers.
 */
object PromptKinds {
    const val TOOL_APPROVAL = "tool_approval"
    const val QUESTION = "question"
}

/** The `decision` values of a `tool_approval` answer (and the `outcome` of its `prompt_resolved`). */
object PromptDecisions {
    const val ALLOW_ONCE = "allow_once"
    const val ALLOW_SESSION = "allow_session"
    /** PROMPT-6 (1.78.0): allow, and remember a rule for the asking session's project (see [ApprovalRule]). */
    const val ALLOW_ALWAYS = "allow_always"
    const val DENY = "deny"
    val ALL: List<String> = listOf(ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY)
}

/** One choice of a `question` prompt. */
@Serializable
data class PromptOption(val label: String, val description: String = "")

/**
 * `prompt_request` (daemon -> every client). [kind] is [PromptKinds.TOOL_APPROVAL] (with [tool] and [input], a short
 * summary of the call, never the full input) or [PromptKinds.QUESTION] (with [question], [options], [multiSelect],
 * [allowOther]). [createdAt] is epoch milliseconds.
 */
@Serializable
data class PromptRequest(
    val promptId: String,
    val sessionId: String,
    val kind: String,
    val createdAt: Long,
    val tool: String? = null,
    val input: String? = null,
    val question: String? = null,
    val options: List<PromptOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowOther: Boolean = false,
)

/**
 * `prompt_response` (client -> daemon). A `tool_approval` answer carries [decision] (one of [PromptDecisions.ALL]); a
 * `question` answer carries [answers] (option labels; at most one unless the prompt is `multiSelect`) and/or
 * [otherText] (only when the prompt `allowOther`). Anything else is refused.
 */
@Serializable
data class PromptResponse(
    val promptId: String,
    val decision: String? = null,
    val answers: List<String> = emptyList(),
    val otherText: String? = null,
) {
    companion object {
        fun decode(text: String): PromptResponse = StrictJson.decode(serializer(), text, "prompt_response payload")
    }
}

/**
 * `prompt_resolved` (daemon -> every client): the card for [promptId] closes. [resolvedBy] is `client:<n>` (the
 * connection that answered), [PromptResolvedBy.TIMEOUT], [PromptResolvedBy.SESSION_END],
 * [PromptResolvedBy.DAEMON_STOP] or [PromptResolvedBy.CANCELLED]. [outcome] is the decision for a tool approval, `answered` / `no_answer` for a
 * question.
 */
@Serializable
data class PromptResolved(val promptId: String, val resolvedBy: String, val outcome: String)

object PromptResolvedBy {
    const val TIMEOUT = "timeout"
    const val SESSION_END = "session_end"
    const val DAEMON_STOP = "daemon_stop"
    /** The asker itself went away (its run was stopped) before anyone answered. */
    const val CANCELLED = "cancelled"
}

object PromptOutcomes {
    const val ANSWERED = "answered"
    const val NO_ANSWER = "no_answer"
}

/**
 * Contract 1.78.0 (PROMPT-6): a remembered "Always allow", stored per project. A `tool_approval` prompt in [projectId]
 * for [tool] is answered allow with no card. [prefix] is set for a shell tool (`Bash`, `PowerShell`) only: the first
 * word of the command the rule was made from (`git`, `npm`); the rule then matches only a command with that first word
 * and no shell control operator (`;`, `&`, `|`, a backtick, `$(`, `<`, `>`, a line break). [createdAt] is epoch
 * milliseconds.
 */
@Serializable
data class ApprovalRule(
    val ruleId: String,
    val projectId: String,
    val tool: String,
    val prefix: String? = null,
    val createdAt: Long,
)

/** `approval_rules` (client -> daemon): list the rules of [projectId]; null or blank = every project. */
@Serializable
data class ApprovalRulesQuery(val projectId: String? = null) {
    companion object {
        fun decode(text: String): ApprovalRulesQuery =
            if (text.isBlank()) ApprovalRulesQuery() else StrictJson.decode(serializer(), text, "approval_rules payload")
    }
}

/** `approval_rules` (daemon -> client): the answer to `approval_rules` and to `approval_rule_delete`. */
@Serializable
data class ApprovalRulesList(val projectId: String? = null, val rules: List<ApprovalRule> = emptyList())

/**
 * `approval_rule_delete` (client -> daemon): remove [ruleId]. Answered by `approval_rules` listing the deleted rule's
 * project; an unknown id is refused (`error` "approval_rule_delete refused: unknown rule '<id>'").
 */
@Serializable
data class ApprovalRuleDelete(val ruleId: String) {
    companion object {
        fun decode(text: String): ApprovalRuleDelete = StrictJson.decode(serializer(), text, "approval_rule_delete payload")
    }
}

/**
 * Contract 1.79.0 (PROMPT-5): `prompt_ask` (client -> daemon). A harness-neutral question for the session named by the
 * envelope's `sessionId` (the `agenthq ask` CLI sends its own `AGENTHQ_SESSION_ID`). The daemon opens a `question`
 * prompt exactly as an adapter's would (the `prompt_request` card goes to every client) and answers THIS connection
 * with one [PromptAnswer] once it resolves. [timeoutSec] (1..86400) replaces `prompts.timeoutSec` for this prompt only.
 * Refused as an `error` "prompt_ask refused: ...": no or unknown session, a blank question, no options, a blank or
 * repeated label, a timeout out of range. Closing the connection withdraws the prompt (`resolvedBy` `cancelled`).
 */
@Serializable
data class PromptAsk(
    val question: String,
    val options: List<PromptOption>,
    val multiSelect: Boolean = false,
    val allowOther: Boolean = false,
    val timeoutSec: Int? = null,
) {
    companion object {
        fun decode(text: String): PromptAsk = StrictJson.decode(serializer(), text, "prompt_ask payload")

        /** The range [timeoutSec] accepts: the same as the `prompts.timeoutSec` setting. */
        val TIMEOUT_SEC_RANGE: IntRange = 1..86_400
    }
}

/**
 * Contract 1.79.0 (PROMPT-5): `prompt_answer` (daemon -> the asking connection only, envelope sessionId = the asker),
 * the outcome of its `prompt_ask`. [answered] false = no answer (timeout, session end, daemon stop): [answers] is empty and [otherText] null.
 */
@Serializable
data class PromptAnswer(
    val answered: Boolean,
    val answers: List<String> = emptyList(),
    val otherText: String? = null,
)
