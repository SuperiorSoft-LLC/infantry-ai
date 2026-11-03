package com.superiorsoftllc.infantry.harness

/**
 * HARNESS-CARRY-1 (harness contract 1.6.0): one exchange of a session's conversation as Infantry recorded it -- what was
 * asked ([prompt]) and the visible text of the answer ([reply]: the assistant's text, plus one `[tool] ...` line per tool
 * it used, summarised to what the transcript showed).
 */
data class CarriedTurn(val prompt: String, val reply: String)

/**
 * HARNESS-CARRY-1: how a harness takes over a conversation that began on ANOTHER harness. The daemon keeps the Infantry
 * transcript as the source of truth; on the first run after a switch it asks the target harness for that run's input,
 * given the earlier exchanges (oldest first) and the new prompt.
 *
 * A harness with a native import (a resume or a session file it can be handed) overrides this; every other one uses
 * [REPLAY], which opens the run with the history as context. Either way the agent is never told it was switched: the
 * point is that the conversation just continues.
 */
fun interface ConversationCarry {
    /** The input of the first run on this harness: [history] carried (oldest first), then [prompt], whole and last. */
    fun seed(history: List<CarriedTurn>, prompt: String): String

    companion object {
        /**
         * The replayed history's budget, in characters (~4 characters a token: ~12k tokens). Chosen to fit inside the
         * smallest context window any supported harness runs, with ample room for the agent's own work; the daemon knows
         * no per-model window, so one conservative limit beats a guess per model.
         */
        const val DEFAULT_BUDGET_CHARS: Int = 48_000

        /** The share of [DEFAULT_BUDGET_CHARS] the summary of trimmed (older) exchanges may use. */
        const val DEFAULT_SUMMARY_CHARS: Int = 4_000

        val REPLAY: ConversationCarry = ReplayCarry()
    }
}

/**
 * The harness-agnostic [ConversationCarry]: the conversation so far as an opening context block, then the prompt.
 *
 * Over [budgetChars], the most recent exchanges are kept verbatim and the older ones are summarised to one line each
 * (what was asked), newest of them first to fill [summaryChars]; any older still are counted. The NEWEST exchange is
 * always kept, cut to the budget if it alone exceeds it. The prompt itself is never counted or cut.
 */
class ReplayCarry(
    private val budgetChars: Int = ConversationCarry.DEFAULT_BUDGET_CHARS,
    private val summaryChars: Int = ConversationCarry.DEFAULT_SUMMARY_CHARS,
) : ConversationCarry {
    override fun seed(history: List<CarriedTurn>, prompt: String): String {
        if (history.isEmpty()) return prompt
        val kept = ArrayDeque<String>()
        var used = 0
        var cut = history.size
        for (i in history.indices.reversed()) {
            val rendered = render(history[i])
            if (kept.isEmpty() && rendered.length > budgetChars) {
                kept.addFirst(rendered.take(budgetChars - ELLIPSIS.length) + ELLIPSIS)
                cut = i
                break
            }
            if (used + rendered.length > budgetChars) break
            kept.addFirst(rendered)
            used += rendered.length
            cut = i
        }
        val block = buildString {
            appendLine(OPEN)
            if (cut > 0) appendLine(summary(history.subList(0, cut)))
            kept.forEach { appendLine(it) }
            append(CLOSE)
        }
        return "$block\n\n$prompt"
    }

    private fun render(turn: CarriedTurn): String = buildString {
        append("User: ").append(turn.prompt.trim()).append('\n')
        append("Assistant: ").append(turn.reply.trim().ifEmpty { "(no reply)" }).append('\n')
    }

    /** Older exchanges, one line each (what was asked), the most recent of them first in, so the summary keeps them. */
    private fun summary(older: List<CarriedTurn>): String {
        val lines = ArrayDeque<String>()
        var used = 0
        for (turn in older.reversed()) {
            val line = "- the user asked: " + turn.prompt.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ").let { if (it.length > LINE_CHARS) it.take(LINE_CHARS - ELLIPSIS.length) + ELLIPSIS else it }
            if (used + line.length + 1 > summaryChars) break
            lines.addFirst(line)
            used += line.length + 1
        }
        val omitted = older.size - lines.size
        return buildString {
            append("Earlier (summarised):")
            if (omitted > 0) append("\n- ").append(omitted).append(" earlier exchange(s) not shown")
            lines.forEach { append('\n').append(it) }
        }
    }

    companion object {
        const val OPEN = "[Our conversation so far]"
        const val CLOSE = "[End of our conversation so far]"
        private const val ELLIPSIS = " [...]"
        private const val LINE_CHARS = 200
    }
}
